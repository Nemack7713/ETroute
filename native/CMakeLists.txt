cmake_minimum_required(VERSION 3.22)
project(etroute_native_supervisor LANGUAGES CXX)

add_library(etroute_native_supervisor STATIC
    src/native_supervisor.cpp
)

set_target_properties(etroute_native_supervisor PROPERTIES
    POSITION_INDEPENDENT_CODE ON
)

target_include_directories(etroute_native_supervisor
    PUBLIC
        ${CMAKE_CURRENT_SOURCE_DIR}/include
)

target_compile_features(etroute_native_supervisor PUBLIC cxx_std_20)

target_compile_options(etroute_native_supervisor PRIVATE
    -Wall
    -Wextra
    -Wpedantic
    -Werror
)

if(ANDROID)
    add_library(etroute_native_supervisor_jni SHARED
        src/jni_native_supervisor.cpp
    )

    target_include_directories(etroute_native_supervisor_jni
        PRIVATE
            ${CMAKE_CURRENT_SOURCE_DIR}/include
    )

    target_compile_features(etroute_native_supervisor_jni PRIVATE cxx_std_20)

    target_compile_options(etroute_native_supervisor_jni PRIVATE
        -Wall
        -Wextra
        -Wpedantic
        -Werror
    )

    target_link_libraries(etroute_native_supervisor_jni
        PRIVATE
            etroute_native_supervisor
    )

    set_target_properties(etroute_native_supervisor_jni PROPERTIES
        OUTPUT_NAME "etroute_native_supervisor"
    )
endif()

option(ETROUTE_BUILD_NATIVE_TESTS "Build native supervisor smoke test" OFF)

if(ETROUTE_BUILD_NATIVE_TESTS)
    add_executable(etroute_native_supervisor_smoke
        tests/native_supervisor_smoke.cpp
    )
    target_link_libraries(etroute_native_supervisor_smoke
        PRIVATE
            etroute_native_supervisor
    )
endif()
