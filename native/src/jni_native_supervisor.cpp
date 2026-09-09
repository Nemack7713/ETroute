#include "etroute/etroute_jni_abi.h"
#include "etroute/native_supervisor.h"

#include <jni.h>

#include <array>
#include <cerrno>
#include <cstdint>
#include <exception>
#include <new>
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

void throw_java_exception(JNIEnv* env, const char* class_name, const char* message) noexcept {
    if (env == nullptr || env->ExceptionCheck()) return;
    jclass exception_class = env->FindClass(class_name);
    if (exception_class == nullptr) return;
    env->ThrowNew(exception_class, message);
    env->DeleteLocalRef(exception_class);
}

void throw_illegal_argument(JNIEnv* env, const char* message) noexcept {
    throw_java_exception(env, "java/lang/IllegalArgumentException", message);
}

bool to_string(JNIEnv* env, jstring value, std::string& result) {
    if (value == nullptr) {
        throw_illegal_argument(env, "ETroute JNI string argument must not be null");
        return false;
    }
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return false;
    try {
        result.assign(chars);
    } catch (...) {
        env->ReleaseStringUTFChars(value, chars);
        throw;
    }
    env->ReleaseStringUTFChars(value, chars);
    return !env->ExceptionCheck();
}

bool to_strings(JNIEnv* env, jobjectArray array, std::vector<std::string>& result) {
    if (array == nullptr) {
        throw_illegal_argument(env, "ETroute JNI string-array argument must not be null");
        return false;
    }
    const jsize size = env->GetArrayLength(array);
    if (env->ExceptionCheck()) return false;
    result.clear();
    result.reserve(static_cast<std::size_t>(size));
    for (jsize i = 0; i < size; ++i) {
        jobject local = env->GetObjectArrayElement(array, i);
        if (local == nullptr) {
            if (!env->ExceptionCheck()) {
                throw_illegal_argument(env, "ETroute JNI string arrays must not contain null elements");
            }
            return false;
        }
        auto value = static_cast<jstring>(local);
        std::string converted;
        const bool ok = to_string(env, value, converted);
        env->DeleteLocalRef(local);
        if (!ok || env->ExceptionCheck()) return false;
        result.emplace_back(std::move(converted));
    }
    return true;
}

jlongArray pack_result(JNIEnv* env, const etroute::NativeRunResult& result) noexcept {
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
    if (env->ExceptionCheck()) return nullptr;
    jlongArray array = env->NewLongArray(static_cast<jsize>(values.size()));
    if (array == nullptr || env->ExceptionCheck()) return nullptr;
    env->SetLongArrayRegion(array, 0, static_cast<jsize>(values.size()), values.data());
    if (env->ExceptionCheck()) return nullptr;
    return array;
}

bool validate_scalar_arguments(
    JNIEnv* env,
    const jlong timeout_ms,
    const jlong terminate_grace_ms,
    const jlong cpu_seconds,
    const jlong max_open_files,
    const jlong max_file_bytes,
    const jlong max_address_space_bytes
) noexcept {
    if (timeout_ms <= 0) {
        throw_illegal_argument(env, "timeoutMs must be greater than zero");
        return false;
    }
    if (terminate_grace_ms < 0) {
        throw_illegal_argument(env, "terminateGraceMs must not be negative");
        return false;
    }
    if (cpu_seconds < 0 || max_open_files < 0 || max_file_bytes < 0 || max_address_space_bytes < 0) {
        throw_illegal_argument(env, "ETroute resource limits must not be negative");
        return false;
    }
    return true;
}

}  // namespace

extern "C"
JNIEXPORT jlong JNICALL
Java_org_nemack_universalfilelab_etroute_JniNativeSupervisor_nativeAbiVersion(
    JNIEnv*, jobject
) noexcept {
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
    jlong max_file_bytes,
    jlong max_address_space_bytes
) noexcept {
    try {
        if (env == nullptr) return nullptr;
        if (executable_value == nullptr || argv_value == nullptr || envp_value == nullptr ||
            cwd_value == nullptr || stdout_value == nullptr || stderr_value == nullptr) {
            throw_illegal_argument(env, "ETroute JNI arguments must not be null");
            return nullptr;
        }
        if (!validate_scalar_arguments(
                env,
                timeout_ms,
                terminate_grace_ms,
                cpu_seconds,
                max_open_files,
                max_file_bytes,
                max_address_space_bytes)) {
            return nullptr;
        }

        std::string executable;
        std::string cwd;
        std::string stdout_path;
        std::string stderr_path;
        std::vector<std::string> argv;
        std::vector<std::string> envp;

        if (!to_string(env, executable_value, executable) ||
            !to_strings(env, argv_value, argv) ||
            !to_strings(env, envp_value, envp) ||
            !to_string(env, cwd_value, cwd) ||
            !to_string(env, stdout_value, stdout_path) ||
            !to_string(env, stderr_value, stderr_path)) {
            return nullptr;
        }
        if (env->ExceptionCheck()) return nullptr;

        etroute::PreparedProcess process{
            .executable = std::move(executable),
            .argv = std::move(argv),
            .envp = std::move(envp),
            .working_directory = std::move(cwd),
            .stdout_path = std::move(stdout_path),
            .stderr_path = std::move(stderr_path),
            .timeout_ms = static_cast<std::int64_t>(timeout_ms),
            .terminate_grace_ms = static_cast<std::int64_t>(terminate_grace_ms),
            .limits = etroute::NativeResourceLimits{
                .cpu_seconds = static_cast<std::uint64_t>(cpu_seconds),
                .max_open_files = static_cast<std::uint64_t>(max_open_files),
                .max_file_bytes = static_cast<std::uint64_t>(max_file_bytes),
                .max_address_space_bytes = static_cast<std::uint64_t>(max_address_space_bytes),
            },
        };

        const etroute::NativeRunResult result = etroute::run_process(process);
        return pack_result(env, result);
    } catch (const std::bad_alloc&) {
        throw_java_exception(env, "java/lang/OutOfMemoryError", "ETroute native allocation failed");
        return nullptr;
    } catch (const std::exception& error) {
        throw_java_exception(env, "java/lang/RuntimeException", error.what());
        return nullptr;
    } catch (...) {
        throw_java_exception(env, "java/lang/Error", "Unknown ETroute native exception");
        return nullptr;
    }
}
