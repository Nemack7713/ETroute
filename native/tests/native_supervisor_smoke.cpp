#include "etroute/native_supervisor.h"

#include <filesystem>
#include <fstream>
#include <iostream>
#include <string>

int main() {
    namespace fs = std::filesystem;

    const auto temp = fs::temp_directory_path() / "etroute-native-supervisor-smoke";
    fs::create_directories(temp);

    const auto stdout_path = temp / "stdout.log";
    const auto stderr_path = temp / "stderr.log";

    etroute::PreparedProcess process{
        .executable = "/bin/sh",
        .argv = {"/bin/sh", "-c", "printf ETROUTE_NATIVE_SMOKE_OK"},
        .envp = {"PATH=/usr/bin:/bin"},
        .working_directory = temp.string(),
        .stdout_path = stdout_path.string(),
        .stderr_path = stderr_path.string(),
        .timeout_ms = 2'000,
        .terminate_grace_ms = 100,
    };

    const auto result = etroute::run_process(process);

    std::ifstream stream(stdout_path);
    const std::string stdout_text(
        (std::istreambuf_iterator<char>(stream)),
        std::istreambuf_iterator<char>()
    );

    if (!result.succeeded() || stdout_text != "ETROUTE_NATIVE_SMOKE_OK") {
        std::cerr << "native supervisor smoke failed"
                  << " exit=" << result.exit_code
                  << " signal=" << result.signal
                  << " timeout=" << result.timed_out
                  << " errno=" << result.spawn_errno
                  << " stdout=" << stdout_text << '\n';
        return 1;
    }

    std::cout << "ETROUTE_NATIVE_SMOKE_OK\n";
    return 0;
}
