// 按键信道推理：p_geo(意图字母 | 按下键, 落点偏移)。
//
// 模型：TSI(Google UIST'24, 43,735 真实 tap) 训练的 MLP(28-64-64-26) + 温度标定，
// 已导出为 ONNX（assets/corrector/channel.onnx，2 KB）；复用项目共享 onnxruntime
// 环境（jni/onnx_env.cc），CPU 单线程即可（单次推理微秒级）。
//
// 输入 28 维：[off_x, off_y, one-hot(按下字母)]
//   off_x = (触点x - 按下键心x) / 键宽，off_y 同理
// 输出 26 维概率（已校准）。
#ifndef RIME_CORRECTION_CHANNEL_H_
#define RIME_CORRECTION_CHANNEL_H_

#include <array>
#include <mutex>
#include <string>
#include <vector>

namespace rime {
namespace correction {

class ChannelModel {
 public:
  static ChannelModel& Instance();

  bool Load(const std::string& model_path);

  // taps：每键 28 维特征（见上）。probs 输出 [n][26]，由调用方预分配。
  bool Predict(const float* taps, int n, float* probs);

  bool loaded() const { return session_ != nullptr; }

  static constexpr int kFeatDim = 28;
  static constexpr int kVocab = 26;

 private:
  ChannelModel() = default;
  ~ChannelModel() = default;
  ChannelModel(const ChannelModel&) = delete;
  ChannelModel& operator=(const ChannelModel&) = delete;

  void* session_ = nullptr;  // OrtSession*（经 dlsym 取得的 C API 操作）
  mutable std::mutex mutex_;
};

}  // namespace correction
}  // namespace rime

#endif  // RIME_CORRECTION_CHANNEL_H_
