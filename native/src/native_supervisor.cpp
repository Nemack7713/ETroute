#include "etroute/native_supervisor.h"

#include <cerrno>
#include <chrono>
#include <fcntl.h>
#include <signal.h>
#include <sys/resource.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

#include <algorithm>
#include <cstddef>
#include <limits>
#include <thread>
#include <vector>

namespace etroute {
namespace {

using Clock = std::chrono::steady_clock;

constexpr std::uint64_t MIB = 1024ULL * 1024ULL;
constexpr std::uint64_t GIB = 1024ULL * MIB;
constexpr std::uint64_t ADVISORY_MEMORY_CAP = 8ULL * GIB;
constexpr std::uint64_t ADVISORY_MEMORY_FLOOR = 1ULL * GIB;

struct ChildFailure {
    int stage;
    int error;
};

enum class ErrorPipeRead {
    ExecSucceeded,
    ChildFailed,
    ReadFailed,
};

std::int64_t elapsed_ms(const Clock::time_point started) noexcept {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
        Clock::now() - started
    ).count();
}

bool is_absolute_path(const std::string& value) noexcept {
    return !value.empty() && value.front() == '/';
}

std::vector<char*> build_pointer_vector(const std::vector<std::string>& values) {
    std::vector<char*> result;
    result.reserve(values.size() + 1);
    for (const auto& value : values) {
        result.push_back(const_cast<char*>(value.c_str()));
    }
    result.push_back(nullptr);
    return result;
}

int open_output_file(const std::string& path) noexcept {
    return ::open(path.c_str(), O_CREAT | O_WRONLY | O_TRUNC | O_CLOEXEC, 0600);
}

bool apply_limit(const int resource, const std::uint64_t requested) noexcept {
    if (requested == 0) return true;
    const auto capped = std::min<std::uint64_t>(
        requested,
        static_cast<std::uint64_t>(std::numeric_limits<rlim_t>::max())
    );
    const rlimit value{static_cast<rlim_t>(capped), static_cast<rlim_t>(capped)};
    return ::setrlimit(resource, &value) == 0;
}

std::uint64_t advisory_address_space_limit() noexcept {
    const long pages = ::sysconf(_SC_PHYS_PAGES);
    const long page_size = ::sysconf(_SC_PAGESIZE);
    if (pages <= 0 || page_size <= 0) return 0;

    const auto pages_u = static_cast<std::uint64_t>(pages);
    const auto page_size_u = static_cast<std::uint64_t>(page_size);
    if (pages_u > std::numeric_limits<std::uint64_t>::max() / page_size_u) {
        return ADVISORY_MEMORY_CAP;
    }

    const std::uint64_t total_bytes = pages_u * page_size_u;
    if (total_bytes < 2ULL * ADVISORY_MEMORY_FLOOR) {
        // Very small devices need flexibility more than an RLIMIT_AS cap.
        return 0;
    }

    const std::uint64_t seventy_five_percent = total_bytes - (total_bytes / 4ULL);
    return std::clamp(
        seventy_five_percent,
        ADVISORY_MEMORY_FLOOR,
        ADVISORY_MEMORY_CAP
    );
}

[[noreturn]] void child_fail(
    const int error_fd,
    const SpawnStage stage,
    const int error,
    const int exit_code
) noexcept {
    const ChildFailure failure{
        static_cast<int>(stage),
        error,
    };

    const auto* bytes = reinterpret_cast<const char*>(&failure);
    std::size_t offset = 0;

    while (offset < sizeof(failure)) {
        const ssize_t written = ::write(
            error_fd,
            bytes + offset,
            sizeof(failure) - offset
        );

        if (written > 0) {
            offset += static_cast<std::size_t>(written);
            continue;
        }

        if (written < 0 && errno == EINTR) {
            continue;
        }

        break;
    }

    _exit(exit_code);
}

ErrorPipeRead read_child_failure(
    const int error_fd,
    ChildFailure& failure,
    int& read_error
) noexcept {
    auto* bytes = reinterpret_cast<char*>(&failure);
    std::size_t offset = 0;

    while (offset < sizeof(failure)) {
        const ssize_t count = ::read(
            error_fd,
            bytes + offset,
            sizeof(failure) - offset
        );

        if (count > 0) {
            offset += static_cast<std::size_t>(count);
            continue;
        }

        if (count == 0) {
            if (offset == 0) {
                return ErrorPipeRead::ExecSucceeded;
            }
            read_error = EPROTO;
            return ErrorPipeRead::ReadFailed;
        }

        if (errno == EINTR) {
            continue;
        }

        read_error = errno;
        return ErrorPipeRead::ReadFailed;
    }

    return ErrorPipeRead::ChildFailed;
}

void decode_wait_status(
    NativeRunResult& result,
    const int status,
    const bool status_valid
) noexcept {
    if (!status_valid) return;
    if (WIFEXITED(status)) result.exit_code = WEXITSTATUS(status);
    if (WIFSIGNALED(status)) result.signal = WTERMSIG(status);
}

bool process_group_exists(const pid_t pid) noexcept {
    if (::kill(-pid, 0) == 0) return true;
    return errno == EPERM;
}

void signal_process_group(const pid_t pid, const int signal) noexcept {
    if (::kill(-pid, signal) == 0 || errno == ESRCH) return;
    const int group_error = errno;
    if (::kill(pid, signal) == 0 || errno == ESRCH) return;
    errno = group_error;
}

bool reap_leader_nonblocking(
    const pid_t pid,
    int& status,
    bool& status_valid,
    int& wait_error
) noexcept {
    while (true) {
        const pid_t waited = ::waitpid(pid, &status, WNOHANG);
        if (waited == pid) {
            status_valid = true;
            return true;
        }
        if (waited == 0) {
            return false;
        }
        if (errno == EINTR) {
            continue;
        }
        wait_error = errno;
        return false;
    }
}

void terminate_process_group(
    const pid_t pid,
    const std::int64_t grace_ms,
    int& status,
    bool& status_valid,
    int& wait_error
) noexcept {
    signal_process_group(pid, SIGTERM);

    const auto grace_started = Clock::now();
    while (elapsed_ms(grace_started) < std::max<std::int64_t>(0, grace_ms)) {
        if (!status_valid) {
            int local_error = 0;
            reap_leader_nonblocking(pid, status, status_valid, local_error);
            if (local_error != 0 && local_error != ECHILD) {
                wait_error = local_error;
            }
        }

        if (status_valid && !process_group_exists(pid)) {
            return;
        }

        std::this_thread::sleep_for(std::chrono::milliseconds(10));
    }

    if (process_group_exists(pid)) {
        signal_process_group(pid, SIGKILL);
    }

    if (!status_valid) {
        while (true) {
            const pid_t waited = ::waitpid(pid, &status, 0);
            if (waited == pid) {
                status_valid = true;
                break;
            }
            if (waited < 0 && errno == EINTR) {
                continue;
            }
            if (waited < 0 && errno != ECHILD) {
                wait_error = errno;
            }
            break;
        }
    }
}

}  // namespace

NativeRunResult run_process(const PreparedProcess& process) noexcept {
    NativeRunResult result{};
    const auto started = Clock::now();

    if (!is_absolute_path(process.executable) ||
        process.argv.empty() ||
        process.argv.front() != process.executable ||
        !is_absolute_path(process.working_directory) ||
        !is_absolute_path(process.stdout_path) ||
        !is_absolute_path(process.stderr_path) ||
        process.timeout_ms <= 0 ||
        process.terminate_grace_ms < 0) {
        result.stage = SpawnStage::InvalidArgument;
        result.spawn_errno = EINVAL;
        result.duration_ms = elapsed_ms(started);
        return result;
    }

    auto argv = build_pointer_vector(process.argv);
    auto envp = build_pointer_vector(process.envp);

    const int stdout_fd = open_output_file(process.stdout_path);
    if (stdout_fd < 0) {
        result.stage = SpawnStage::OpenStdout;
        result.spawn_errno = errno;
        result.duration_ms = elapsed_ms(started);
        return result;
    }

    const int stderr_fd = open_output_file(process.stderr_path);
    if (stderr_fd < 0) {
        result.stage = SpawnStage::OpenStderr;
        result.spawn_errno = errno;
        ::close(stdout_fd);
        result.duration_ms = elapsed_ms(started);
        return result;
    }

    int error_pipe[2] = {-1, -1};
    if (::pipe2(error_pipe, O_CLOEXEC) != 0) {
        result.stage = SpawnStage::ErrorPipe;
        result.spawn_errno = errno;
        ::close(stdout_fd);
        ::close(stderr_fd);
        result.duration_ms = elapsed_ms(started);
        return result;
    }

    const pid_t pid = ::fork();
    if (pid < 0) {
        result.stage = SpawnStage::Fork;
        result.spawn_errno = errno;
        ::close(error_pipe[0]);
        ::close(error_pipe[1]);
        ::close(stdout_fd);
        ::close(stderr_fd);
        result.duration_ms = elapsed_ms(started);
        return result;
    }

    if (pid == 0) {
        ::close(error_pipe[0]);

        if (::setpgid(0, 0) != 0) {
            const int error = errno;
            child_fail(error_pipe[1], SpawnStage::Setpgid, error, 121);
        }

        const std::uint64_t address_space_limit =
            process.limits.max_address_space_bytes != 0
                ? process.limits.max_address_space_bytes
                : advisory_address_space_limit();

        if (!apply_limit(RLIMIT_CPU, process.limits.cpu_seconds) ||
            !apply_limit(RLIMIT_NOFILE, process.limits.max_open_files) ||
            !apply_limit(RLIMIT_FSIZE, process.limits.max_file_bytes) ||
            !apply_limit(RLIMIT_AS, address_space_limit)) {
            const int error = errno;
            child_fail(error_pipe[1], SpawnStage::ResourceLimit, error, 122);
        }

        if (::chdir(process.working_directory.c_str()) != 0) {
            const int error = errno;
            child_fail(error_pipe[1], SpawnStage::Chdir, error, 123);
        }

        if (::dup2(stdout_fd, STDOUT_FILENO) < 0) {
            const int error = errno;
            child_fail(error_pipe[1], SpawnStage::Dup2Stdout, error, 124);
        }

        if (::dup2(stderr_fd, STDERR_FILENO) < 0) {
            const int error = errno;
            child_fail(error_pipe[1], SpawnStage::Dup2Stderr, error, 124);
        }

        ::close(stdout_fd);
        ::close(stderr_fd);

        ::execve(process.executable.c_str(), argv.data(), envp.data());

        const int error = errno;
        child_fail(error_pipe[1], SpawnStage::Execve, error, 127);
    }

    ::close(error_pipe[1]);
    ::close(stdout_fd);
    ::close(stderr_fd);

    (void)::setpgid(pid, pid);

    ChildFailure failure{};
    int pipe_error = 0;
    const auto pipe_state = read_child_failure(error_pipe[0], failure, pipe_error);
    ::close(error_pipe[0]);

    if (pipe_state == ErrorPipeRead::ChildFailed) {
        result.stage = static_cast<SpawnStage>(failure.stage);
        result.spawn_errno = failure.error;

        int status = 0;
        bool status_valid = false;
        while (true) {
            const pid_t waited = ::waitpid(pid, &status, 0);
            if (waited == pid) {
                status_valid = true;
                break;
            }
            if (waited < 0 && errno == EINTR) continue;
            if (waited < 0 && result.spawn_errno == 0) {
                result.spawn_errno = errno;
                result.stage = SpawnStage::Waitpid;
            }
            break;
        }

        decode_wait_status(result, status, status_valid);
        result.duration_ms = elapsed_ms(started);
        return result;
    }

    if (pipe_state == ErrorPipeRead::ReadFailed) {
        result.stage = SpawnStage::ErrorPipe;
        result.spawn_errno = pipe_error;
        signal_process_group(pid, SIGKILL);

        int status = 0;
        bool status_valid = false;
        while (true) {
            const pid_t waited = ::waitpid(pid, &status, 0);
            if (waited == pid) {
                status_valid = true;
                break;
            }
            if (waited < 0 && errno == EINTR) continue;
            break;
        }

        decode_wait_status(result, status, status_valid);
        result.duration_ms = elapsed_ms(started);
        return result;
    }

    int status = 0;
    bool status_valid = false;

    while (true) {
        const pid_t waited = ::waitpid(pid, &status, WNOHANG);

        if (waited == pid) {
            status_valid = true;
            break;
        }

        if (waited < 0) {
            if (errno == EINTR) continue;

            result.stage = SpawnStage::Waitpid;
            result.spawn_errno = errno;

            int wait_error = 0;
            terminate_process_group(pid, 0, status, status_valid, wait_error);
            break;
        }

        if (elapsed_ms(started) >= process.timeout_ms) {
            result.timed_out = true;
            result.stage = SpawnStage::TimeoutKill;

            int wait_error = 0;
            terminate_process_group(
                pid,
                process.terminate_grace_ms,
                status,
                status_valid,
                wait_error
            );

            if (wait_error != 0 && result.spawn_errno == 0) {
                result.spawn_errno = wait_error;
            }
            break;
        }

        std::this_thread::sleep_for(std::chrono::milliseconds(10));
    }

    decode_wait_status(result, status, status_valid);
    result.duration_ms = elapsed_ms(started);
    return result;
}

}  // namespace etroute
