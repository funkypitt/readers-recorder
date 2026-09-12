cmake_minimum_required(VERSION 3.22)
project(readersrecorder_whisper C CXX)
set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

# whisper.cpp 1.9.4 and ggml's CPU backend, vendored in ./whisper.cpp (MIT), compiled
# straight into one shared library per CPU flavour — ggml's own CMake cannot be
# instantiated twice, and on arm64 we want two builds: a baseline one, and one with fp16
# arithmetic that the loader picks when the CPU has it.
set(W ${CMAKE_SOURCE_DIR}/whisper.cpp)
set(GGML_VERSION "0.9.7")
set(GGML_BUILD_COMMIT "vendored")
configure_file(${W}/ggml/src/ggml-version.h.in ${CMAKE_BINARY_DIR}/gen/ggml-version.h @ONLY)

set(GGML_SOURCES
    ${W}/ggml/src/ggml.c ${W}/ggml/src/ggml.cpp ${W}/ggml/src/ggml-alloc.c ${W}/ggml/src/ggml-backend.cpp
    ${W}/ggml/src/ggml-backend-meta.cpp ${W}/ggml/src/ggml-opt.cpp ${W}/ggml/src/ggml-threading.cpp
    ${W}/ggml/src/ggml-quants.c ${W}/ggml/src/gguf.cpp ${W}/ggml/src/ggml-backend-dl.cpp ${W}/ggml/src/ggml-backend-reg.cpp
    ${W}/ggml/src/ggml-cpu/ggml-cpu.c ${W}/ggml/src/ggml-cpu/ggml-cpu.cpp ${W}/ggml/src/ggml-cpu/repack.cpp
    ${W}/ggml/src/ggml-cpu/iqp.cpp ${W}/ggml/src/ggml-cpu/hbm.cpp ${W}/ggml/src/ggml-cpu/quants.c ${W}/ggml/src/ggml-cpu/traits.cpp
    ${W}/ggml/src/ggml-cpu/amx/amx.cpp ${W}/ggml/src/ggml-cpu/amx/mmq.cpp
    ${W}/ggml/src/ggml-cpu/binary-ops.cpp ${W}/ggml/src/ggml-cpu/unary-ops.cpp ${W}/ggml/src/ggml-cpu/vec.cpp ${W}/ggml/src/ggml-cpu/ops.cpp
    ${W}/ggml/src/ggml-cpu/llamafile/sgemm.cpp)
if (ANDROID_ABI STREQUAL "arm64-v8a")
    list(APPEND GGML_SOURCES ${W}/ggml/src/ggml-cpu/arch/arm/quants.c ${W}/ggml/src/ggml-cpu/arch/arm/repack.cpp)
elseif (ANDROID_ABI STREQUAL "x86_64")
    list(APPEND GGML_SOURCES ${W}/ggml/src/ggml-cpu/arch/x86/quants.c ${W}/ggml/src/ggml-cpu/arch/x86/repack.cpp)
endif ()

find_library(LOG_LIB log)

function(build_variant name)
    add_library(${name} SHARED ${GGML_SOURCES} ${W}/src/whisper.cpp ${CMAKE_SOURCE_DIR}/jni.c)
    target_include_directories(${name} PRIVATE ${CMAKE_BINARY_DIR}/gen ${W}/include ${W}/src ${W}/ggml/include ${W}/ggml/src ${W}/ggml/src/ggml-cpu)
    target_compile_definitions(${name} PRIVATE GGML_USE_CPU GGML_USE_LLAMAFILE _GNU_SOURCE WHISPER_VERSION="1.9.4")
    target_compile_options(${name} PRIVATE -O3 -fvisibility=hidden -fvisibility-inlines-hidden -ffunction-sections -fdata-sections -Wno-unused-function -Wno-unused-parameter ${ARGN})
    target_link_options(${name} PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
    target_link_libraries(${name} ${LOG_LIB} android)
endfunction()

if (ANDROID_ABI STREQUAL "arm64-v8a")
    build_variant(whisper_v8fp16_va -march=armv8.2-a+fp16)
    build_variant(whisper -march=armv8-a)
elseif (ANDROID_ABI STREQUAL "x86_64")
    build_variant(whisper -mavx2 -mfma -mf16c -msse4.2)
else ()
    build_variant(whisper)
endif ()

# ── llama.cpp (MIT), vendored in ./llama.cpp: the summary, written on the phone ──────────────
# Its ggml is a different version from whisper's, and both end up in the same app. They can
# only coexist because each library hides everything but its JNI entry points, so nothing
# resolves across the two. Their generated version headers also collide by name, hence the
# separate gen_llama directory, included by these targets only.
set(L ${CMAKE_SOURCE_DIR}/llama.cpp)
set(LLAMA_VERSION "0.4.0")
set(LLAMA_BUILD_COMMIT "e71b805")
set(GGML_VERSION "0.23.0")   # llama.cpp carries its own, newer ggml; whisper's header is already generated
configure_file(${L}/ggml/src/ggml-version.h.in ${CMAKE_BINARY_DIR}/gen_llama/ggml-version.h @ONLY)
configure_file(${L}/src/llama-version.h.in     ${CMAKE_BINARY_DIR}/gen_llama/llama-version.h @ONLY)

file(GLOB LLAMA_GGML ${L}/ggml/src/*.c ${L}/ggml/src/*.cpp)
file(GLOB LLAMA_GGML_CPU ${L}/ggml/src/ggml-cpu/*.c ${L}/ggml/src/ggml-cpu/*.cpp
                         ${L}/ggml/src/ggml-cpu/amx/*.cpp ${L}/ggml/src/ggml-cpu/llamafile/*.cpp)
file(GLOB LLAMA_SRC ${L}/src/*.cpp ${L}/src/models/*.cpp)
if (ANDROID_ABI STREQUAL "arm64-v8a")
    file(GLOB LLAMA_ARCH ${L}/ggml/src/ggml-cpu/arch/arm/*.c ${L}/ggml/src/ggml-cpu/arch/arm/*.cpp)
elseif (ANDROID_ABI STREQUAL "x86_64")
    file(GLOB LLAMA_ARCH ${L}/ggml/src/ggml-cpu/arch/x86/*.c ${L}/ggml/src/ggml-cpu/arch/x86/*.cpp)
endif ()

function(build_llama name)
    add_library(${name} SHARED ${LLAMA_GGML} ${LLAMA_GGML_CPU} ${LLAMA_ARCH} ${LLAMA_SRC}
                               ${CMAKE_SOURCE_DIR}/llama_jni.c)
    target_include_directories(${name} PRIVATE ${CMAKE_BINARY_DIR}/gen_llama ${L}/include ${L}/src
        ${L}/ggml/include ${L}/ggml/src ${L}/ggml/src/ggml-cpu)
    target_compile_definitions(${name} PRIVATE GGML_USE_CPU GGML_USE_LLAMAFILE _GNU_SOURCE)
    target_compile_options(${name} PRIVATE -O3 -fvisibility=hidden -fvisibility-inlines-hidden
        -ffunction-sections -fdata-sections -Wno-unused-function -Wno-unused-parameter ${ARGN})
    target_link_options(${name} PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
    target_link_libraries(${name} ${LOG_LIB} android)
endfunction()

if (ANDROID_ABI STREQUAL "arm64-v8a")
    build_llama(llama_v8fp16_va -march=armv8.2-a+fp16)
    build_llama(llama -march=armv8-a)
elseif (ANDROID_ABI STREQUAL "x86_64")
    build_llama(llama -mavx2 -mfma -mf16c -msse4.2)
else ()
    build_llama(llama)
endif ()
