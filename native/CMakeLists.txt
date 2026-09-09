cmake_minimum_required(VERSION 3.22)
project(etroute_native_supervisor LANGUAGES CXX)

add_library(etroute_native_supervisor STATIC
    src/native_supervisor.cpp
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
