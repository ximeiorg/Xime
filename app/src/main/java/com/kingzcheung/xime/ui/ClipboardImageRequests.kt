package com.kingzcheung.xime.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil.request.ImageRequest
import coil.size.Precision
import java.io.File

/**
 * 剪贴板图片的**解码尺寸上限**（px，方框）。
 *
 * 为什么必须显式给上限：候选栏芯片只有 28dp、面板卡片约 165×62dp，而剪贴板原图上限是
 * 5MB / 4096px（见 [com.kingzcheung.xime.clipboard.ClipboardImageStore.MAX_IMAGE_BYTES]）。
 * Coil 的 `AsyncImage` 默认走 [coil.compose.ConstraintsSizeResolver]——按控件像素尺寸做
 * `inSampleSize` 降采样，正常情况下**不会**整图解码；但只要某个使用面的父容器给了无界约束
 * （wrap_content、未定尺寸的弹窗、后续有人改布局），Coil 就会退回按原图尺寸解码：
 * 4096×4096 的 ARGB_8888 ≈ 64MB，输入法进程必崩。
 *
 * 因此每个使用面都显式声明上限，把最坏内存钉死在「上限方框 × 4B」量级，与布局怎么写无关。
 * 精度用 [Precision.INEXACT]：解码结果只会 ≤ 请求框的 2 倍幂（不放大、不整图）。
 */
const val CLIPBOARD_CARD_MAX_PX = 512

/** 候选栏图片芯片（约 28dp 圆形缩略图）。 */
const val CLIPBOARD_CHIP_MAX_PX = 128

/** 长按浮层里的图片预览（卡片约 200dp 宽，留足 3x 屏清晰度，仍远小于原图上限）。 */
const val CLIPBOARD_PREVIEW_MAX_PX = 1024

/**
 * 构造剪贴板图片请求：[maxWidthPx]×[maxHeightPx] 为解码上限方框。
 *
 * 传入屏幕尺寸即"按屏幕解码"（全屏预览用，保证放大后仍清晰，同时仍有界）；
 * 传入 [CLIPBOARD_CARD_MAX_PX] / [CLIPBOARD_CHIP_MAX_PX] 即小图缩略图。
 */
@Composable
fun rememberClipboardImageRequest(
    file: File,
    maxWidthPx: Int,
    maxHeightPx: Int = maxWidthPx,
): ImageRequest {
    val context = LocalContext.current
    return remember(file, maxWidthPx, maxHeightPx) {
        ImageRequest.Builder(context)
            .data(file)
            .size(maxWidthPx, maxHeightPx)
            .precision(Precision.INEXACT)
            .build()
    }
}