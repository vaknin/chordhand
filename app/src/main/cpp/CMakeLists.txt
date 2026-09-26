cmake_minimum_required(VERSION 3.22)
project(chordhand_listen C CXX)

# whisper.cpp, fetched at a pinned release and built into this one library. Speech recognition
# is far too slow unoptimised, so it is always a release build, debug APK or not.
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)

include(FetchContent)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
# Dot product and half floats: every arm64 phone from the last several years has both.
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)

FetchContent_Declare(
    whisper
    URL https://github.com/ggml-org/whisper.cpp/archive/refs/tags/v1.9.4.tar.gz
    URL_HASH SHA256=57e280cee375ab02425b806ad5146b99f6eb9357e3c2b31357c8a6af2e2e44ae
)
FetchContent_MakeAvailable(whisper)

add_library(chordhand_listen SHARED listen_jni.cpp)
target_compile_options(chordhand_listen PRIVATE -O3)
target_link_libraries(chordhand_listen whisper log)
