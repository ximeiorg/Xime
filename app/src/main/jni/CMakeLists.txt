cmake_minimum_required(VERSION 3.18.0)

project(kime VERSION 1.0.0)

set(CMAKE_CXX_STANDARD 17)

# F-Droid 可复现构建：AGP 对 release 变体使用 RelWithDebInfo（-O2 -g -DNDEBUG），
# 其中的 -g 会把绝对源码路径嵌入 .so 的调试信息，不同构建环境路径不同
# 导致产物不一致。此处统一去掉 -g，使 native 产物与源码路径无关。
string(REPLACE "-g" "" CMAKE_C_FLAGS_RELWITHDEBINFO "${CMAKE_C_FLAGS_RELWITHDEBINFO}")
string(REPLACE "-g" "" CMAKE_CXX_FLAGS_RELWITHDEBINFO "${CMAKE_CXX_FLAGS_RELWITHDEBINFO}")

# __FILE__/__BASE_FILE__ 宏会把源码绝对路径嵌入产物（异常消息、日志等），
# 各构建环境绝对路径不同（/Users/...、/home/runner/...、/home/vagrant/...），
# 导致 .so 不一致。用 -ffile-prefix-map 把项目根前缀映射为空，
# 使产物中只含相对路径，与构建环境无关。对所有子项目（librime/boost/opencc 等）全局生效。
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -ffile-prefix-map=${CMAKE_SOURCE_DIR}=")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -ffile-prefix-map=${CMAKE_SOURCE_DIR}=")

# CMake 构建目录（app/.cxx/...）里生成的文件也会通过 __FILE__ 嵌入路径，
# 且该目录名含环境相关的 hash（如 59353x1w），必须一并映射掉。
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -ffile-prefix-map=${CMAKE_BINARY_DIR}=")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -ffile-prefix-map=${CMAKE_BINARY_DIR}=")

# NDK 头文件路径会嵌入 RTTI 类型名（anonymous struct 等），各环境 SDK 路径不同。
# AGP 通过缓存变量 ANDROID_NDK 提供 NDK 路径（勿用环境变量 ANDROID_NDK_HOME，构建时未必设置）。
if(ANDROID_NDK)
  set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -ffile-prefix-map=${ANDROID_NDK}=")
  set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -ffile-prefix-map=${ANDROID_NDK}=")
endif()

# Debug 构建也启用优化，解决大词典查询性能问题
if(CMAKE_BUILD_TYPE STREQUAL "Debug")
  set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O2 -g")
endif()

option(BUILD_SHARED_LIBS "" OFF)
option(BUILD_TESTING "" OFF)

if(ANDROID)
  set(CMAKE_THREAD_LIBS_INIT "" CACHE STRING "" FORCE)
endif()

set(CMAKE_MODULE_PATH "${CMAKE_CURRENT_SOURCE_DIR}/cmake" ${CMAKE_MODULE_PATH})

include(Boost)

option(WITH_GFLAGS "Use gflags" OFF)
option(WITH_GTEST "Use googletest" OFF)
set(WITH_UNWIND "none" CACHE STRING "Enable libunwind support (values: none, unwind, libunwind)")
add_subdirectory(librime/deps/glog)
target_compile_options(glog_internal PRIVATE "-ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=.")

set(Glog_FOUND TRUE CACHE BOOL "" FORCE)
set(Glog_INCLUDE_PATH "${CMAKE_CURRENT_SOURCE_DIR}/librime/deps/glog/src" CACHE PATH "" FORCE)
set(Glog_LIBRARY glog CACHE STRING "" FORCE)
set(Glog_STATIC TRUE CACHE BOOL "" FORCE)

option(YAML_CPP_BUILD_TOOLS "" OFF)
option(YAML_CPP_BUILD_TESTS "" OFF)
option(YAML_CPP_INSTALL "" OFF)
add_subdirectory(librime/deps/yaml-cpp)

set(YamlCpp_FOUND TRUE CACHE BOOL "" FORCE)
set(YamlCpp_INCLUDE_PATH "${CMAKE_CURRENT_SOURCE_DIR}/librime/deps/yaml-cpp/include" CACHE PATH "" FORCE)
set(YamlCpp_LIBRARY yaml-cpp CACHE STRING "" FORCE)
set(YamlCpp_STATIC TRUE CACHE BOOL "" FORCE)
set(YamlCpp_NEW_API TRUE CACHE BOOL "" FORCE)

# 配置 snappy (用于 leveldb 压缩)
option(SNAPPY_BUILD_TESTS "" OFF)
option(SNAPPY_BUILD_BENCHMARKS "" OFF)
option(SNAPPY_INSTALL "" OFF)
add_subdirectory(snappy)

set(HAVE_SNAPPY TRUE)
option(LEVELDB_BUILD_TESTS "" OFF)
option(LEVELDB_BUILD_BENCHMARKS "" OFF)
option(LEVELDB_INSTALL "" OFF)
add_subdirectory(librime/deps/leveldb)

set(LevelDb_FOUND TRUE CACHE BOOL "" FORCE)
set(LevelDb_INCLUDE_PATH "${CMAKE_CURRENT_SOURCE_DIR}/librime/deps/leveldb/include" CACHE PATH "" FORCE)
set(LevelDb_LIBRARY leveldb CACHE STRING "" FORCE)
set(LevelDb_STATIC TRUE CACHE BOOL "" FORCE)

set(ENABLE_TOOLS OFF CACHE BOOL "" FORCE)
add_subdirectory(librime/deps/marisa-trie)
target_compile_options(marisa PRIVATE "-ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=.")

set(Marisa_FOUND TRUE CACHE BOOL "" FORCE)
set(Marisa_INCLUDE_PATH "${CMAKE_CURRENT_SOURCE_DIR}/librime/deps/marisa-trie/include" CACHE PATH "" FORCE)
set(Marisa_LIBRARY marisa CACHE STRING "" FORCE)
set(Marisa_STATIC TRUE CACHE BOOL "" FORCE)
set(LIBMARISA marisa)
option(USE_SYSTEM_MARISA "" ON)

add_subdirectory(librime/deps/opencc)
include(OpenccWorkarounds)

set(Opencc_FOUND TRUE CACHE BOOL "" FORCE)
set(Opencc_INCLUDE_PATH "${CMAKE_CURRENT_SOURCE_DIR}/librime/deps/opencc/src" CACHE PATH "" FORCE)
set(Opencc_LIBRARY libopencc CACHE STRING "" FORCE)
set(Opencc_STATIC TRUE CACHE BOOL "" FORCE)

set(X11Keysym "${CMAKE_SOURCE_DIR}/librime/include")
include_directories("${X11Keysym}")

include_directories(
    "${CMAKE_BINARY_DIR}/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/algorithm/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/crc/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/dll/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/interprocess/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/range/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/regex/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/scope_exit/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/signals2/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/utility/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/uuid/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/assert/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/config/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/core/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/throw_exception/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/static_assert/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/mpl/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/preprocessor/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/vmd/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/type_traits/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/smart_ptr/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/move/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/container/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/container_hash/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/describe/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/intrusive/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/detail/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/integer/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/predef/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/io/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/lexical_cast/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/numeric/conversion/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/spirit/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/serialization/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/foreach/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/iterator/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/function/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/bind/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/tuple/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/optional/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/any/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/array/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/mp11/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/unordered/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/multi_index/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/variant/include"
    "${CMAKE_SOURCE_DIR}/boost/libs/concept_check/include"
)

include(Rime)

# 编译 JNI 库
add_subdirectory(librime_jni)

# ONNX Runtime JNI 库
set(ONNXRUNTIME_DIR "${CMAKE_SOURCE_DIR}/onnxruntime")

add_library(onnxruntime SHARED IMPORTED)
set_target_properties(onnxruntime PROPERTIES
    IMPORTED_LOCATION "${ONNXRUNTIME_DIR}/lib/${ANDROID_ABI}/libonnxruntime.so"
    INTERFACE_INCLUDE_DIRECTORIES "${ONNXRUNTIME_DIR}/include"
)

add_library(onnx_env SHARED
    onnx_env.cc
    onnx_env.h
)

target_include_directories(onnx_env PRIVATE
    ${ONNXRUNTIME_DIR}/include
)

target_link_libraries(onnx_env
    onnxruntime
    log
)

add_library(onnx_jni SHARED
    onnx_jni.cc
)

target_include_directories(onnx_jni PRIVATE
    ${ONNXRUNTIME_DIR}/include
)

target_link_libraries(onnx_jni
    onnx_env
    onnxruntime
    log
)

target_link_options(onnx_jni PRIVATE "-Wl,-z,max-page-size=16384")

# Handwriting ONNX JNI 库
add_library(handwriting_jni SHARED
    handwriting_jni.cc
)

target_include_directories(handwriting_jni PRIVATE
    ${ONNXRUNTIME_DIR}/include
)

target_link_libraries(handwriting_jni
    onnx_env
    onnxruntime
    log
)

target_link_options(handwriting_jni PRIVATE "-Wl,-z,max-page-size=16384")

# 离线语音识别（zipformer2）：已集成进主版本，始终编译
set(ASR_SRC_DIR "${CMAKE_SOURCE_DIR}")
set(KNF_ROOT "${ASR_SRC_DIR}/third_party/knf")
set(KNF_LOCAL_DIR "${KNF_ROOT}/kaldi-native-fbank-1.22.3")
set(FETCHCONTENT_SOURCE_DIR_KISSFFT "${KNF_ROOT}/kissfft")
add_subdirectory(${KNF_LOCAL_DIR} knf_build)

add_library(asr_jni SHARED
    ${ASR_SRC_DIR}/asr/asr_jni.cc
    ${ASR_SRC_DIR}/asr/zipformer2_model.cc
    ${ASR_SRC_DIR}/asr/feature_extractor.cc
    ${ASR_SRC_DIR}/asr/streaming_recognizer.cc
)
target_include_directories(asr_jni PRIVATE
    ${ONNXRUNTIME_DIR}/include
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${KNF_LOCAL_DIR}
)
target_link_libraries(asr_jni
    onnxruntime
    onnx_env
    kaldi-native-fbank-core
    log
)
target_link_options(asr_jni PRIVATE "-Wl,-z,max-page-size=16384")