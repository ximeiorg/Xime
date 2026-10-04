// 相邻键纠错几何模型 JNI 包装。
// 对应 Kotlin: com.kingzcheung.xime.correction.CorrectorNative
#include <jni.h>

#include <cstdint>
#include <vector>

#include "corrector.h"

extern "C" JNIEXPORT jlong JNICALL
Java_com_kingzcheung_xime_correction_CorrectorNative_nativeCreate(
    JNIEnv* env, jobject /*thiz*/, jbyteArray weights) {
  if (weights == nullptr) return 0;
  const jsize len = env->GetArrayLength(weights);
  jbyte* data = env->GetByteArrayElements(weights, nullptr);
  Corrector* c = corrector_create(data, static_cast<size_t>(len));
  env->ReleaseByteArrayElements(weights, data, JNI_ABORT);
  return reinterpret_cast<jlong>(c);
}

// 返回 [W*26] logits；失败返回空数组。
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_kingzcheung_xime_correction_CorrectorNative_nativeForward(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jfloatArray x, jint w) {
  Corrector* c = reinterpret_cast<Corrector*>(handle);
  if (c == nullptr || x == nullptr || w <= 0) return env->NewFloatArray(0);
  const int in_dim = corrector_in_dim(c);
  const int vocab = corrector_vocab(c);
  if (env->GetArrayLength(x) != static_cast<jsize>(w) * in_dim) {
    return env->NewFloatArray(0);
  }
  jfloat* xp = env->GetFloatArrayElements(x, nullptr);
  std::vector<float> out(static_cast<size_t>(w) * vocab);
  const int rc = corrector_forward(c, xp, w, out.data());
  env->ReleaseFloatArrayElements(x, xp, JNI_ABORT);
  if (rc != 0) return env->NewFloatArray(0);
  jfloatArray res = env->NewFloatArray(w * vocab);
  env->SetFloatArrayRegion(res, 0, w * vocab, out.data());
  return res;
}

// 对 [W*26] 按行原地 softmax。
extern "C" JNIEXPORT void JNICALL
Java_com_kingzcheung_xime_correction_CorrectorNative_nativeSoftmax(
    JNIEnv* env, jobject /*thiz*/, jfloatArray logits, jint w) {
  if (logits == nullptr || w <= 0) return;
  jfloat* p = env->GetFloatArrayElements(logits, nullptr);
  corrector_softmax_rows(p, w, 26);
  env->ReleaseFloatArrayElements(logits, p, 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_kingzcheung_xime_correction_CorrectorNative_nativeFree(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
  corrector_free(reinterpret_cast<Corrector*>(handle));
}
