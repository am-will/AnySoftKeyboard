cmake_minimum_required(VERSION 3.22.1)
project(keeb_whisper)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(WHISPER_LIB_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../third_party/whisper.cpp)

include(FetchContent)
FetchContent_Declare(ggml SOURCE_DIR ${WHISPER_LIB_DIR}/ggml)
FetchContent_MakeAvailable(ggml)

foreach(target_name ggml ggml-base ggml-cpu)
    if(TARGET ${target_name})
        target_compile_options(${target_name} PRIVATE -O3 -DNDEBUG)
    endif()
endforeach()

add_library(keeb_whisper_jni SHARED
    whisper_jni.cpp
    ${WHISPER_LIB_DIR}/src/whisper.cpp
)

target_compile_definitions(keeb_whisper_jni PUBLIC GGML_USE_CPU)
target_compile_definitions(keeb_whisper_jni PRIVATE WHISPER_VERSION="1.8.4")
target_compile_options(keeb_whisper_jni PRIVATE -O3 -DNDEBUG)
target_include_directories(keeb_whisper_jni PRIVATE
    ${WHISPER_LIB_DIR}
    ${WHISPER_LIB_DIR}/include
    ${WHISPER_LIB_DIR}/src
    ${WHISPER_LIB_DIR}/ggml/include
    ${WHISPER_LIB_DIR}/ggml/src
    ${WHISPER_LIB_DIR}/ggml/src/ggml-cpu
)

find_library(log-lib log)
target_link_libraries(keeb_whisper_jni ${log-lib} android ggml)
