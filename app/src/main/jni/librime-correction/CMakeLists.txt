cmake_minimum_required(VERSION 3.18.0)

# 邻键误触纠错插件（librime translator + ONNX 信道）
file(GLOB RIME_CORRECTION_SRC CONFIGURE_DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/src/*.cc)

add_library(rime-correction-objs OBJECT ${RIME_CORRECTION_SRC})
if(BUILD_SHARED_LIBS)
  set_target_properties(rime-correction-objs PROPERTIES POSITION_INDEPENDENT_CODE ON)
endif()

# 需要：librime 头（translator_commons/candidate/config 等）+ 项目共享的 onnxruntime 头
target_include_directories(rime-correction-objs PRIVATE
  ${CMAKE_SOURCE_DIR}/librime/src
  ${CMAKE_BINARY_DIR}/librime/src
  ${CMAKE_SOURCE_DIR}/onnxruntime/include
  ${CMAKE_SOURCE_DIR}
)

set(plugin_name "rime-correction" PARENT_SCOPE)
set(plugin_objs $<TARGET_OBJECTS:rime-correction-objs> PARENT_SCOPE)
# 插件只用 onnxruntime 的头 + OnnxGetApi 符号；符号在最终链接（rime_jni 链接
# onnx_env/onnxruntime）时解析，故这里不引入 IMPORTED 目标（子目录不可见）。
set(plugin_deps ${rime_library} PARENT_SCOPE)
set(plugin_modules "correction" PARENT_SCOPE)
