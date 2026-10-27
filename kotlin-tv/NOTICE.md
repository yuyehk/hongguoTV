# 原生版第三方声明

`core` 中内容请求签名、Spade URL / key 处理、MP4 CENC 处理是以下 GPL-3.0 源码的 Kotlin 移植：

- drpys, `spider/js/红果果[短].js`
- https://github.com/woshishiq1/drpys/tree/22261adfa31435e3b3ef5a730b8a2a75bcaf1715
- 本仓库对照源：`../server/vendor/hongguo.cjs`，取回于 2026-09-21。
- 本版改动：完整 Kotlin 实现；签名只选择已验证的 hash branch 0；加密媒体通过 Media3 DataSource 直接读取；增加样本边界检查、子样本处理和 AVC 原始编码恢复。

`kotlin-tv/` 原生应用源码和派生实现以 GPL-3.0-only 发布，许可证全文见 `LICENSE`。发布 APK 时同时提供本分支对应源码、Gradle 包装器和构建说明。APK 不包含 Node、React Native、JavaScript 引擎或该 JavaScript 源文件。`tools/signing-oracle.cjs` 仅为开发期生成对照测试数据的工具，不参与应用构建或运行。

其他依赖：

- 2026-10-27 功能移植参考 tsukiyo-zero/hongguoTV，GPL-3.0-only，快照 `a99cd6036ea3cf823f98ab69ea36d95fb871e159`：https://github.com/tsukiyo-zero/hongguoTV 。移植公开的原生账户、搜索、发现、排行榜、相关作品、评论弹幕、解码能力与配套测试；保留本项目包名、素材、签名、搜索兜底及播放队列修复。参考实现的账户接口协议来源同时见其 `docs/ACCOUNT_SYNC.md`。
- 同轮工具功能参考 jycao2/hongguoTV，GPL-3.0-only，快照 `20d3f641b8feeb76c9f7a6a90909b6a85d578ec1`：https://github.com/jycao2/hongguoTV 。借鉴按剧跳过、收藏分组、备份、遥控器偏好、诊断与可选片源等功能；不移植安装统计、应用更新或设置页面。
- 原生评论表情素材及映射随 tsukiyo-zero 公开源码移植，保留其源码声明，独立来源说明见随源码交付的素材文件。

- 本地 Android 6 搜索修复参考 jycao2/hongguoTV 的 GPL-3.0-only 接口适配，提交 `7e4dfb5d7f4e406a49d3199f2cf05f25cbb83966`（2026-10-22）：https://github.com/jycao2/hongguoTV/commit/7e4dfb5d7f4e406a49d3199f2cf05f25cbb83966 。仅移植新版站点搜索接口与类型区分；保留本地播放与 Android 6 改动。

- ZXing core（本机生成手机输入、备份与登录二维码）：https://github.com/zxing/zxing — Apache-2.0
- Kotlin 标准库：https://github.com/JetBrains/kotlin — Apache-2.0
- AndroidX Media3：https://github.com/androidx/media — Apache-2.0
- OkHttp / Okio：https://github.com/square/okhttp / https://github.com/square/okio — Apache-2.0
- Bouncy Castle SM3：https://github.com/bcgit/bc-java — MIT
- jsoup（仅解析官网 HTML，不执行网页脚本）：https://jsoup.org/ — MIT
- Android desugar_jdk_libs：https://github.com/google/desugar_jdk_libs — GPL-2.0 with Classpath Exception
- org.json（仅 JVM 工具，APK 使用 Android 内置实现）：https://github.com/stleary/JSON-java — 公有领域声明
- JUnit（仅测试）：https://github.com/junit-team/junit4 — EPL-1.0

应用图标和 TV banner 沿用本仓库素材。这里的应用不是内容平台官方客户端。
