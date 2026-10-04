// 邻键误触纠错 JNI：
//   1) 几何先验侧信道：Kotlin 把最近按键的 (字母, off_x, off_y) 送入插件
//   2) schema 补丁注入：把 correction_translator 注入目标方案（照 T9 的 custom.yaml 思路）
//   3) 信道模型加载：从 assets 解出的 channel.onnx 路径初始化
#include <jni.h>

#include <android/log.h>

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "correction_translator.h"

#define LOG_TAG "CorrectionJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

bool ReadFile(const std::string& path, std::string* out) {
  FILE* f = fopen(path.c_str(), "rb");
  if (!f)
    return false;
  fseek(f, 0, SEEK_END);
  long n = ftell(f);
  fseek(f, 0, SEEK_SET);
  if (n < 0) {
    fclose(f);
    return false;
  }
  out->resize(static_cast<size_t>(n));
  size_t got = fread(&(*out)[0], 1, static_cast<size_t>(n), f);
  fclose(f);
  out->resize(got);
  return true;
}

bool WriteFile(const std::string& path, const std::string& content) {
  FILE* f = fopen(path.c_str(), "wb");
  if (!f)
    return false;
  fwrite(content.c_str(), 1, content.size(), f);
  fclose(f);
  return true;
}

}  // namespace

extern "C" {

// 插件日志文件（插件内部直接写文件，不依赖 logcat / JNI 回调）
JNIEXPORT void JNICALL
Java_com_kingzcheung_xime_correction_CorrectionNative_nativeSetLogFile(JNIEnv* env, jclass,
                                                                      jstring j_path) {
  const char* p = j_path ? env->GetStringUTFChars(j_path, nullptr) : nullptr;
  correction_set_log_file(p);
  LOGI("plugin log file: %s", p ? p : "(null)");
  if (p) env->ReleaseStringUTFChars(j_path, p);
}

// ---- 1) 几何先验侧信道 ----
JNIEXPORT void JNICALL
Java_com_kingzcheung_xime_correction_CorrectionNative_nativeSetTaps(
    JNIEnv* env, jclass, jbyteArray letters, jfloatArray off_x, jfloatArray off_y) {
  if (!letters || !off_x || !off_y) {
    correction_clear_taps();
    return;
  }
  const jsize n = env->GetArrayLength(letters);
  if (n <= 0 || env->GetArrayLength(off_x) != n || env->GetArrayLength(off_y) != n) {
    correction_clear_taps();
    return;
  }
  jbyte* l = env->GetByteArrayElements(letters, nullptr);
  jfloat* ox = env->GetFloatArrayElements(off_x, nullptr);
  jfloat* oy = env->GetFloatArrayElements(off_y, nullptr);
  std::vector<char> ls(static_cast<size_t>(n));
  for (jsize i = 0; i < n; ++i)
    ls[i] = static_cast<char>(l[i]);
  correction_set_taps(ls.data(), ox, oy, n);
  env->ReleaseByteArrayElements(letters, l, JNI_ABORT);
  env->ReleaseFloatArrayElements(off_x, ox, JNI_ABORT);
  env->ReleaseFloatArrayElements(off_y, oy, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_com_kingzcheung_xime_correction_CorrectionNative_nativeClearTaps(JNIEnv*,
                                                                     jclass) {
  correction_clear_taps();
}

JNIEXPORT void JNICALL
Java_com_kingzcheung_xime_correction_CorrectionNative_nativeSetEnabled(JNIEnv*,
                                                                      jclass,
                                                                      jboolean on) {
  correction_set_enabled(on ? 1 : 0);
}

// 信道模型（channel.onnx）与码表（wubi_codes.bin）的文件路径
JNIEXPORT void JNICALL
Java_com_kingzcheung_xime_correction_CorrectionNative_nativeSetModelPaths(
    JNIEnv* env, jclass, jstring j_channel, jstring j_codes) {
  const char* c = j_channel ? env->GetStringUTFChars(j_channel, nullptr) : nullptr;
  const char* t = j_codes ? env->GetStringUTFChars(j_codes, nullptr) : nullptr;
  correction_set_model_paths(c, t);
  if (c) env->ReleaseStringUTFChars(j_channel, c);
  if (t) env->ReleaseStringUTFChars(j_codes, t);
  LOGI("model paths: channel=%s codes=%s", c ? c : "(null)", t ? t : "(null)");
}

// ---- 2) schema 补丁注入（幂等） ----
// 需要注入的组件与配置行；schema 或 custom.yaml 已含则跳过。
JNIEXPORT jboolean JNICALL
Java_com_kingzcheung_xime_correction_CorrectionNative_nativeEnsureCorrectedSchemaPatch(
    JNIEnv* env, jclass, jstring j_schema, jstring j_user_data_dir) {
  const char* schema_c = env->GetStringUTFChars(j_schema, nullptr);
  const char* dir_c = env->GetStringUTFChars(j_user_data_dir, nullptr);
  std::string schema(schema_c), dir(dir_c);
  env->ReleaseStringUTFChars(j_schema, schema_c);
  env->ReleaseStringUTFChars(j_user_data_dir, dir_c);

  const std::string schema_path = dir + "/" + schema + ".schema.yaml";
  std::string schema_content;
  if (!ReadFile(schema_path, &schema_content) || schema_content.empty()) {
    LOGI("schema file not found: %s", schema_path.c_str());
    return JNI_FALSE;
  }
  const std::string custom_path = dir + "/" + schema + ".custom.yaml";
  std::string custom;
  ReadFile(custom_path, &custom);

  // 目标方案由 Kotlin 侧按配置筛选（xime.yaml correction.schemas），
  // 这里只做幂等检查：schema 或 custom.yaml 已含 correction_translator 则跳过。
  if (schema_content.find("correction_translator") != std::string::npos ||
      custom.find("correction_translator") != std::string::npos) {
    return JNI_FALSE;  // 已注入
  }

  std::vector<std::string> patch_lines = {
      "  \"engine/translators/@before 0\": correction_translator",
      "  \"correction/enable\": true",
      "  \"correction/mode\": append",
      "  \"correction/lambda\": 1.0",
      "  \"correction/margin\": 2.0",
      "  \"correction/max_candidates\": 3",
      "  \"correction/min_code_length\": 2",
  };

  std::string base = custom;
  while (!base.empty() && (base.back() == '\n' || base.back() == '\r' || base.back() == ' '))
    base.pop_back();
  if (base.size() >= 3 && base.substr(base.size() - 3) == "...")
    base.resize(base.size() - 3);

  std::string patch_content;
  for (const auto& line : patch_lines)
    patch_content += line + "\n";

  std::string out;
  if (base.empty()) {
    out = "patch:\n" + patch_content;
  } else if (base.find("patch:") != std::string::npos) {
    out = base + "\n" + patch_content;
  } else {
    out = base + "\n\npatch:\n" + patch_content;
  }
  if (!WriteFile(custom_path, out)) {
    LOGE("failed to write %s", custom_path.c_str());
    return JNI_FALSE;
  }
  LOGI("injected correction_translator into %s", custom_path.c_str());
  return JNI_TRUE;
}

}  // extern "C"
