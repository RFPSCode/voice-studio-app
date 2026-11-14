cmake_minimum_required(VERSION 3.22)
project(voicestudio LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Third-party sources are fetched by scripts/fetch_deps.sh (pinned commits)
set(VS_THIRD_PARTY "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party" CACHE PATH "third party sources")
set(QWEN_DIR  "${VS_THIRD_PARTY}/qwentts.cpp")
set(SHINE_DIR "${VS_THIRD_PARTY}/shine/src/lib")

if(NOT EXISTS "${QWEN_DIR}/src/qwen.cpp")
    message(FATAL_ERROR "qwentts.cpp not found in ${QWEN_DIR}. Run scripts/fetch_deps.sh first.")
endif()

# ---- ggml (CPU backend only, statically linked) ----
add_compile_definitions(GGML_MAX_NAME=128)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_BLAS OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
if(ANDROID AND ANDROID_ABI STREQUAL "arm64-v8a")
    # Dimensity 9300 / Snapdragon 8 Gen 2+ : ARMv8.2-A dotprod + fp16
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
endif()
add_subdirectory("${QWEN_DIR}/ggml" ggml)

# ---- qwen core (same three files as upstream qwen-core) ----
file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/gen/version.h" "#pragma once\n#define QWEN_VERSION \"voicestudio\"\n")
add_library(qwen-core STATIC
    "${QWEN_DIR}/src/qwen.cpp"
    "${QWEN_DIR}/src/pipeline-tts.cpp"
    "${QWEN_DIR}/src/pipeline-codec.cpp")
target_compile_definitions(qwen-core PUBLIC QWEN_STATIC)
target_include_directories(qwen-core PUBLIC "${QWEN_DIR}/src" PRIVATE "${QWEN_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/gen")
target_include_directories(qwen-core SYSTEM PRIVATE "${QWEN_DIR}/ggml/include")
target_link_libraries(qwen-core PUBLIC ggml ggml-base ggml-cpu)

# ---- shine MP3 encoder ----
file(GLOB SHINE_SRC "${SHINE_DIR}/*.c")
add_library(shine STATIC ${SHINE_SRC})
target_include_directories(shine PUBLIC "${SHINE_DIR}")
target_compile_options(shine PRIVATE -w)

# ---- JNI library ----
add_library(voicestudio SHARED vs_jni.cpp)
target_link_libraries(voicestudio PRIVATE qwen-core shine)
if(ANDROID)
    find_library(log-lib log)
    target_link_libraries(voicestudio PRIVATE ${log-lib})
else()
    find_package(JNI REQUIRED)
    target_include_directories(voicestudio PRIVATE ${JNI_INCLUDE_DIRS})
endif()
