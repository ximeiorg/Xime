#include "correction_channel.h"

#include <android/log.h>
#include <dlfcn.h>
#include <onnxruntime_c_api.h>

#include <cstring>

#define LOG_TAG "CorrectionChannel"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// 本插件不链接 onnxruntime / onnx_env：
//  · 编译期只依赖 onnxruntime_c_api.h（声明），不引入链接符号，这样插件能安全地
//    合并进 librime.a，不会让 build_predict / build_grammar 等独立工具因缺 onnx 符号而链接失败；
//  · 运行时 dlopen 打包在 APK 里的 libonnxruntime.so 解析 OrtGetApiBase。
//    注意：libonnxruntime.so 平时未必已在进程内（ASR 未启用时没人加载它），
//    且 dlsym(RTLD_DEFAULT, ...) 找不到符号会返回 null —— **必须判空后再调用**，
//    否则就是空函数指针调用（曾因此 SIGSEGV(0x0) 崩掉整个输入法进程）。
// OnnxGetSharedEnv 若进程内可用（libonnx_env.so 已加载）则复用共享 env，省一个 env 与线程池。
namespace rime {
namespace correction {
namespace {

constexpr const char* kInputName = "taps";
constexpr const char* kOutputName = "probs";

constexpr int kOrtMemTypeDefault = 0;  // OrtMemTypeDefault

const OrtApi* g_api = nullptr;
OrtEnv* g_env = nullptr;
bool g_env_tried = false;

const OrtApi* GetApi() {
  if (g_api)
    return g_api;
  void* h = dlopen("libonnxruntime.so", RTLD_NOW | RTLD_GLOBAL);
  if (!h) {
    LOGE("dlopen libonnxruntime.so failed: %s", dlerror());
    return nullptr;
  }
  void* sym = dlsym(h, "OrtGetApiBase");
  if (!sym) {
    LOGE("OrtGetApiBase not found in libonnxruntime.so");
    return nullptr;
  }
  const OrtApiBase* base = reinterpret_cast<const OrtApiBase* (*)()>(sym)();
  if (!base) {
    LOGE("OrtGetApiBase() returned null");
    return nullptr;
  }
  g_api = base->GetApi(ORT_API_VERSION);
  if (!g_api)
    LOGE("GetApi(%d) returned null (runtime too old?)", ORT_API_VERSION);
  return g_api;
}

OrtEnv* GetEnv() {
  if (g_env || g_env_tried)
    return g_env;
  g_env_tried = true;
  const OrtApi* api = GetApi();
  if (!api)
    return nullptr;
  // 优先复用项目共享 env（onnx_env.cc；未加载时 dlsym 返回 null，自行创建）
  void* shared_sym = dlsym(RTLD_DEFAULT, "OnnxGetSharedEnv");
  if (shared_sym) {
    g_env = reinterpret_cast<OrtEnv* (*)()>(shared_sym)();
    if (g_env)
      return g_env;
  }
  OrtStatus* status = api->CreateEnv(ORT_LOGGING_LEVEL_WARNING, "xime_correction", &g_env);
  if (status) {
    LOGE("CreateEnv failed: %s", api->GetErrorMessage(status));
    api->ReleaseStatus(status);
    g_env = nullptr;
  }
  return g_env;
}

}  // namespace

ChannelModel& ChannelModel::Instance() {
  static ChannelModel model;
  return model;
}

bool ChannelModel::Load(const std::string& model_path) {
  std::lock_guard<std::mutex> lock(mutex_);
  if (session_)
    return true;
  const OrtApi* api = GetApi();
  if (!api) {
    LOGE("onnxruntime API unavailable");
    return false;
  }
  OrtEnv* env = GetEnv();
  if (!env) {
    LOGE("onnx env unavailable");
    return false;
  }
  OrtSessionOptions* opts = nullptr;
  OrtStatus* status = api->CreateSessionOptions(&opts);
  if (status) {
    LOGE("CreateSessionOptions failed: %s", api->GetErrorMessage(status));
    api->ReleaseStatus(status);
    return false;
  }
  api->SetIntraOpNumThreads(opts, 1);
  api->SetSessionGraphOptimizationLevel(opts, ORT_ENABLE_ALL);
  api->DisableCpuMemArena(opts);
  OrtSession* session = nullptr;
  status = api->CreateSession(env, model_path.c_str(), opts, &session);
  api->ReleaseSessionOptions(opts);
  if (status) {
    LOGE("CreateSession('%s') failed: %s", model_path.c_str(), api->GetErrorMessage(status));
    api->ReleaseStatus(status);
    return false;
  }
  session_ = session;
  LOGI("channel model loaded: %s", model_path.c_str());
  return true;
}

bool ChannelModel::Predict(const float* taps, int n, float* probs) {
  std::lock_guard<std::mutex> lock(mutex_);
  if (!session_ || !taps || !probs || n <= 0)
    return false;
  const OrtApi* api = GetApi();
  if (!api)
    return false;

  OrtMemoryInfo* mem = nullptr;
  OrtStatus* status = api->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &mem);
  if (status) {
    LOGE("CreateCpuMemoryInfo failed: %s", api->GetErrorMessage(status));
    api->ReleaseStatus(status);
    return false;
  }
  const int64_t shape[2] = {n, kFeatDim};
  OrtValue* input = nullptr;
  status = api->CreateTensorWithDataAsOrtValue(mem, const_cast<float*>(taps),
                                               sizeof(float) * kFeatDim * n, shape, 2,
                                               ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &input);
  api->ReleaseMemoryInfo(mem);
  if (status) {
    LOGE("create input tensor failed: %s", api->GetErrorMessage(status));
    api->ReleaseStatus(status);
    return false;
  }
  const char* in_names[] = {kInputName};
  const char* out_names[] = {kOutputName};
  OrtValue* output = nullptr;
  status = api->Run(static_cast<OrtSession*>(session_), nullptr, in_names, &input, 1, out_names, 1,
                    &output);
  api->ReleaseValue(input);
  if (status) {
    LOGE("Run failed: %s", api->GetErrorMessage(status));
    api->ReleaseStatus(status);
    return false;
  }
  float* data = nullptr;
  status = api->GetTensorMutableData(output, reinterpret_cast<void**>(&data));
  if (status) {
    LOGE("GetTensorMutableData failed: %s", api->GetErrorMessage(status));
    api->ReleaseStatus(status);
    api->ReleaseValue(output);
    return false;
  }
  std::memcpy(probs, data, sizeof(float) * kVocab * n);
  api->ReleaseValue(output);
  return true;
}

}  // namespace correction
}  // namespace rime
