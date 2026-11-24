# v0.11.17 SDK 会话播放修复与验证记录

本文记录发布源码的本地验证：回归、生产 Kotlin 取流，以及真实正片的 App 原生整集和自动连播均已通过。小米 S85 尚未连接，电视整集与连播、覆盖升级后的账号和原有记录仍未现场验证。正式 CI 与签名产物回执由对应 Release 提供。

版本目标：`0.11.17-android14-transitions`，versionCode `31`，包名 `com.hongguotv.nativeapp`。正式升级沿用原证书，不清除 App 数据。

## 问题与修复

2026-11-24 晚间，v0.11.16 的匿名注册成功，但原有 `preload` 和 `video_tab` 播放模型接口在一次有限身份恢复前后均返回 HTTP 200、完整正文 0 字节。独立对照参考实现的七条直接 HTTP 路线也未取得模型。该证据来自本机当前网络环境，不构成所有地区或网络均故障的结论。

固定参考为 [jackotom/hongguo-drama ea5a852f65acdc4c5b11265ca13b03d115096dc7](https://github.com/jackotom/hongguo-drama/commit/ea5a852f65acdc4c5b11265ca13b03d115096dc7)，主要对应 `scripts/sdk-playback/vendor/phone_account/` 与 `resolve_cli.py`。参考实现中独立的匿名 SDK 会话路径经本机诊断成功取得目标第 1 集模型；随后将必要协议移植为纯 Kotlin。

新增模块负责匿名注册、SDI 握手请求/响应、V5 签名封装、滚动会话状态和严格模型解析。官方输入的帧常量、签名常量、全部 1024 字节替换表与已有实现完全相同，只补充交换材料及未知属性 sentinel。开发期以独立上游 Python/SDK 向量对拍摘要分支；APK 不包含桌面 helper、Python、Unicorn 或原 SDK 二进制，运行时不下载 SDK。

原取流路径保留。只有其有限恢复仍返回空正文时，前台请求才尝试 SDK 会话；HTTP 拒绝、业务拒绝、坏 JSON、错集、普通网络错误不触发 SDK 回退。SDK 返回有效且精确匹配的模型后，本仓库实例为后续集和预加载复用该来源。即使当前集只有不支持的编码，下一集仍可继续使用已经取得有效模型的 SDK 来源，避免重新进入旧路径冷却。

冷启动最多完成一次匿名注册和两步握手；失败不立即重复注册或重发模型，清除不确定的 token/Ladon 状态并限制后续恢复频率。尚未选择 SDK 的推测预加载不得启动新 SDK 恢复；已选会话失效后，禁止身份恢复的预加载也不得重建会话。请求按会话串行，取消信号传播到正在执行的请求。

账号 Cookie、通用鉴权头和用户账号存储与匿名 SDK 会话隔离。匿名身份、允许的注册 Cookie 和 SDK token 仅驻留内存；错误信息和对象文本不输出 token、签名 URL 或媒体密钥。

## 保留的应用行为

原生 Media3 播放、播放器界面、手机触摸菜单、电视遥控操作、画面衔接及更新器保持原有路径。预加载改为复用当前 `ContentRepository` 的匿名模型会话，其媒体预热仍保留独立 HTTP transport、线程和取消控制。账号及观看记录存储未修改，未增加迁移或清空数据操作。

`AppUpdateSource` 无源码修改；发布继续使用旧更新器识别的 `hongguotv-0.11.17-android14-transitions.apk` 及 SHA256 校验文件。账号和历史在目标电视覆盖升级后的实际保留情况仍须现场核对。

## 已完成的本地验证

2026-11-24 最终回归构建的实际 XML 报告统计：

| 检查 | 结果 |
| --- | --- |
| Core JUnit | 356 tests，59 suites；0 failures / errors / skipped |
| App Debug JUnit | 213 tests，59 suites；0 failures / errors / skipped |
| Debug Lint | 0 errors，75 warnings |
| 发布流程守卫 | `python -m unittest discover -s tests -p test_ci_release.py -v`，10/10 通过 |
| 生产更新器离线准备 | `verify-live-update.py --prepare-only` 已编译并加载真实 `AppUpdateSource`，未请求更新服务器 |

新增 SDK 定向测试包含仓库回退 6 项、真实 SDK 摘要 oracle 1 项、交换编解码 10 项、会话/传输 13 项、签名/请求 5 项。覆盖有界解析、独立加密向量、请求正文绑定、原证书和实际 CI 产物绑定守卫、精确分集、账号隔离、取消、冷却及预加载边界。

上游 SDK 诊断于 UTC 12:44:31—12:45:17 取得精确目标第 1 集模型，报告时长 158.918 秒。该诊断用于证明独立协议路线的可行性，不是 App 原生播放验收。

生产 Kotlin 检查于 UTC 13:09:33—13:09:35 成功完成：

| 目标 | 分集 ID | 取流与媒体检查 |
| --- | --- | --- |
| 《全球死考》第七季第 1 集 | `7707465250475346969` | 5 个档位；选取 720P / bytevc1；MP4 准备成功，读取 65,536 字节 |
| 第 2 集 | `7707465250475363353` | 1 个档位；1080P / bytevc1；MP4 准备成功，读取 65,536 字节 |

该次检查使用的 `core.jar` SHA256 为 `7f94e43ba7dd2c36af2d92c05c1b21e3e400f63d03f9823144056c338c468ecd`。它是当时开发产物的真实取流证据，不作为最终签名 APK 的哈希。短媒体读取不证明完整解码、整集自然播放或自动连播。

本地脱敏证据保存在开发任务目录 `.tooling/playback-recheck-20261124/`：

- `sdk-probe-20261124T124431Z.json`：独立 SDK 路线诊断。
- `sdk-kotlin-probe-20261124T130933Z.json`：生产 Kotlin 两集取流与媒体字节检查。
- `sdk-materials-comparison.json`：官方输入与已有常量逐字节比较。
- `release-v01117/final-source-review.json`：报告计数与发布辅助脚本检查。

这些开发证据不包含账号 Cookie、真实匿名身份、SDK token、签名播放 URL 或媒体密钥；不随源码加入临时 SDK 缓存和媒体文件。

## 已通过：最终原生整集与连播

2026-11-24 北京时间 21:37:49—21:40:55，在 API 34 隔离模拟器 `emulator-5570`，执行现有 `LivePlaybackRecoveryTest.firstEpisodeNaturallyEndsAndSecondPlays`，结果 `OK (1 test)`，JUnit 用时 184.557 秒。包名为 `com.hongguotv.nativeapp.localrecovery01115`，版本配置为本版 31 / 0.11.17；该隔离包不包含用户账号。

App 自己的 Media3/ExoPlayer 播放精确匹配的《全球死考》第七季正片。第 1 集从 00:00、1 倍速自然播放，时长 158,918 ms，在位置 158,934 ms 收到原生 `STATE_ENDED`；观察到 4,743 帧。App 的片尾逻辑自动在同一 ExoPlayer 进入第 2 集，观察到 303 帧，持续播放至 10,246 ms，持久化历史推进到第 2 集。未拖进度、加速、替换源，未使用网页、WebView、预览或测试片源。

实际生产日志观察到 `Next episode queued`，播放列表项数达到 2，证明本版预加载入口确实运行。最终衔接为 `nativeEndedTransition=true`、`naturalPlaylistTransition=false`：这是片尾事件驱动的自动续播，不能宣称本轮验证了无缝播放列表切换。

- 验收 APK SHA256：`e1bfb269c50964a5955481c3cea6ae25880251550d5daa188736a29c05d5bf10`。
- 测试 APK SHA256：`f25e5cfc628bafbf83cea0fcad68362d742ad2ade4937df983274c86a8c5bd07`。
- 播放证据 JSON SHA256：`f81c86c534f2a75d2cbb8a3d3399e897b95fc72326eed3c34a3256324837c347`。
- 本地证据目录：`.tooling/playback-recheck-20261124/native-sdk-acceptance-20261124T131200Z/`，含 `native-verification.json`、`live-playback-recovery.json`、`history-verification.json`、`player-events.txt`、`instrumentation.txt`。

结束后已关闭本轮模拟器。此测试证明本地隔离 App 的新观看记录更新，不证明实体电视原账号或原有历史保留。

## 正式构建、升级与电视限制

正式发布沿用仓库的 main CI 构建、下载实际 CI 产物、本地原证书签名、发布分支校验流程。成功运行、源码 SHA、unsigned APK 哈希与测试统计由 [v0.11.17 Release](https://github.com/yuyehk/hongguoTV/releases/tag/v0.11.17) 的 `build-verification.json` 和构建链接提供；发布 APK 哈希由 `SHA256SUMS` 提供。这些后续产生的回执不写入它们所绑定的源码提交本身。

原证书 SHA256 为 `76e72e760843107a1640ad8544fe46b5772e28fa00b8708ba414d5173ea3c9fa`。下载和签名脚本核对本地 HEAD、成功 main CI、artifact、内部报告、APK 内容、包名、递增版本及原证书一致后才继续。签名与发布后的真实更新器下载检查回执保存在本地 `outputs/update-v0.11.17/` 和任务目录 `release-v01117/`。

用户确认电视仍是 `192.168.1.7:5555`；本轮 ADB 未连接成功，另一台电脑的「红果tv」会话仍不可达。没有覆盖安装、读取电视账号或核对原有历史，也没有更改电视网络。依用户此前指示，正式版发布后可由电视在线更新；现场启动/手动检查更新、整集连播和账号历史保留仍须在电视验证。
