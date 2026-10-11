cmake_minimum_required(VERSION 3.22)
project(nyanime_reader_quality LANGUAGES C CXX)
include(FetchContent)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_CXX_STANDARD 17)
add_compile_options(
    "-ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=/nyanime/reader-quality"
    "-ffile-prefix-map=${CMAKE_BINARY_DIR}=/nyanime/reader-quality-build"
)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(NCNN_VERSION 20240410)
set(NCNN_VULKAN OFF CACHE BOOL "" FORCE)
set(NCNN_OPENMP OFF CACHE BOOL "" FORCE)
set(NCNN_DISABLE_EXCEPTION OFF CACHE BOOL "" FORCE)
set(NCNN_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(NCNN_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(NCNN_BUILD_BENCHMARK OFF CACHE BOOL "" FORCE)
set(NCNN_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(NCNN_INSTALL_SDK OFF CACHE BOOL "" FORCE)
set(NCNN_SOURCE_ARCHIVE "https://codeload.github.com/Tencent/ncnn/tar.gz/56775de50990ab7f16627efdcf5529b49541206f" CACHE STRING "Verified NCNN source archive")
FetchContent_Declare(ncnn
    URL "${NCNN_SOURCE_ARCHIVE}"
    URL_HASH SHA256=25d1a616bb3a598535545e217eb6fc5df98c4ba04344b48dbb141bbb108801ef
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE
)
FetchContent_MakeAvailable(ncnn)
add_library(nyanime_reader_quality SHARED reader_quality.cpp)
target_link_libraries(nyanime_reader_quality PRIVATE ncnn jnigraphics log)
target_compile_options(nyanime_reader_quality PRIVATE -fexceptions -fvisibility=hidden -fvisibility-inlines-hidden)
target_link_options(nyanime_reader_quality PRIVATE -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384)
