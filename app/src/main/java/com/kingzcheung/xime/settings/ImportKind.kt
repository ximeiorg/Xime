package com.kingzcheung.xime.settings

/** 导入文件的落点类型；浏览器导入据此决定提示与后续动作。 */
enum class ImportKind {
    /** 输入法词库（.scel/.bdict/.dict/.bin/.txt 中的词库）。 */
    LEXICON,

    /** 方案包压缩包（.zip/.tar.gz），进 market 等用户安装。 */
    SCHEME_PACKAGE,

    /** Rime 配置/词典 yaml、纯文本，落 rime 目录。 */
    CONFIG,

    FONT,
    IMAGE,

    /** 其它普通文件。 */
    FILE,

    /** 插件包（.xipk）。 */
    PLUGIN,
}
