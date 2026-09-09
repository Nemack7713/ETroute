#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace etroute {

enum class SpawnStage : int {
    Ok = 0,
    InvalidArgument = 1,
    OpenStdout = 2,
    OpenStderr = 3,
    ErrorPipe = 4,
    Fork = 5,
    Setpgid = 6,
    ResourceLimit = 7,
    Chdir = 8,
    Dup2Stdout = 9,
    Dup2Stderr = 10,
    Execve = 11,
    Waitpid = 12,
    TimeoutKill = 13,
};

struct NativeResourceLimits {
    std::uint64_t cpu_seconds = 0;
    std::uint64_t max_open_files = 0;
    std::uint64_t max_file_bytes = 0;
};

struct PreparedProcess {
    std::string executable;
    std::vector<std::string> argv;
    std::vector<std::string> envp;
    std::string working_directory;
    std::string stdout_path;
    std::string stderr_path;
    std::int64_t timeout_ms = 60'000;
    std::int64_t terminate_grace_ms = 500;
    NativeResourceLimits limits{};
};

struct NativeRunResult {
    int exit_code = -1;
    int signal = 0;
    bool timed_out = false;
    int spawn_errno = 0;
    SpawnStage stage = SpawnStage::Ok;
    std::int64_t duration_ms = 0;

    [[nodiscard]] bool succeeded() const noexcept {
        return stage == SpawnStage::Ok &&
               !timed_out &&
               spawn_errno == 0 &&
               signal == 0 &&
               exit_code == 0;
    }
};

[[nodiscard]] NativeRunResult run_process(const PreparedProcess& process) noexcept;

}  // namespace etroute
