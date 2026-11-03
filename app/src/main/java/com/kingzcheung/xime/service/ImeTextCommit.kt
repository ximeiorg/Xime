package com.kingzcheung.xime.service

import com.kingzcheung.xime.R

import com.kingzcheung.xime.i18n.tr

import com.kingzcheung.xime.util.FileLogger
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import com.kingzcheung.xime.clipboard.ClipboardItem
import com.kingzcheung.xime.clipboard.MediaBridge
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 文本上屏与剪贴板提交。
 *
 * 承载 commitImage（图片上屏）、剪贴板候选提交（selectClipboardItem/commitClipboardText/
 * deleteClipboardChars）与语音撤销/搜索动作（performUndo/performSearch）。
 * 共享状态通过 service 引用访问。
 */
internal class ImeTextCommit(private val service: XimeInputMethodService) {
    internal fun performUndo() {
        val currentTextBeforeCursor = service.currentInputConnection?.getTextBeforeCursor(1000, 0)?.toString() ?: ""
        val currentLength = currentTextBeforeCursor.length
        
        val charsToDelete = currentLength - service.voiceRecognitionHandler.textLengthBeforeVoiceInput
        
        if (charsToDelete > 0) {
            for (i in 0 until charsToDelete) {
                service.currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
                service.currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
            }
        }
        
        service.voiceRecognitionHandler.textBeforeVoiceInput = ""
        service.voiceRecognitionHandler.textLengthBeforeVoiceInput = 0
    }
    
    internal fun performSearch() {
        service.currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        service.currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
    }

    internal fun commitImage(imagePath: String, mimeType: String = "image/jpeg"): Boolean {
        return try {
            val imageFile = File(imagePath)
            if (!imageFile.exists()) {
                FileLogger.e(XimeInputMethodService.TAG, "Image file not found: $imagePath")
                return false
            }

            // 按扩展名修正真实 MIME 类型（PNG/GIF/WebP 表情不应声明为 image/jpeg）
            val actualMimeType = when (imageFile.extension.lowercase()) {
                "png" -> "image/png"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "jpg", "jpeg" -> "image/jpeg"
                else -> mimeType
            }

            // 宿主未声明支持图片 MIME 时 commitContent 必然失败，
            // 提前返回 false，由调用方降级为复制到剪贴板
            val supportedMimeTypes = service.currentInputEditorInfo?.contentMimeTypes
            if (!supportsMimeType(supportedMimeTypes, actualMimeType)) {
                Log.i(XimeInputMethodService.TAG, "Host does not support image commit (contentMimeTypes=${supportedMimeTypes?.contentToString()}), falling back to clipboard")
                return false
            }

            val cacheDir = File(service.cacheDir, "emoji_cache")
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }
            
            val cacheFile = File(cacheDir, imageFile.name)
            FileInputStream(imageFile).use { input ->
                cacheFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            
            val uri = getContentUriForImage(cacheFile, actualMimeType) ?: return false
            
            val inputContentInfo = InputContentInfo(
                uri,
                android.content.ClipDescription("emoji_image", arrayOf(actualMimeType)),
                null
            )
            
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION
            } else {
                0
            }
            
            service.currentInputConnection?.commitContent(inputContentInfo, flags, null) ?: false
            
        } catch (e: Exception) {
            FileLogger.e(XimeInputMethodService.TAG, "Failed to commit image", e)
            false
        }
    }

    /** 判断宿主声明的 contentMimeTypes 是否支持指定 MIME 类型（支持通配符匹配）。 */
    private fun supportsMimeType(declaredMimeTypes: Array<String>?, mimeType: String): Boolean {
        if (declaredMimeTypes.isNullOrEmpty()) return false
        return declaredMimeTypes.any { declared ->
            declared == "*/*" ||
                declared.equals(mimeType, ignoreCase = true) ||
                (declared.endsWith("/*") && mimeType.startsWith(declared.removeSuffix("/*"), ignoreCase = true))
        }
    }
    

    internal fun selectClipboardItem(text: String) {
        if (service.candidateState.value.isComposing) {
            service.keyRouter.postRimeJob {
                service.rimeEngine.clearComposition()
                withContext(Dispatchers.Main) {
                    service.updateUI()
                }
            }
        }
        // 标记为已消费：候选栏/剪贴板点选上屏后不再重复出现在候选栏
        service.clipboardManager.markConsumed(text)
        // 粘贴不计打字统计（不投 text_committed），联想照常
        service.commitPastedText(text)
        service.clipboardManager.copyToSystemClipboard(text)
    }

    /**
     * 快捷发送条目点选：只上屏，不写回系统剪贴板。
     *
     * 快捷发送是长期复用的短语，不属于剪贴板历史；写回系统剪贴板会被剪贴板监听
     * 采集为一条新历史，并重新出现在候选栏。
     */
    internal fun selectQuickSendItem(text: String) {
        if (service.candidateState.value.isComposing) {
            service.keyRouter.postRimeJob {
                service.rimeEngine.clearComposition()
                withContext(Dispatchers.Main) {
                    service.updateUI()
                }
            }
        }
        service.commitPastedText(text)
    }

    /**
     * 剪贴板**图片**条目点选。
     *
     * 两条路径（与 Emoji 图片发送一致，复用 [commitImage] 的 MIME 探测）：
     * 1. 宿主输入框声明支持图片 MIME → `commitContent` 直插（如部分笔记/邮件应用）；
     * 2. 否则（微信/Telegram 等）→ 写入系统剪贴板，提示用户长按输入框粘贴发送。
     *
     * 图片条目没有文本上屏，但同样标记 consumed（候选栏/列表不再提示"新内容"）。
     */
    internal fun selectClipboardImage(item: ClipboardItem) {
        service.clipboardManager.markConsumedById(item.id)
        val file = service.clipboardManager.imageFileOf(item)
        if (file == null) {
            FileLogger.w(XimeInputMethodService.TAG, "Clipboard image file missing: ${item.imagePath}")
            Toast.makeText(service, tr(R.string.s_tupianyibuzaibendi_wufafasong), Toast.LENGTH_SHORT).show()
            return
        }
        val mimeType = item.mimeType.ifEmpty { "image/jpeg" }
        if (commitImage(file.absolutePath, mimeType)) return

        val copied = service.clipboardManager.copyImageToSystemClipboard(
            imagePath = file.absolutePath,
            label = "xime_clipboard_image",
            // 直接共享 files/clipboard_images 原文件（配合淘汰保护集），
            // 避免再拷一份到 cache 造成重复占用
            shareCacheCopy = false,
        )
        Toast.makeText(
            service,
            if (copied) tr(R.string.s_yifuzhitupian_zhanganshurukuan_96610) else tr(R.string.s_fuzhitupianshibai),
            Toast.LENGTH_SHORT,
        ).show()
    }

    internal fun commitClipboardText(text: String) {
        service.commitPastedText(text)
    }

    internal fun deleteClipboardChars(count: Int) {
        service.noteSelfWriteCaretDelta(-count)
        service.currentInputConnection?.deleteSurroundingText(count, 0)
    }

    /** 生成图片 content URI（commitContent 用）：FileProvider 优先，抛错时降级 [MediaBridge] 中转（不写相册）。 */
    private fun getContentUriForImage(imageFile: File, mimeType: String): Uri? {
        try {
            return FileProvider.getUriForFile(
                service,
                "${service.packageName}.fileprovider",
                imageFile
            )
        } catch (e: IllegalArgumentException) {
            FileLogger.w(XimeInputMethodService.TAG, "FileProvider unavailable, falling back to MediaBridge", e)
        } catch (e: Exception) {
            FileLogger.w(XimeInputMethodService.TAG, "FileProvider getUriForFile failed, falling back to MediaBridge", e)
        }

        return MediaBridge.insert(service, imageFile, mimeType, autoCleanup = false)
    }
}
