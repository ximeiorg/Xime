# Xime 输入法

## 项目简介
这是一个基于 rime 框架实现的安卓手机输入法，采用 kotlin + jetpack compose 构建。

## 快速开始
- 构建： `./gradlew assembleDebug --quiet`
- 测试： `./gradlew test`
- **测试前置**：插件测试从 `build/plugin-js/<name>/main.js` 加载编译产物；跑测试前需先构建插件与测试夹具：
  `xipm build`（仓库根，编译插件）+ `cd tools/xime-plugin && xipm build test-fixture --out ../../build/plugin-js`

## 插件开发
- 插件源码为 TypeScript 模块（`plugins/<name>/main.ts` + `manifest.json` + `resources/`）：用 `definePlugin({...})` 定义扩展点并 `export default plugin`；由 Rust CLI `xipm` 编译为 IIFE 单文件 main.js（产物 `var plugin = (...)()` = 宿主 `globalThis.plugin`，QuickJS 执行）
- **v3 形态（TS 范式）**：host 网络/IO 服务为 async（`await host.http.request(...)`），失败 throw `XimeError`（`code` + `message`，无 `lastError()`）；纯计算 API 同步；扩展点按需 async（`panel.state/onAction`、`speech.*`、`clipboardSync.*`、`backup.*`、`onLoad/onUnload`；`transform.candidates` 必须同步）
- CLI 二进制名 `xipm`（`cargo run -- ...` 等价；也可 `cargo build --release` 后用 `tools/xime-plugin/target/release/xipm`）
- 构建全部插件： `xipm build`（仓库根零参数；产物 build/plugin-js/）
- 打包全部 xipk： `xipm pack`（仓库根零参数；产物 build/plugin-release/*.xipk）
- 构建/打包单个插件： 在插件目录内运行 `xipm build` / `xipm pack`
- 打包并同步内置插件到 app assets： `bash scripts/build-plugins.sh --with-assets`
- 新建插件骨架： `xipm init <name> --type tool`（自带 main.test.ts 测试骨架）
- 校验清单： `xipm check`（仓库根零参数批量）
- 运行插件测试（免真机；CLI 内嵌 QuickJS + mock host，与真机同引擎）： `xipm test`（仓库根零参数批量，无 main.test.ts 跳过；`--smoke` 做加载冒烟）
- 真机热调试（watch → 编译打包 → adb 推送 → am start 热安装/重载 + 日志跟随 + 回执）： `xipm dev <插件目录>`（需 adb；debug 宿主直用，release 宿主需先在 设置→关于 连点设备信息 7 次解锁并开启"插件开发模式"；无设备/组件缺失时快速失败）
- 真机插件日志回显： `xipm logs <插件目录>`（实时 `JsPlugin`/`PluginErrorLog` tag）；`--history` 读宿主 errors.jsonl（仅 debug 宿主）
- 真机安装 xipk： `xipm install <xipk 文件|插件目录>`（插件目录会先编译打包；与 `xipm dev` 同一热安装通道，装后等设备回执）
- 指定真机设备（多设备/无线调试，等价 `adb -s`）：全局参数 `-s <序列号>`，可写在子命令前后： `xipm -s <序列号> dev <插件目录>` / `xipm dev <插件目录> -s <序列号>`（缺省自动采用唯一在线设备）
- 类型检查（可选）： `npx -p typescript tsc -p tsconfig.json --noEmit`
- SDK 类型定义： `tools/xime-plugin/templates/xime-plugin.d.ts`
- **完整 CLI 用法**： [tools/xime-plugin/README.md](tools/xime-plugin/README.md)
- 清除插件数据： `./gradlew clearPlugins`
- 完全卸载主应用： `./gradlew uninstallApp`
- [插件开发指南](https://ime.ximei.me/plugins/PLUGIN_DEVELOPMENT_GUIDE) - 开发插件时必读

## 硬性规则（必须遵守，CI 会验证）
- 禁止自己提交代码
- PR 必须遵循最小修改原则
- 贡献前请阅读 [CONTRIBUTING.md](CONTRIBUTING.md)
- 禁止使用 `./gradlew clean`
- 必须使用中文回复
- 修改功能时，需要审查是否影响到其他功能。
- UI 要遵循material 3 设计
- 除非明确需要，否则不要自行安装apk，如果要自己安装，请使用 Android studio 内部的 debug 证书安装
- 除非明确需要，否则不要自行提交git
- 代码注释要简洁明了，不要做多余的注释
- 输入法设置有输入框，测试应该在输入法设置里进行

## 工作规则
- 每次只做一个功能点
- 当前功能点端到端验证通过后，才能开始下一个
- 不要在实现功能 A 时"顺便"重构功能 B
- 当觉得有必要时，就添加单元测试


## 每次会话开始时（上班打卡）
1. 读 PROGRESS.md 了解当前状态
2. 读 DECISIONS.md 了解重要决策
3. 从 PROGRESS.md 的"下一步"部分继续工作

## 每次会话结束前（下班打卡）
1. 更新 PROGRESS.md
2. 跑 `./gradlew assembleDebug --quiet` 确认一致状态
3. 提交所有已完成的工作

## Jetpack Compose
For all Compose/Android UI tasks, follow the instructions in
`.skills/compose-expert/SKILL.md` and consult the reference
files in `.skills/compose-expert/references/` before answering.