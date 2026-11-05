package com.kingzcheung.xime.plugin.core.security

/**
 * 插件错误分类。
 *
 * UI 层据此渲染"可读原因 + 处理建议"（见 [PluginErrorLog.userMessage]/[PluginErrorLog.userHint]），
 * 让**终端用户**在出错时能看懂发生了什么、该怎么办，而不是面对一段原始异常文本。
 */
enum class ErrorCategory {

    /** JS 脚本错误：脚本加载/调用/回调抛错（错误含 main.js:行号）。 */
    SCRIPT_ERROR,

    /** 网络访问被拒绝：插件请求了未声明或未授权的域名。 */
    NETWORK_DENIED,

    /** 网络请求失败：超时/无法解析域名/连接被拒等 IO 错误。 */
    HTTP_ERROR,

    /** 插件执行超时（疑似死循环），已被停止执行（中毒）。 */
    TIMEOUT_POISONED,

    /** 插件执行超时，已中止本次调用但**未**停用插件（常见于慢网络下的长传输）。 */
    TIMEOUT,

    /** 长连接会话中断（SSE/WS 流错误）。 */
    STREAM_ERROR,

    /** 其他错误。 */
    OTHER
}