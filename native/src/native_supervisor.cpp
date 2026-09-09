#include "etroute/native_supervisor.h"

#include <cerrno>
#include <chrono>
#include <fcntl.h>
#include <sys/resource.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

#include <algorithm>
#include <limits>
#include <vector>

namespace etroute {
namespace {

using Clock = std::chrono::steady_clock;

std::int64_t elapsed_ms(const Clock::time_point started) noexcept {
    return std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now() - started).count();
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
        process.timeout_ms <= 0) {
        result.spawn_errno = EINVAL;
        result.duration_ms = elapsed_ms(started);
        return result;
    }

    auto argv = build_pointer_vector(process.argv);
    auto envp = build_pointer_vector(process.envp);

    const int stdout_fd = open_output_file(process.stdout_path);
    if (stdout_fd < 0) {
        result.spawn_errno = errno;
        result.duration_ms = elapsed_ms(started);
        return result;
    }

    const int stderr_fd = open_output_file(process.stderr_path);
    if (stderr_fd < 0) {
        result.spawn_errno = errno;
        ::close(stdout_fd);
        result.duration_ms = elapsed_ms(started);
        return result;
    }

    const pid_t pid = ::fork();
    if (pid < 0) {
        result.spawn_errno = errno;
        ::close(stdout_fd);
        ::close(stderr_fd);
        result.duration_ms = elapsed_ms(started);
        return result;
    }

    if (pid == 0) {
        if (!apply_limit(RLIMIT_CPU, process.limits.cpu_seconds) ||
            !apply_limit(RLIMIT_NOFILE, process.limits.max_open_files) ||
            !apply_limit(RLIMIT_FSIZE, process.limits.max_file_bytes)) {
            _exit(122);
        }
        if (::chdir(process.working_directory.c_str()) != 0) _exit(123);
        if (::dup2(stdout_fd, STDOUT_FILENO) < 0 ||
            ::dup2(stderr_fd, STDERR_FILENO) < 0) _exit(124);
        ::close(stdout_fd);
        ::close(stderr_fd);
        ::execve(process.executable.c_str(), argv.data(), envp.data());
        _exit(127);
    }

    ::close(stdout_fd);
    ::close(stderr_fd);

    int status = 0;
    while (::waitpid(pid, &status, 0) == -1 && errno == EINTR) {
    }

    if (WIFEXITED(status)) result.exit_code = WEXITSTATUS(status);
    if (WIFSIGNALED(status)) result.signal = WTERMSIG(status);
    result.duration_ms = elapsed_ms(started);
    return result;
}

}  // namespace etroute
