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

# ---- ggml: backends as loadable modules ----
# CPU: one module per ARM feature level (armv8.0 ... armv9.2: dotprod, fp16, i8mm, SVE2, SME);
#      the app loads the best one this phone supports (see vs_jni.cpp setup()).
# Vulkan (GPU, experimental): libggml-vulkan.so, only loaded when the user turns it on.
option(VS_VULKAN "build the Vulkan GPU backend" ON)
add_compile_definitions(GGML_MAX_NAME=128)
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_BLAS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
if(CMAKE_LIBRARY_OUTPUT_DIRECTORY)
    # put the backend modules next to libvoicestudio.so so that Gradle packages them
    set(CMAKE_RUNTIME_OUTPUT_DIRECTORY "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}")
endif()
if(VS_VULKAN)
    set(GGML_VULKAN ON CACHE BOOL "" FORCE)
    set(Vulkan_INCLUDE_DIR "${VS_THIRD_PARTY}/Vulkan-Headers/include" CACHE PATH "" FORCE)
    set(SPIRV-Headers_DIR "${VS_THIRD_PARTY}/spirv-install/share/cmake/SPIRV-Headers" CACHE PATH "" FORCE)
    if(ANDROID)
        find_library(VS_VULKAN_LIB vulkan REQUIRED)
        set(Vulkan_LIBRARY "${VS_VULKAN_LIB}" CACHE FILEPATH "" FORCE)
        find_program(VS_GLSLC glslc HINTS "${ANDROID_NDK}/shader-tools/linux-x86_64" NO_CMAKE_FIND_ROOT_PATH REQUIRED)
    else()
        find_program(VS_GLSLC glslc REQUIRED)
    endif()
    if(DEFINED ENV{VS_GLSLC})
        set(VS_GLSLC "$ENV{VS_GLSLC}")
    endif()
    set(Vulkan_GLSLC_EXECUTABLE "${VS_GLSLC}" CACHE FILEPATH "" FORCE)
    message(STATUS "glslc: ${VS_GLSLC}")
else()
    set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
endif()
add_subdirectory("${QWEN_DIR}/ggml" ggml)
if(VS_VULKAN AND TARGET ggml-vulkan)
    target_include_directories(ggml-vulkan SYSTEM PRIVATE "${VS_THIRD_PARTY}/SPIRV-Headers/include" "${VS_THIRD_PARTY}/Vulkan-Headers/include")
endif()

# ---- qwen core (same three files as upstream qwen-core) ----
file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/gen/version.h" "#pragma once\n#define QWEN_VERSION \"voicestudio\"\n")
add_library(qwen-core STATIC
    "${QWEN_DIR}/src/qwen.cpp"
    "${QWEN_DIR}/src/pipeline-tts.cpp"
    "${QWEN_DIR}/src/pipeline-codec.cpp")
target_compile_definitions(qwen-core PUBLIC QWEN_STATIC)
target_include_directories(qwen-core PUBLIC "${QWEN_DIR}/src" PRIVATE "${QWEN_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/gen")
target_include_directories(qwen-core SYSTEM PRIVATE "${QWEN_DIR}/ggml/include")
target_link_libraries(qwen-core PUBLIC ggml ggml-base)

# ---- shine MP3 encoder ----
file(GLOB SHINE_SRC "${SHINE_DIR}/*.c")
add_library(shine STATIC ${SHINE_SRC})
target_include_directories(shine PUBLIC "${SHINE_DIR}")
target_compile_options(shine PRIVATE -w)

# ---- JNI library ----
add_library(voicestudio SHARED vs_jni.cpp)
target_link_libraries(voicestudio PRIVATE qwen-core shine ${CMAKE_DL_LIBS})
target_include_directories(voicestudio SYSTEM PRIVATE "${QWEN_DIR}/ggml/include")
if(ANDROID)
    find_library(log-lib log)
    target_link_libraries(voicestudio PRIVATE ${log-lib})
else()
    find_package(JNI REQUIRED)
    target_include_directories(voicestudio PRIVATE ${JNI_INCLUDE_DIRS})
endif()
