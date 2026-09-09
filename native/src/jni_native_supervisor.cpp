#include "etroute/etroute_jni_abi.h"
#include "etroute/native_supervisor.h"

#include <jni.h>

#include <array>
#include <string>
#include <vector>

namespace {

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
