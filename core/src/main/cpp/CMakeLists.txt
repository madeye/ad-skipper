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
# OpenMP worker threads are crash-prone on Android (SIGSEGV inside
# __kmp_invoke_microtask during mul_mat); ggml's own threadpool is used instead.
set(GGML_OPENMP          OFF CACHE BOOL "" FORCE)
# GPU inference via the ggml Vulkan backend (works on Android GPUs; falls
# back to CPU when no Vulkan device is present).
set(GGML_VULKAN           ON CACHE BOOL "" FORCE)
set(MTMD_VIDEO           OFF CACHE BOOL "" FORCE)

# Cross-compiling: CMake's FindVulkan / find_package can't see host or NDK
# paths on their own — point everything out explicitly.
# Headers come from Khronos Vulkan-Headers (brew install vulkan-headers), NOT
# the NDK sysroot: ggml-vulkan includes <vulkan/vulkan.hpp> (Vulkan-Hpp C++
# bindings), which the NDK doesn't ship, and vulkan.h/vulkan.hpp must be the
# same version. Only the loader (libvulkan.so) is taken from the NDK.
foreach(candidate /opt/homebrew/include /usr/local/include)
    if (EXISTS ${candidate}/vulkan/vulkan.hpp)
        set(Vulkan_INCLUDE_DIR ${candidate} CACHE PATH "" FORCE)
        break()
    endif()
endforeach()
if (NOT Vulkan_INCLUDE_DIR)
    message(FATAL_ERROR "vulkan/vulkan.hpp not found — brew install vulkan-headers")
endif()
# CMAKE_SYSTEM_VERSION is not reliable at this point (may still be the host
# value), so prefer the toolchain's ANDROID_PLATFORM_LEVEL, falling back to
# the project's minSdk.
if (DEFINED ANDROID_PLATFORM_LEVEL)
    set(_vulkan_api_dir ${ANDROID_PLATFORM_LEVEL})
else()
    set(_vulkan_api_dir 30)
endif()
# The NDK's host-tag (darwin-x86_64 / linux-x86_64) is set by the Android
# toolchain file; derive it from the host OS when absent so the same paths
# resolve on both macOS dev machines and Linux CI runners.
if (NOT DEFINED ANDROID_HOST_TAG)
    if (CMAKE_HOST_SYSTEM_NAME STREQUAL "Linux")
        set(ANDROID_HOST_TAG linux-x86_64)
    else()
        set(ANDROID_HOST_TAG darwin-x86_64)
    endif()
endif()
set(Vulkan_LIBRARY
    ${ANDROID_NDK}/toolchains/llvm/prebuilt/${ANDROID_HOST_TAG}/sysroot/usr/lib/aarch64-linux-android/${_vulkan_api_dir}/libvulkan.so
    CACHE FILEPATH "" FORCE)

# ggml-vulkan needs a host glslc to compile shaders. find_program is confined
# to the target sysroot by the Android toolchain, so probe literal paths with
# EXISTS instead (Homebrew/system first, then the NDK's shader-tools).
foreach(candidate
        /opt/homebrew/bin/glslc
        /usr/local/bin/glslc
        /usr/bin/glslc
        ${ANDROID_NDK}/shader-tools/${ANDROID_HOST_TAG}/glslc)
    if (EXISTS ${candidate})
        set(Vulkan_GLSLC_EXECUTABLE ${candidate} CACHE FILEPATH "" FORCE)
        break()
    endif()
endforeach()

# vulkan-shaders-gen is a host tool built via ExternalProject; its nested
# CMake inherits the Ninja generator but not a PATH that contains ninja, so
# hand it a host toolchain that pins CMAKE_MAKE_PROGRAM to the SDK's ninja
# (and the host clang, since the Android toolchain must not leak in).
file(WRITE ${CMAKE_BINARY_DIR}/vk-host-toolchain.cmake "
set(CMAKE_BUILD_TYPE Release)
set(CMAKE_C_FLAGS -O2)
set(CMAKE_CXX_FLAGS -O2)
set(CMAKE_C_COMPILER /usr/bin/cc)
set(CMAKE_CXX_COMPILER /usr/bin/c++)
set(CMAKE_MAKE_PROGRAM \"${CMAKE_MAKE_PROGRAM}\" CACHE FILEPATH \"\" FORCE)
set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE NEVER)
")
set(GGML_VULKAN_SHADERS_GEN_TOOLCHAIN
    ${CMAKE_BINARY_DIR}/vk-host-toolchain.cmake CACHE FILEPATH "" FORCE)

# SPIRV-Headers (Homebrew: brew install spirv-headers) — required by
# find_package in ggml-vulkan but only soft-included via __has_include.
foreach(candidate
        /opt/homebrew/share/cmake/SPIRV-Headers
        /usr/local/share/cmake/SPIRV-Headers)
    if (EXISTS ${candidate}/SPIRV-HeadersConfig.cmake)
        set(SPIRV-Headers_DIR ${candidate} CACHE PATH "" FORCE)
        break()
    endif()
endforeach()

# Local patches to the vendored llama.cpp (idempotent: skipped when already
# applied). 0001: don't request the legacy VK_KHR_16bit_storage extension
# name unless the driver advertises it — gfxstream (Android emulator) has the
# core-1.1 feature but not the name, and createDevice fails otherwise.
file(GLOB _llama_patches ${CMAKE_CURRENT_SOURCE_DIR}/patches/*.patch)
foreach(_patch ${_llama_patches})
    execute_process(
        COMMAND git -C ${LLAMA_SRC} apply --reverse --check ${_patch}
        RESULT_VARIABLE _already_applied OUTPUT_QUIET ERROR_QUIET)
    if (NOT _already_applied EQUAL 0)
        execute_process(
            COMMAND git -C ${LLAMA_SRC} apply ${_patch}
            RESULT_VARIABLE _apply_result)
        if (NOT _apply_result EQUAL 0)
            message(FATAL_ERROR "Failed to apply ${_patch} to ${LLAMA_SRC}")
        endif()
    endif()
endforeach()

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

# Native YOLO detector: Ultralytics NCNN export running on ncnn (Vulkan
# compute with CPU/NEON fallback). Uses the prebuilt static ncnn (vendored
# here, gitignored — see README). simplevk: ncnn dlopens the Vulkan driver
# itself, so no NDK libvulkan is linked.
set(NCNN_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/ncnn)
set(ncnn_DIR ${NCNN_ROOT}/${ANDROID_ABI}/lib/cmake/ncnn)
if (NOT EXISTS ${ncnn_DIR}/ncnnConfig.cmake)
    message(FATAL_ERROR
        "ncnn prebuilt not found at ${NCNN_ROOT}/${ANDROID_ABI}\n"
        "Run: gh release download 20260526 -R Tencent/ncnn -p ncnn-20260526-android-vulkan.zip\n"
        "     unzip ncnn-20260526-android-vulkan.zip && mv ncnn-20260526-android-vulkan ${NCNN_ROOT}")
endif()
find_package(ncnn REQUIRED)

add_library(yolo_jni SHARED yolo_jni.cpp)
target_compile_features(yolo_jni PRIVATE cxx_std_17)
target_link_options(yolo_jni PRIVATE "-Wl,-z,max-page-size=16384")
# ncnn threads on the NDK's OpenMP; link it statically so no libomp.so needs
# packaging.
target_link_options(yolo_jni PRIVATE -fopenmp -static-openmp)
target_link_libraries(yolo_jni PRIVATE ncnn ${log_lib} jnigraphics android)
