#include "etroute/native_supervisor.h"

#include <cerrno>
#include <chrono>
#include <csignal>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <string>
#include <thread>

#include <unistd.h>

namespace {

namespace fs = std::filesystem;

std::string read_text(const fs::path& path) {
    std::ifstream stream(path);
    return {
        (std::istreambuf_iterator<char>(stream)),
        std::istreambuf_iterator<char>()
    };
}

bool pid_is_gone(const pid_t pid) {
    for (int attempt = 0; attempt < 100; ++attempt) {
        if (::kill(pid, 0) != 0 && errno == ESRCH) {
            return true;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(10));
    }
    return false;
}

bool test_success(const fs::path& root) {
    const auto dir = root / "success";
    fs::create_directories(dir);

    const auto stdout_path = dir / "stdout.log";
    const auto stderr_path = dir / "stderr.log";

    etroute::PreparedProcess process{
        .executable = "/bin/sh",
        .argv = {"/bin/sh", "-c", "printf ETROUTE_NATIVE_SMOKE_OK"},
        .envp = {"PATH=/usr/bin:/bin"},
        .working_directory = dir.string(),
        .stdout_path = stdout_path.string(),
        .stderr_path = stderr_path.string(),
        .timeout_ms = 2'000,
        .terminate_grace_ms = 100,
    };

    const auto result = etroute::run_process(process);
    const std::string stdout_text = read_text(stdout_path);

    if (!result.succeeded() || stdout_text != "ETROUTE_NATIVE_SMOKE_OK") {
        std::cerr << "success case failed"
                  << " stage=" << static_cast<int>(result.stage)
                  << " exit=" << result.exit_code
                  << " signal=" << result.signal
                  << " timeout=" << result.timed_out
                  << " errno=" << result.spawn_errno
                  << " stdout=" << stdout_text << '\n';
        return false;
    }

    return true;
}

bool test_exec_failure(const fs::path& root) {
    const auto dir = root / "exec-failure";
    fs::create_directories(dir);

    const std::string missing = "/definitely/missing/etroute-native-supervisor";

    etroute::PreparedProcess process{
        .executable = missing,
        .argv = {missing},
        .envp = {"PATH=/usr/bin:/bin"},
        .working_directory = dir.string(),
        .stdout_path = (dir / "stdout.log").string(),
        .stderr_path = (dir / "stderr.log").string(),
        .timeout_ms = 2'000,
        .terminate_grace_ms = 100,
    };

    const auto result = etroute::run_process(process);

    if (result.stage != etroute::SpawnStage::Execve ||
        result.spawn_errno != ENOENT ||
        result.exit_code != 127 ||
        result.timed_out) {
        std::cerr << "exec failure case failed"
                  << " stage=" << static_cast<int>(result.stage)
                  << " exit=" << result.exit_code
                  << " timeout=" << result.timed_out
                  << " errno=" << result.spawn_errno << '\n';
        return false;
    }

    return true;
}

bool test_timeout(const fs::path& root) {
    const auto dir = root / "timeout";
    fs::create_directories(dir);

    etroute::PreparedProcess process{
        .executable = "/bin/sh",
        .argv = {"/bin/sh", "-c", "sleep 5"},
        .envp = {"PATH=/usr/bin:/bin"},
        .working_directory = dir.string(),
        .stdout_path = (dir / "stdout.log").string(),
        .stderr_path = (dir / "stderr.log").string(),
        .timeout_ms = 150,
        .terminate_grace_ms = 100,
    };

    const auto result = etroute::run_process(process);

    if (!result.timed_out ||
        result.stage != etroute::SpawnStage::TimeoutKill ||
        result.duration_ms > 2'000) {
        std::cerr << "timeout case failed"
                  << " stage=" << static_cast<int>(result.stage)
                  << " exit=" << result.exit_code
                  << " signal=" << result.signal
                  << " timeout=" << result.timed_out
                  << " errno=" << result.spawn_errno
                  << " duration_ms=" << result.duration_ms << '\n';
        return false;
    }

    return true;
}

bool test_process_tree_cleanup(const fs::path& root) {
    const auto dir = root / "process-tree";
    fs::create_directories(dir);

    const auto leader_path = dir / "leader.pid";
    const auto child_path = dir / "child.pid";

    const std::string script =
        "echo $$ > leader.pid; "
        "sleep 10 & "
        "echo $! > child.pid; "
        "wait";

    etroute::PreparedProcess process{
        .executable = "/bin/sh",
        .argv = {"/bin/sh", "-c", script},
        .envp = {"PATH=/usr/bin:/bin"},
        .working_directory = dir.string(),
        .stdout_path = (dir / "stdout.log").string(),
        .stderr_path = (dir / "stderr.log").string(),
        .timeout_ms = 300,
        .terminate_grace_ms = 100,
    };

    const auto result = etroute::run_process(process);

    if (!result.timed_out || !fs::exists(leader_path) || !fs::exists(child_path)) {
        std::cerr << "process tree setup failed"
                  << " stage=" << static_cast<int>(result.stage)
                  << " timeout=" << result.timed_out << '\n';
        return false;
    }

    const pid_t leader = static_cast<pid_t>(std::stol(read_text(leader_path)));
    const pid_t child = static_cast<pid_t>(std::stol(read_text(child_path)));

    if (!pid_is_gone(leader) || !pid_is_gone(child)) {
        std::cerr << "process tree cleanup failed"
                  << " leader=" << leader
                  << " child=" << child << '\n';
        return false;
    }

    return true;
}

}  // namespace

int main() {
    const auto root =
        fs::temp_directory_path() / "etroute-native-supervisor-smoke";

    fs::remove_all(root);
    fs::create_directories(root);

    const bool ok =
        test_success(root) &&
        test_exec_failure(root) &&
        test_timeout(root) &&
        test_process_tree_cleanup(root);

    fs::remove_all(root);

    if (!ok) {
        return 1;
    }

    std::cout
        << "ETROUTE_NATIVE_SMOKE_OK\n"
        << "exec-failure: PASS\n"
        << "timeout: PASS\n"
        << "process-group cleanup: PASS\n"
        << "native supervisor contract: PASS\n";

    return 0;
}
