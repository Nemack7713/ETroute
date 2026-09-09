#include "etroute/etroute_jni_abi.h"

#include <cstdint>
#include <iostream>

namespace {

bool expect(const bool condition, const char* message) {
    if (condition) return true;
    std::cerr << "JNI ABI contract failure: " << message << '\n';
    return false;
}

}  // namespace

int main() {
    using etroute::jni::ABI_VERSION;
    using etroute::jni::RESULT_FIELD_COUNT;
    using etroute::jni::ResultField;
    using etroute::jni::SpawnStageWire;

    bool ok = true;

    ok &= expect(ABI_VERSION == 1, "ABI_VERSION must remain 1 for v1");
    ok &= expect(RESULT_FIELD_COUNT == 7, "v1 result must contain seven fields");

    ok &= expect(static_cast<std::size_t>(ResultField::AbiVersion) == 0, "AbiVersion index drifted");
    ok &= expect(static_cast<std::size_t>(ResultField::ExitCode) == 1, "ExitCode index drifted");
    ok &= expect(static_cast<std::size_t>(ResultField::Signal) == 2, "Signal index drifted");
    ok &= expect(static_cast<std::size_t>(ResultField::TimedOut) == 3, "TimedOut index drifted");
    ok &= expect(static_cast<std::size_t>(ResultField::DurationMs) == 4, "DurationMs index drifted");
    ok &= expect(static_cast<std::size_t>(ResultField::SpawnErrno) == 5, "SpawnErrno index drifted");
    ok &= expect(static_cast<std::size_t>(ResultField::SpawnStage) == 6, "SpawnStage index drifted");

    ok &= expect(static_cast<std::int64_t>(SpawnStageWire::Ok) == 0, "Ok stage drifted");
    ok &= expect(static_cast<std::int64_t>(SpawnStageWire::InvalidArgument) == 1, "InvalidArgument stage drifted");
    ok &= expect(static_cast<std::int64_t>(SpawnStageWire::OpenStdout) == 2, "OpenStdout stage drifted");
    ok &= expect(static_cast<std::int64_t>(SpawnStageWire::OpenStderr) == 3, "OpenStderr stage drifted");
    ok &= expect(static_cast<std::int64_t>(SpawnStageWire::ErrorPipe) == 4, "ErrorPipe stage drifted");
    ok &= expect(static_cast<std::int64_t>(SpawnStageWire::Fork) == 5, "Fork stage drifted");
    ok &= expect(static_cast<std::int64_t>(SpawnStageWire::Setpgid) == 6, "Setpgid stage drifted");
    ok &= expect(static_cast<std::int64_t>(SpawnStageWire::ResourceLimit) == 7, "ResourceLimit stage drifted");
    ok &= expect(static_cast<std::int64_t>(SpawnStageWire::Chdir) == 8, "Chdir stage drifted");
    ok &= expect(static_cast<std::int64_t>(SpawnStageWire::Dup2Stdout) == 9, "Dup2Stdout stage drifted");
    ok &= expect(static_cast<std::int64_t>(SpawnStageWire::Dup2Stderr) == 10, "Dup2Stderr stage drifted");
    ok &= expect(static_cast<std::int64_t>(SpawnStageWire::Execve) == 11, "Execve stage drifted");
    ok &= expect(static_cast<std::int64_t>(SpawnStageWire::Waitpid) == 12, "Waitpid stage drifted");
    ok &= expect(static_cast<std::int64_t>(SpawnStageWire::TimeoutKill) == 13, "TimeoutKill stage drifted");

    if (!ok) return 1;

    std::cout << "ETROUTE_JNI_ABI_V1_OK\n";
    return 0;
}
