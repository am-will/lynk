cmake_minimum_required(VERSION 3.22.1)
project(gguf-runtime CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(LLAMA_CPP_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/llama.cpp")
set(LLAMA_CPP_BINARY_DIR "${CMAKE_CURRENT_BINARY_DIR}/llama.cpp")

set(GGML_VULKAN ON CACHE BOOL "Enable Vulkan backend" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_UI OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)

unset(ENV{VULKAN_SDK})

include(FetchContent)

if (DEFINED ENV{GGUF_VULKAN_HEADERS_DIR} AND IS_DIRECTORY "$ENV{GGUF_VULKAN_HEADERS_DIR}/include")
    set(vulkanheaders_SOURCE_DIR "$ENV{GGUF_VULKAN_HEADERS_DIR}")
    message(STATUS "Using local Vulkan-Headers: ${vulkanheaders_SOURCE_DIR}")
else()
    FetchContent_Declare(vulkanheaders
        GIT_REPOSITORY https://github.com/KhronosGroup/Vulkan-Headers.git
        GIT_TAG v1.3.296
        GIT_SHALLOW TRUE
    )
    FetchContent_MakeAvailable(vulkanheaders)
endif()

if (DEFINED ENV{GGUF_SPIRV_HEADERS_DIR} AND IS_DIRECTORY "$ENV{GGUF_SPIRV_HEADERS_DIR}/include")
    set(spirvheaders_SOURCE_DIR "$ENV{GGUF_SPIRV_HEADERS_DIR}")
    message(STATUS "Using local SPIRV-Headers: ${spirvheaders_SOURCE_DIR}")
else()
    FetchContent_Declare(spirvheaders
        GIT_REPOSITORY https://github.com/KhronosGroup/SPIRV-Headers.git
        GIT_TAG vulkan-sdk-1.3.296.0
        GIT_SHALLOW TRUE
    )
    FetchContent_MakeAvailable(spirvheaders)
endif()

set(SPIRV_HEADERS_CONFIG_DIR "${CMAKE_CURRENT_BINARY_DIR}/spirv-headers-config")
file(MAKE_DIRECTORY ${SPIRV_HEADERS_CONFIG_DIR})
set(SPIRV_HEADERS_SOURCE_DIR "${spirvheaders_SOURCE_DIR}")
configure_file(
    "${CMAKE_CURRENT_SOURCE_DIR}/spirv-headers-config/SPIRV-HeadersConfig.cmake.in"
    "${SPIRV_HEADERS_CONFIG_DIR}/SPIRV-HeadersConfig.cmake"
    @ONLY
)
set(SPIRV-Headers_DIR "${SPIRV_HEADERS_CONFIG_DIR}" CACHE PATH "" FORCE)
list(APPEND CMAKE_PREFIX_PATH "${SPIRV_HEADERS_CONFIG_DIR}")
set(CMAKE_PREFIX_PATH "${CMAKE_PREFIX_PATH}" CACHE PATH "" FORCE)

if(NOT CMAKE_MAKE_PROGRAM)
    get_filename_component(CMAKE_BIN_DIR "${CMAKE_COMMAND}" DIRECTORY)
    find_program(FALLBACK_NINJA ninja PATHS "${CMAKE_BIN_DIR}" NO_DEFAULT_PATH)
    if(FALLBACK_NINJA)
        set(CMAKE_MAKE_PROGRAM "${FALLBACK_NINJA}")
    endif()
endif()

set(GGML_VULKAN_SHADERS_GEN_TOOLCHAIN "${CMAKE_CURRENT_BINARY_DIR}/host-toolchain.cmake" CACHE FILEPATH "" FORCE)
configure_file(
    "${CMAKE_CURRENT_SOURCE_DIR}/host-toolchain.cmake.in"
    "${GGML_VULKAN_SHADERS_GEN_TOOLCHAIN}"
    @ONLY
)

add_subdirectory(${LLAMA_CPP_ROOT} ${LLAMA_CPP_BINARY_DIR} EXCLUDE_FROM_ALL)

find_library(ANDROID_LOG log)

add_library(gguf-runtime SHARED gguf_runtime.cpp)

target_include_directories(gguf-runtime PRIVATE
    ${LLAMA_CPP_ROOT}/include
    ${LLAMA_CPP_ROOT}/ggml/include
)

target_link_libraries(gguf-runtime PRIVATE llama ggml)

if (ANDROID_LOG)
    target_link_libraries(gguf-runtime PRIVATE ${ANDROID_LOG})
endif()

if (GGML_VULKAN)
    target_compile_definitions(gguf-runtime PRIVATE GGML_USE_VULKAN)
    if (TARGET ggml-vulkan)
        target_include_directories(ggml-vulkan BEFORE PRIVATE
            ${vulkanheaders_SOURCE_DIR}/include
            ${spirvheaders_SOURCE_DIR}/include
        )
    endif()
endif()
