package com.kingzcheung.xime.service

import android.content.Context
import android.util.Log
import com.kingzcheung.xime.association.AssociationManager
import com.kingzcheung.xime.association.AssociationService
import com.kingzcheung.xime.BuildConfig
import com.kingzcheung.xime.plugin.ExtensionManager
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.util.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PredictionManager(
    private val context: Context,
    private val serviceScope: CoroutineScope,
    private val onPredictionResult: (List<String>) -> Unit,
) {
    companion object {
        private const val TAG = "PredictionManager"
        private const val MAX_CONTEXT_LENGTH = 25
        const val MAX_ASSOCIATION_COUNT = 20

        /**
         * 联想上下文是否值得推理：最后一个字符必须是文字或数字。
         *
         * 空格、标点、表情本身不承载语义——若照常推理，ONNX 模型对 " "、"，" 这类
         * 输入同样会吐出一批候选，于是「什么都没输入，按个空格」或「刚打完一个句号」
         * 候选栏就凭空冒出一排与前文无关的联想词。这类上屏只清空候选栏，不触发推理。
         */
        internal fun isValidPredictionContext(text: String): Boolean {
            val last = text.lastOrNull() ?: return false
            return Character.isLetterOrDigit(last)
        }
    }
    
    private var _lastCommittedText = ""
    val lastCommittedText: String get() = _lastCommittedText

    /**
     * 预测请求代际号：退格/清空等改变上下文的操作调用 [invalidatePendingPredictions]，
     * 使所有在途的异步预测结果失效。否则长按退格删除时，旧的联想结果会迟到并反复
     * 回填 associationCandidates，候选栏在"显示联想词 ↔ 空"之间闪动（一闪一闪）。
     */
    private var requestEpoch = 0L

    fun invalidatePendingPredictions() {
        requestEpoch++
    }

    /**
     * 单次联想抑制标志：联想候选上屏（点击/空格）前置位，使 commitText 触发的下一轮
     * 自动推理被跳过并清空联想候选（候选栏干净），等待用户下一次真实输入。
     * 连续联想模式不置位——commitText 的自动推理即为"一直上屏一直推理"。
     */
    @Volatile
    private var suppressNextPrediction = false

    fun suppressNextPredictionOnce() {
        suppressNextPrediction = true
    }
    
    fun appendCommittedText(text: String) {
        _lastCommittedText = (_lastCommittedText + text).takeLast(MAX_CONTEXT_LENGTH)
    }
    
    fun clearCommittedText() {
        _lastCommittedText = ""
    }
    
    fun deleteLastChar() {
        if (_lastCommittedText.isNotEmpty()) {
            _lastCommittedText = _lastCommittedText.dropLast(1)
        }
    }
    
    fun initialize() {
        FileLogger.i(TAG, "Initializing association system")
        
        serviceScope.launch(Dispatchers.IO) {
            try {
                val trieInit = AssociationService.initialize(context)
                if (trieInit) {
                    FileLogger.i(TAG, "Trie association service initialized")
                } else {
                    FileLogger.w(TAG, "Trie association service initialization failed")
                }
                
                if (!ExtensionManager.isInitialized()) {
                    FileLogger.d(TAG, "ExtensionManager not initialized, initializing...")
                    ExtensionManager.initialize(context)
                }
                
                if (SettingsPreferences.isSmartPredictionEnabled(context)) {
                    try {
                        val initialized = AssociationManager.initialize(context)
                        if (initialized) {
                            FileLogger.i(TAG, "Smart prediction initialized")
                        } else {
                            FileLogger.w(TAG, "Smart prediction initialization failed")
                        }
                    } catch (e: Exception) {
                        FileLogger.e(TAG, "Failed to initialize smart prediction: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                FileLogger.e(TAG, "Failed to initialize association system: ${e.message}")
            }
        }
    }
    
    fun getPrediction(contextText: String) {
        // 单次联想：消费抑制标志——联想上屏引发的本轮推理不执行，回调空结果清空候选栏
        if (suppressNextPrediction) {
            suppressNextPrediction = false
            onPredictionResult(emptyList())
            return
        }

        if (!isValidPredictionContext(contextText)) {
            // 上下文为空，或以空白/标点/表情结尾（空按空格、刚上屏句号）：不推理，
            // 作废在途结果并立刻清空候选栏——否则旧的联想词会残留到下一次输入。
            invalidatePendingPredictions()
            onPredictionResult(emptyList())
            return
        }
        
        if (!SettingsPreferences.isSmartPredictionEnabled(context)) {
            onPredictionResult(emptyList())
            return
        }
        
        val epoch = requestEpoch
        serviceScope.launch {
            try {
                if (!AssociationManager.isInitialized()) {
                    val initSuccess = withContext(Dispatchers.IO) {
                        AssociationManager.initialize(context)
                    }
                    if (!initSuccess) {
                        Log.e(TAG, "Failed to initialize AssociationManager")
                        withContext(Dispatchers.Main) {
                            if (epoch == requestEpoch) {
                                onPredictionResult(emptyList())
                            }
                        }
                        return@launch
                    }
                }
                
                val candidates = AssociationManager.predict(contextText, MAX_ASSOCIATION_COUNT)

                withContext(Dispatchers.Main) {
                    // 代际过期说明上下文已被退格/清空修改，丢弃过期结果避免候选栏闪动
                    if (epoch == requestEpoch) {
                        onPredictionResult(candidates.map { it.text })
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Prediction failed", e)
                withContext(Dispatchers.Main) {
                    if (epoch == requestEpoch) {
                        onPredictionResult(emptyList())
                    }
                }
            }
        }
    }
    
    fun recordInput(text: String) {
        if (SettingsPreferences.isSmartPredictionEnabled(context) && AssociationManager.isInitialized()) {
            AssociationManager.recordInput(text)
        }
    }
    
    fun recordInputPair(lastChar: String, candidate: String) {
        if (SettingsPreferences.isSmartPredictionEnabled(context) && AssociationManager.isInitialized()) {
            AssociationManager.recordInput(lastChar + candidate)
        }
    }
    
    suspend fun getEnglishAssociations(text: String, limit: Int = MAX_ASSOCIATION_COUNT): List<String> {
        return try {
            AssociationService.getAssociations(context, text, true, limit)
        } catch (e: Exception) {
            Log.e(TAG, "English association failed", e)
            emptyList()
        }
    }
    
    suspend fun getChineseAssociations(text: String, limit: Int = MAX_ASSOCIATION_COUNT): List<String> {
        // 与自动推理同口径：空白/标点结尾不预测（供插件等外部调用方复用）
        if (!isValidPredictionContext(text)) return emptyList()
        return try {
            if (!AssociationManager.isInitialized()) {
                AssociationManager.initialize(context)
            }
            
            val candidates = AssociationManager.predict(text, limit)
            candidates.map { it.text }
        } catch (e: Exception) {
            Log.e(TAG, "Chinese association failed", e)
            emptyList()
        }
    }
}
