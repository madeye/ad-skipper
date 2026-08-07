cmake_minimum_required(VERSION 3.22.1)
project(vlm_jni CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# llama.cpp is vendored here (gitignored, see README "拉取 llama.cpp").
set(LLAMA_SRC ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp)
if (NOT EXISTS ${LLAMA_SRC}/CMakeLists.txt)
    message(FATAL_ERROR
        "llama.cpp source not found at ${LLAMA_SRC}\n"
        "Run: git clone --depth 1 https://github.com/ggml-org/llama.cpp.git ${LLAMA_SRC}")
endif()

# Library-only build: no curl/tools/tests; mtmd is built by the top-level
# CMakeLists when LLAMA_BUILD_MTMD=ON and tools are off.
set(LLAMA_STANDALONE    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD      ON CACHE BOOL "" FORCE)
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)
set(LLAMA_NATIVE         OFF CACHE BOOL "" FORCE) # cross-compiling for Android
set(GGML_NATIVE          OFF CACHE BOOL "" FORCE)
set(MTMD_VIDEO           OFF CACHE BOOL "" FORCE)

add_subdirectory(${LLAMA_SRC} ${CMAKE_CURRENT_BINARY_DIR}/llama.cpp)

add_library(vlm_jni SHARED vlm_jni.cpp)
target_include_directories(vlm_jni PRIVATE
    ${LLAMA_SRC}/include
    ${LLAMA_SRC}/ggml/include
    ${LLAMA_SRC}/tools/mtmd)
target_compile_features(vlm_jni PRIVATE cxx_std_17)
# 16KB page-size alignment (required by recent Android / Google Play).
target_link_options(vlm_jni PRIVATE "-Wl,-z,max-page-size=16384")

find_library(log_lib log)
target_link_libraries(vlm_jni PRIVATE mtmd llama ${log_lib} jnigraphics android)
