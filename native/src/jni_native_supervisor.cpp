#include "etroute/etroute_jni_abi.h"
#include "etroute/native_supervisor.h"

#include <jni.h>

#include <array>
#include <cerrno>
#include <cstdint>
#include <string>
#include <vector>

namespace {

static_assert(static_cast<std::int64_t>(etroute::SpawnStage::Ok) ==
              static_cast<std::int64_t>(etroute::jni::SpawnStageWire::Ok));
static_assert(static_cast<std::int64_t>(etroute::SpawnStage::InvalidArgument) ==
              static_cast<std::int64_t>(etroute::jni::SpawnStageWire::InvalidArgument));
static_assert(static_cast<std::int64_t>(etroute::SpawnStage::OpenStdout) ==
              static_cast<std::int64_t>(etroute::jni::SpawnStageWire::OpenStdout));
static_assert(static_cast<std::int64_t>(etroute::SpawnStage::OpenStderr) ==
              static_cast<std::int64_t>(etroute::jni::SpawnStageWire::OpenStderr));
static_assert(static_cast<std::int64_t>(etroute::SpawnStage::ErrorPipe) ==
              static_cast<std::int64_t>(etroute::jni::SpawnStageWire::ErrorPipe));
static_assert(static_cast<std::int64_t>(etroute::SpawnStage::Fork) ==
              static_cast<std::int64_t>(etroute::jni::SpawnStageWire::Fork));
static_assert(static_cast<std::int64_t>(etroute::SpawnStage::Setpgid) ==
              static_cast<std::int64_t>(etroute::jni::SpawnStageWire::Setpgid));
static_assert(static_cast<std::int64_t>(etroute::SpawnStage::ResourceLimit) ==
              static_cast<std::int64_t>(etroute::jni::SpawnStageWire::ResourceLimit));
static_assert(static_cast<std::int64_t>(etroute::SpawnStage::Chdir) ==
              static_cast<std::int64_t>(etroute::jni::SpawnStageWire::Chdir));
static_assert(static_cast<std::int64_t>(etroute::SpawnStage::Dup2Stdout) ==
              static_cast<std::int64_t>(etroute::jni::SpawnStageWire::Dup2Stdout));
static_assert(static_cast<std::int64_t>(etroute::SpawnStage::Dup2Stderr) ==
              static_cast<std::int64_t>(etroute::jni::SpawnStageWire::Dup2Stderr));
static_assert(static_cast<std::int64_t>(etroute::SpawnStage::Execve) ==
              static_cast<std::int64_t>(etroute::jni::SpawnStageWire::Execve));
static_assert(static_cast<std::int64_t>(etroute::SpawnStage::Waitpid) ==
              static_cast<std::int64_t>(etroute::jni::SpawnStageWire::Waitpid));
static_assert(static_cast<std::int64_t>(etroute::SpawnStage::TimeoutKill) ==
              static_cast<std::int64_t>(etroute::jni::SpawnStageWire::TimeoutKill));

std::vector<std::string> to_strings(JNIEnv* env, jobjectArray array) {
    std::vector<std::string> result;
    const jsize size = env->GetArrayLength(array);
    result.reserve(static_cast<std::size_t>(size));

    for (jsize i = 0; i < size; ++i) {
        auto value = static_cast<jstring>(env->GetObjectArrayElement(array, i));
        const char* chars = env->GetStringUTFChars(value, nullptr);
        result.emplace_back(chars);
        env->ReleaseStringUTFChars(value, chars);
        env->DeleteLocalRef(value);
    }

    return result;
}

std::string to_string(JNIEnv* env, jstring value) {
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

jlongArray pack_result(JNIEnv* env, const etroute::NativeRunResult& result) {
    using etroute::jni::ABI_VERSION;
    using etroute::jni::RESULT_FIELD_COUNT;

    const std::array<jlong, RESULT_FIELD_COUNT> values{
        ABI_VERSION,
        static_cast<jlong>(result.exit_code),
        static_cast<jlong>(result.signal),
        result.timed_out ? 1L : 0L,
        static_cast<jlong>(result.duration_ms),
        static_cast<jlong>(result.spawn_errno),
        static_cast<jlong>(result.stage),
    };

    jlongArray array = env->NewLongArray(static_cast<jsize>(values.size()));
    if (array == nullptr) return nullptr;

    env->SetLongArrayRegion(
        array,
        0,
        static_cast<jsize>(values.size()),
        values.data()
    );
    return array;
}

etroute::NativeRunResult invalid_argument_result() noexcept {
    etroute::NativeRunResult result{};
    result.stage = etroute::SpawnStage::InvalidArgument;
    result.spawn_errno = EINVAL;
    return result;
}

}  // namespace

extern "C"
JNIEXPORT jlong JNICALL
Java_org_nemack_universalfilelab_etroute_JniNativeSupervisor_nativeAbiVersion(
    JNIEnv*,
    jobject
) {
    return etroute::jni::ABI_VERSION;
}

extern "C"
JNIEXPORT jlongArray JNICALL
Java_org_nemack_universalfilelab_etroute_JniNativeSupervisor_nativeRun(
    JNIEnv* env,
    jobject,
    jstring executable_value,
    jobjectArray argv_value,
    jobjectArray envp_value,
    jstring cwd_value,
    jstring stdout_value,
    jstring stderr_value,
    jlong timeout_ms,
    jlong terminate_grace_ms,
    jlong cpu_seconds,
    jlong max_open_files,
    jlong max_file_bytes
) {
    if (executable_value == nullptr ||
        argv_value == nullptr ||
        envp_value == nullptr ||
        cwd_value == nullptr ||
        stdout_value == nullptr ||
        stderr_value == nullptr ||
        timeout_ms <= 0 ||
        terminate_grace_ms < 0 ||
        cpu_seconds < 0 ||
        max_open_files < 0 ||
        max_file_bytes < 0) {
        return pack_result(env, invalid_argument_result());
    }

    etroute::PreparedProcess process{
        .executable = to_string(env, executable_value),
        .argv = to_strings(env, argv_value),
        .envp = to_strings(env, envp_value),
        .working_directory = to_string(env, cwd_value),
        .stdout_path = to_string(env, stdout_value),
        .stderr_path = to_string(env, stderr_value),
        .timeout_ms = static_cast<std::int64_t>(timeout_ms),
        .terminate_grace_ms = static_cast<std::int64_t>(terminate_grace_ms),
        .limits = etroute::NativeResourceLimits{
            .cpu_seconds = static_cast<std::uint64_t>(cpu_seconds),
            .max_open_files = static_cast<std::uint64_t>(max_open_files),
            .max_file_bytes = static_cast<std::uint64_t>(max_file_bytes),
        },
    };

    return pack_result(env, etroute::run_process(process));
}
