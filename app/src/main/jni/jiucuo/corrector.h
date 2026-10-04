// 几何纠错模型（Linear -> ReLU -> BiLSTM(2层) -> Linear）的独立 C 前向实现。
// 零依赖、可用于 Android NDK / 桌面验证。权重从 PyTorch 导出的二进制载入。
#ifndef JIUCUO_CORRECTOR_H
#define JIUCUO_CORRECTOR_H

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

#define CORRECTOR_IN_DIM 30  // 4 几何 + 26 按下字母 one-hot
#define CORRECTOR_VOCAB 26   // a-z

typedef struct Corrector Corrector;

// 从内存缓冲解析权重（格式见 scripts/export_cpp.py / corrector.cpp），失败返回 NULL。
Corrector* corrector_create(const void* data, size_t size);
void corrector_free(Corrector* c);

// 前向：x 为 [W, in_dim]（行主序），out 为 [W, vocab] 的 logits。
// 返回 0 成功，非 0 失败。可在任意 W 上调用（LSTM 支持变长）。
int corrector_forward(const Corrector* c, const float* x, int W, float* out);

// 便捷：对 out 每行做 softmax（原地）。
void corrector_softmax_rows(float* out, int W, int vocab);

int corrector_in_dim(const Corrector* c);
int corrector_vocab(const Corrector* c);
int corrector_hidden(const Corrector* c);
int corrector_layers(const Corrector* c);

#ifdef __cplusplus
}
#endif

#endif  // JIUCUO_CORRECTOR_H
