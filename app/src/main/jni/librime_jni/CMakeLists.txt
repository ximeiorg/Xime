cmake_minimum_required(VERSION 3.18.0)

# librime JNI 库
add_library(rime_jni SHARED
    rime_jni.cc
    correction_jni.cc
)

# 日志开关：Debug 构建保留 verbose 日志（支持运行时切换），
# Release 构建编译期消除，避免高频日志的格式化和 logcat IPC 开销。
if(CMAKE_BUILD_TYPE STREQUAL "Debug")
  target_compile_definitions(rime_jni PRIVATE RIME_JNI_VERBOSE_LOGGING=1)
else()
  target_compile_definitions(rime_jni PRIVATE RIME_JNI_VERBOSE_LOGGING=0)
endif()

# 包含 librime 头文件
target_include_directories(rime_jni PRIVATE
    ${CMAKE_SOURCE_DIR}/librime/include
    ${CMAKE_SOURCE_DIR}/librime/src
    ${CMAKE_BINARY_DIR}/librime/src
    ${CMAKE_SOURCE_DIR}/librime-t9/src
    ${CMAKE_SOURCE_DIR}/librime-correction/src
)

# 链接 librime 和其他依赖库
target_link_libraries(rime_jni
    -Wl,--whole-archive
    rime-static
    -Wl,--no-whole-archive
    log
)

# 支持 16 KB 页面大小对齐 (Android 15+ 要求)
target_link_options(rime_jni PRIVATE "-Wl,-z,max-page-size=16384")