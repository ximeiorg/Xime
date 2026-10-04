package com.kingzcheung.xime.viewmodel

import android.app.Application
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.kingzcheung.xime.util.FileLogger
import java.io.File

data class LogViewerUiState(
    val logFiles: List<File> = emptyList(),
    val selectedLogFile: File? = null,
    val logContent: String = "",
    val isLoading: Boolean = false,
    val errorMsg: String? = null
)

sealed class LogViewerEvent {
    data class ShareFile(val uri: Uri, val subject: String, val mimeType: String) : LogViewerEvent()
    data class SavedToDownloads(val path: String) : LogViewerEvent()
    data class ShowError(val message: String) : LogViewerEvent()
}

class LogViewerViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val TAG = "LogViewerViewModel"
    
    private val _uiState = MutableStateFlow(LogViewerUiState())
    val uiState: StateFlow<LogViewerUiState> = _uiState.asStateFlow()
    
    private val _events = MutableSharedFlow<LogViewerEvent>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<LogViewerEvent> = _events.asSharedFlow()
    
    init {
        loadLogFiles()
    }
    
    fun loadLogFiles() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMsg = null) }
            
            try {
                val logsDir = File(context.filesDir, "logs")
                if (!logsDir.exists()) {
                    logsDir.mkdirs()
                    Log.i(TAG, "Created logs directory: ${logsDir.absolutePath}")
                }
                
                val allFiles = withContext(Dispatchers.IO) {
                    logsDir.listFiles()?.toList() ?: emptyList()
                }
                
                val logFiles = allFiles
                    // .jsonl：按键坐标日志（key_tap.jsonl）等结构化采集文件，复用日志查看/导出
                    .filter { it.isFile && (it.name.endsWith(".log") || it.name.endsWith(".jsonl")) }
                    .sortedByDescending { it.lastModified() }
                
                _uiState.update { it.copy(
                    logFiles = logFiles,
                    isLoading = false
                )}
            } catch (e: Exception) {
                Log.e(TAG, "Error loading log files", e)
                _uiState.update { it.copy(
                    isLoading = false,
                    errorMsg = e.message
                )}
            }
        }
    }
    
    fun goBackToList() {
        _uiState.update { it.copy(selectedLogFile = null, logContent = "", errorMsg = null) }
    }
    
    fun selectLogFile(file: File) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, selectedLogFile = file) }
            
            try {
                val content = withContext(Dispatchers.IO) {
                    file.readText()
                }
                
                _uiState.update { it.copy(
                    logContent = content,
                    isLoading = false
                )}
            } catch (e: Exception) {
                Log.e(TAG, "Error reading log file", e)
                _uiState.update { it.copy(
                    isLoading = false,
                    errorMsg = "读取日志失败: ${e.message}"
                )}
            }
        }
    }
    
    fun deleteLogFile(file: File) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                file.delete()
                if (FileLogger.getCurrentLogFile()?.absolutePath == file.absolutePath) {
                    FileLogger.reopenLogFile()
                }
            }
            
            val wasSelected = _uiState.value.selectedLogFile == file
            _uiState.update { state ->
                val newFiles = state.logFiles.filter { it != file }
                state.copy(
                    logFiles = newFiles,
                    selectedLogFile = if (state.selectedLogFile == file) null else state.selectedLogFile,
                    logContent = if (state.selectedLogFile == file) "" else state.logContent
                )
            }
            
            if (wasSelected && _uiState.value.logFiles.isNotEmpty()) {
                selectLogFile(_uiState.value.logFiles.first())
            }
        }
    }
    
    fun clearAllLogs() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            
            withContext(Dispatchers.IO) {
                FileLogger.clearAllLogs()
            }
            
            loadLogFiles()
        }
    }
    
    fun shareLogFile(file: File) {
        viewModelScope.launch {
            try {
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
                _events.emit(LogViewerEvent.ShareFile(
                    uri = uri,
                    subject = "Xime 日志 - ${file.name}",
                    mimeType = "text/plain"
                ))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to share log file", e)
                _events.emit(LogViewerEvent.ShowError("分享失败: ${e.message}"))
            }
        }
    }
    
    fun saveToDownloads(file: File) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val values = ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        }
                        val uri = context.contentResolver.insert(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                        ) ?: throw Exception("Failed to create MediaStore entry")
                        context.contentResolver.openOutputStream(uri)?.use { output ->
                            file.inputStream().use { it.copyTo(output) }
                        }
                    } else {
                        val dest = File(
                            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                            file.name
                        )
                        file.copyTo(dest, overwrite = true)
                    }
                }
                _events.emit(LogViewerEvent.SavedToDownloads("Download/${file.name}"))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save log file", e)
                _events.emit(LogViewerEvent.ShowError("保存失败: ${e.message}"))
            }
        }
    }
}