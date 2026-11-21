# 2026-11-21 播放接口修复

本次按“直接拉取上游的修复”接入播放修复。已复现已发布版本的旧 POST 播放模型接口返回空响应，并在修复后验证《全球死考》第七季第 1、2 集与一个对照剧集的真实媒体取流恢复。

## 基线和来源

- 发布基线：yuyehk/hongguoTV main `57c95f6afec5bd2d95806bb7eb55f3bf671dc6e4`（v0.11.13，versionCode 27）。
- 在 `.tooling/playback-fix-20261121/source` 解压该精确基线并移植、测试，以免混入根工作区原有未提交改动。
- 主要上游：[jackotom/hongguo-drama 92df757](https://github.com/jackotom/hongguo-drama/commit/92df757ed7be8ea658ee274a6a429b77f49e54e8) 的 video_tab V5 GET 修复。
- 上游源码、blob 校验、审查记录及独立签名向量：`.tooling/playback-check-20261121/upstream/`。
- 已将 10 个本次源码/测试文件同步回根项目并校验为同一内容；原文件备份在 `.tooling/playback-fix-20261121/root-before/`。NOTICE 只插入本次来源说明。
- 未暂存、提交、推送或发布；未修改版本号。根目录已有历史版本配置，未来发布应从已验证快照准备，不能直接把整个脏工作区作为发布内容。

## 变更

1. 优先使用 V5 签名的 `GET /reading/bookapi/video_tab/multi_video_model/v1/`。
2. GET 成功但正文为空时，才在同一匿名设备身份上回退一次原 POST 接口。原有身份恢复上限和预加载不恢复策略保留；HTTP/业务错误、坏 JSON、错集不会盲目回退。
3. 兼容 `data.video_model_data[episodeId].video_model`，始终按请求分集 ID 精确选择，不接受其他分集。
4. 修正 V5 无请求体签名使用 MD5(empty)，同时修正分支选择和 Medusa 输入；非 V5 和原 POST 签名不变。
5. 新增 6 项回归测试，并适配原有 GET 请求夹具、次数断言，保留身份隔离、取消、并发、缓存和自动跳集限制断言。

生产代码仅修改 `MobilePlayback.kt`、`MobileV5Signer.kt`、`PlaybackModels.kt`。App 的 Media3、换集预加载、画面衔接、账号/观看记录持久化及自动/手动更新逻辑未改动。

## 验证结果

- RED：新增 4 项播放路径测试与 2 项独立签名向量测试，分别在旧实现上确认 4/4、2/2 失败。
- GREEN：core 全量 309 项，0 失败、0 错误、0 跳过。
- app 首轮全量 209 项：208 通过、1 项 `PortableTransferServerTest.nonceOriginUtf8AndOversizeAreRejectedWithoutImport` 出现 3 秒 socket 读取超时。该类及生产逻辑均未修改。随后独立复跑该类 3 项全部通过。首轮 XML 已保留，未把重跑结果表述为首轮全绿。
- Lint：0 Error/Fatal，75 Warning，0 NewApi。
- 隔离复跑、Lint 与 `:app:assembleDebug` 最终退出码 0：`BUILD SUCCESSFUL in 10m 39s`。调试 APK 位于验证快照 `kotlin-tv/app/build/outputs/apk/debug/app-debug.apk`。

验证环境：JDK 17.0.19、项目内 Android SDK、离线 Gradle 缓存与 Robolectric 缓存。

初次命令（app 测试失败后停止）：

```powershell
./kotlin-tv/gradlew.bat -p kotlin-tv --offline --console=plain --no-daemon --max-workers=1 '-Pkotlin.compiler.execution.strategy=in-process' :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

隔离重跑并继续检查：

```powershell
./kotlin-tv/gradlew.bat -p kotlin-tv --offline --console=plain --no-daemon --max-workers=1 '-Pkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests com.hongguotv.nativeapp.PortableTransferServerTest :app:lintDebug :app:assembleDebug
```

## 真实媒体取流

调用本项目编译后的生产 core.jar，无网页、WebView、预览或测试片源。每集仅读取并验证前 65,536 字节 MP4 数据：

| 目标 | 分集 ID | 结果 |
| --- | --- | --- |
| 《全球死考》第七季第 1 集 | 7707465250475346969 | 5 个清晰度，选中 720/bytevc1，MP4 验证通过 |
| 《全球死考》第七季第 2 集 | 7707465250475363353 | 1 个清晰度，选中 1080/bytevc1，MP4 验证通过 |
| 对照分集 | 7703065456646556696 | 5 个清晰度，选中 720/bytevc1，MP4 验证通过 |

- 实测时间：2026-11-21 10:26:16（北京时间）。
- 修复后 core.jar SHA-256：`6e692cc1ef0a935bce925bed6fa57020dd544384615364e049afba3eac110e35`。
- 修复前第一目标抛出 `EmptyPlaybackResponseException`；后两次受既有身份冷却限制，未证明其独立失败。
- 证据：`.tooling/playback-check-20261121/baseline-probe.log`、`.tooling/playback-fix-20261121/fixed-probe.json`、`fixed-probe.log`。

以上证明元数据获取、媒体准备和起始数据读取恢复，不等于 Media3/ExoPlayer 从 00:00 自然播完第 1 集并连播第 2 集。本次未操作小米 S85、未改网络、未清除数据；无法据此声称电视账号/历史的现场核验已完成。电视上的已安装版本不会因本地代码修改而自动获得本修复。

## 风险与回退

- 仍依赖上游实时接口，返回可随服务端变化；GET 空响应回退和身份恢复都有明确上限。
- 没有数据库结构、配置格式、后台服务或 Android 最低版本变化。
- 若需回退代码，按 `root-sync.json` 和 `root-before/` 逐文件核对，不覆盖其他人的后续修改。完整本次差异在 `fix.patch`；本地历史改动未纳入。
- 正式发布需保留原证书及旧更新器可识别的 APK 命名并递增版本号。本次调试 APK 仅作构建验证，不是电视覆盖升级包。

