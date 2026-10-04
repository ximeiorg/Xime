package com.kingzcheung.xime.util

import android.content.Context
import android.util.Log
import com.kingzcheung.xime.BuildConfig
import com.kingzcheung.xime.settings.SettingsPreferences
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue

object FileLogger {
    private const val TAG = "FileLogger"
    private const val MAX_LOG_SIZE = 5 * 1024 * 1024
    private const val MAX_LOG_FILES = 10
    private const val QUEUE_CAPACITY = 4096
    
    private var logFile: File? = null
    private var logsDir: File? = null
    private var isInitialized = false

    /** 调试日志总开关：控制 v/d/i 级写文件（日志查看器）。
     * 仅 Debug 构建生效；Release 构建 v/d/i 级完全不写文件，省磁盘 I/O。 */
    @Volatile
    private var verboseLoggingEnabled = true
    
    private val logQueue = LinkedBlockingQueue<String>(QUEUE_CAPACITY)
    private var writer: BufferedWriter? = null
    private var running = false
    private val writeLock = Any()
    
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val fileDateFormat = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
    
    fun init(context: Context) {
        if (isInitialized) return
        try {
            logsDir = File(context.filesDir, "logs")
            if (!logsDir!!.exists()) {
                logsDir!!.mkdirs()
            }
            
            verboseLoggingEnabled = SettingsPreferences.isVerboseLoggingEnabled(context)
            
            openLogFile()
            cleanOldLogs()
            cleanRimeLogs(context)
            cleanOrphanedLogs()
            
            running = true
            Thread({ flusherLoop() }, "log-flusher").also { it.isDaemon = true }.start()
            
            isInitialized = true
            i(TAG, "FileLogger initialized, log file: ${logFile!!.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize FileLogger", e)
        }
    }
    
    private fun openLogFile() {
        val today = fileDateFormat.format(Date())
        logFile = File(logsDir!!, "kime_$today.log")
        
        if (logFile!!.exists() && logFile!!.length() > MAX_LOG_SIZE) {
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val newLogFile = File(logsDir!!, "kime_$ts.log")
            logFile!!.renameTo(newLogFile)
            logFile = File(logsDir!!, "kime_$today.log")
        }
        
        writer?.close()
        writer = BufferedWriter(FileWriter(logFile, true))
    }
    
    private fun flusherLoop() {
        val batch = ArrayList<String>(100)
        while (running) {
            try {
                batch.clear()
                batch.add(logQueue.take())
                logQueue.drainTo(batch, 99)

                val w = writer ?: continue
                synchronized(writeLock) {
                    for (line in batch) {
                        w.write(line)
                    }
                    w.flush()
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (e: Exception) {
                Log.e(TAG, "Log flusher error", e)
            }
        }
    }
    
    fun isInitialized(): Boolean = isInitialized

    /** 运行时切换 verbose 文件日志（跨进程通过设置项/onStartInput 同步）。 */
    fun setVerboseLoggingEnabled(enabled: Boolean) {
        verboseLoggingEnabled = enabled
    }
    
    fun v(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.v(tag, message)
        if (BuildConfig.DEBUG && verboseLoggingEnabled) writeToFile("V", tag, message)
    }
    
    fun d(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.d(tag, message)
        if (BuildConfig.DEBUG && verboseLoggingEnabled) writeToFile("D", tag, message)
    }
    
    fun i(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.i(tag, message)
        if (BuildConfig.DEBUG && verboseLoggingEnabled) writeToFile("I", tag, message)
    }
    
    fun w(tag: String, message: String, throwable: Throwable? = null) {
        Log.w(tag, message, throwable)
        val fullMessage = if (throwable != null) {
            "$message\n${throwable.stackTraceToString()}"
        } else {
            message
        }
        writeToFile("W", tag, fullMessage)
    }
    
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        val fullMessage = if (throwable != null) {
            "$message\n${throwable.stackTraceToString()}"
        } else {
            message
        }
        writeToFile("E", tag, fullMessage)
    }
    
    fun wtf(tag: String, message: String, throwable: Throwable? = null) {
        Log.wtf(tag, message, throwable)
        val fullMessage = if (throwable != null) {
            "$message\n${throwable.stackTraceToString()}"
        } else {
            message
        }
        writeToFile("F", tag, fullMessage)
    }
    
    private fun writeToFile(level: String, tag: String, message: String) {
        if (!isInitialized) return
        try {
            val timestamp = dateFormat.format(Date())
            val logLine = "$timestamp [$level] $tag: $message\n"
            logQueue.offer(logLine)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to queue log", e)
        }
    }
    
    private fun cleanOldLogs() {
        try {
            logsDir?.let { dir ->
                val logFiles = dir.listFiles()
                    ?.filter { it.name.startsWith("kime_") && it.name.endsWith(".log") }
                    ?.sortedByDescending { it.lastModified() }
                    ?: emptyList()
                
                if (logFiles.size > MAX_LOG_FILES) {
                    logFiles.drop(MAX_LOG_FILES).forEach { oldFile ->
                        oldFile.delete()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clean old logs", e)
        }
    }
    
    /** 纠错插件（C++ 侧）内部日志的落盘路径；kime_ 前缀使其纳入轮转/清理与日志查看器。 */
    fun correctionSinkPath(): String =
        logsDir?.let { File(it, "kime_correction.log").absolutePath } ?: ""

    fun getCurrentLogFile(): File? = logFile
    
    fun getAllLogFiles(): List<File> {
        return logsDir?.listFiles()
            ?.filter { it.name.startsWith("kime_") && it.name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }
    
    fun clearAllLogs() {
        try {
            logsDir?.listFiles()
                ?.filter { it.name.startsWith("kime_") }
                ?.forEach { it.delete() }
            
            val today = fileDateFormat.format(Date())
            logFile = File(logsDir!!, "kime_$today.log")
            openLogFile()
            
            i(TAG, "All logs cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear logs", e)
        }
    }
    
    private fun cleanOrphanedLogs() {
        try {
            logsDir?.let { dir ->
                dir.listFiles()
                    ?.filter { it.name.endsWith(".log") && !it.name.startsWith("kime_") }
                    ?.forEach { file ->
                        if (file.length() > MAX_LOG_SIZE) {
                            file.delete()
                        }
                    }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clean orphaned logs", e)
        }
    }

    private fun cleanRimeLogs(context: Context) {
        try {
            val rimeLogDir = File(File(context.filesDir, "rime"), "logs")
            if (!rimeLogDir.exists()) return
            rimeLogDir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".log") }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(MAX_LOG_FILES)
                ?.forEach { file ->
                    file.delete()
                }
            rimeLogDir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".log") && it.length() > MAX_LOG_SIZE }
                ?.forEach { file ->
                    file.delete()
                }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clean rime logs", e)
        }
    }

    fun reopenLogFile() {
        openLogFile()
    }

    fun flush() {
        writer?.flush()
    }

    /**
     * 同步把队列中尚未落盘的日志全部写出并 flush。
     * 崩溃处理器必须用这个而不是 [flush]：[flush] 只刷 writer，
     * 队列里的行仍依赖后台 flusher 线程，进程即将被杀时会丢失。
     */
    fun flushNow() {
        if (!isInitialized) return
        try {
            val batch = ArrayList<String>(logQueue.size)
            logQueue.drainTo(batch)
            val w = writer ?: return
            synchronized(writeLock) {
                for (line in batch) {
                    w.write(line)
                }
                w.flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "flushNow failed", e)
        }
    }
}