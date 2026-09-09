#pragma once

#include <cstddef>
#include <cstdint>

namespace etroute::jni {

inline constexpr std::int64_t ABI_VERSION = 1;
inline constexpr std::size_t RESULT_FIELD_COUNT = 7;

enum class ResultField : std::size_t {
    AbiVersion = 0,
    ExitCode = 1,
    Signal = 2,
    TimedOut = 3,
    DurationMs = 4,
    SpawnErrno = 5,
    SpawnStage = 6,
};

enum class SpawnStageWire : std::int64_t {
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

}  // namespace etroute::jni
