#!/usr/bin/env bash
# Host checks only; Android device acceptance is a separate gate.
set -euo pipefail
cd "$(dirname "$0")/.."
output="$(mktemp -d)"
trap 'rm -rf "$output"' EXIT
cxx="${CXX:-g++}"
for test in jni_abi_contract_test native_supervisor_smoke cancellation_test; do
  "$cxx" -std=c++20 -Wall -Wextra -Wpedantic -Werror -pthread \
    -I native/include native/src/native_supervisor.cpp "native/tests/$test.cpp" -o "$output/$test"
  "$output/$test"
done
if [[ "${1:-}" == "--jni" ]]; then
  : "${JAVA_HOME:?Set JAVA_HOME to a JDK with JNI headers}"
  "$cxx" -std=c++20 -Wall -Wextra -Wpedantic -Werror -pthread -shared -fPIC \
    -I native/include -I "$JAVA_HOME/include" -I "$JAVA_HOME/include/linux" \
    native/src/native_supervisor.cpp native/src/jni_native_supervisor.cpp \
    -o "$output/libetroute_native_supervisor.so"
  "$JAVA_HOME/bin/javac" -d "$output/classes" tests/jni-host/org/nemack/universalfilelab/etroute/JniNativeSupervisor.java
  "$JAVA_HOME/bin/java" -Detroute.jni.library="$output/libetroute_native_supervisor.so" \
    -cp "$output/classes" org.nemack.universalfilelab.etroute.JniNativeSupervisor
fi
