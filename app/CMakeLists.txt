cmake_minimum_required(VERSION 3.22.1)

project("glaive")

add_library(
        glaive_core
        SHARED
        src/main/cpp/glaive_core.c)

# Target high performance
set(CMAKE_C_STANDARD 99)
target_compile_options(glaive_core PRIVATE
    -O3
    -Wall
    -std=c99
    -fno-exceptions
    -ffast-math
    -funroll-loops
    -fomit-frame-pointer
    -march=armv8-a+simd
)

find_library(log-lib log)

target_link_libraries(
        glaive_core
        ${log-lib})
