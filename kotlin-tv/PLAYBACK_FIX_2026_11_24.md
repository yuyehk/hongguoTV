# 原生播放恢复 0.11.15

修复旧版播放接口返回 HTTP 200、正文为空的问题。基线是我方正式版
`0d971fd12ed1d56dd332fa0eefe2e7efddd15f16`（0.11.14）。

## 来源及改动

移植 N3urda/hongguoTV 0.16.14 的 GPL-3.0-only 实现：
https://github.com/N3urda/hongguoTV/commit/ef328f3a3dc28479d90608a507996136672cf946

- V5 Medusa 字段 21 从 377 调整为上游验证的 312，仍用纯 Kotlin 签名。
- 请求改为当前 74532 App profile、gzip JSON、`preload/v1` 和 level 2。
- 主模型空响应后才尝试同身份 video_tab；正确但不兼容的备用模型最多再请求一次备用 App 端点。
- 仅有内联视频列表却无兼容档位时，使用模型提供的 HTTPS VOD 地址按 codec 1、3 最多协商两次。
- 保留严格分集 ID 校验、2 MiB 响应限制、取消、HTTP/业务拒绝处理和身份恢复预算；不把 403/429 当作空响应重试。
- VOD 不转发模型签名或账号 Cookie；旧 URL-only 模型继续交给既有兼容恢复流程。

生产行为只修改 `MobilePlayback.kt`、`MobileV5Signer.kt`、`MobileV5Constants.kt`。
账号、Library/观看记录、Media3、预加载、画面衔接、自动/手动更新生产实现均未修改。
无数据库迁移、无新后台服务、无电视网络配置变更。

## 验证

本次新增 12 项签名、请求、回退及损坏片源不跳集回归。
发布守卫 10 项通过。完整 core/app 单元测试、Lint 和 R8 Release 构建由发布 CI
对准确源码提交执行；最终结果绑定在 Release 的 `build-verification.json` 中。

2026-11-24 使用编译后的生产 `ContentRepository` 和 `RemoteVideo` 实测：

| 分集 ID | 档位数 | 选择结果 | 媒体读取 |
| --- | ---: | --- | --- |
| 7707465250475346969（第七季第 1 集） | 5 | 720 / bytevc1 | MP4 前 65,536 字节通过 |
| 7707465250475363353（第七季第 2 集） | 1 | 1080 / bytevc1 | MP4 前 65,536 字节通过 |
| 7703065456646556696（对照） | 5 | 720 / bytevc1 | MP4 前 65,536 字节通过 |

上述取流检查不是整集播放验收。新增 opt-in `LivePlaybackRecoveryTest` 使用
App 原有真实目录、取流和 Media3，要求第 1 集从 00:00 自然结束，自动进入第 2 集
播放至少 10 秒并写入本机历史；禁止 seek、加速和预览片段。只能在独立调试包
`com.hongguotv.nativeapp.localrecovery01115` 上显式运行：

```text
adb -s emulator-5570 shell am instrument -w -r \
  -e class com.hongguotv.nativeapp.LivePlaybackRecoveryTest \
  -e livePlaybackRecovery true \
  com.hongguotv.nativeapp.localrecovery01115.test/androidx.test.runner.AndroidJUnitRunner
```

实际执行结果以 `files/live-playback-recovery.json` 的 `passed` 和事件记录为准；
本地模拟器结果不等同小米 S85 实测。用户已指定发布后自行在线更新验收电视。

## 发布及回滚

版本码 29，版本名 `0.11.15-android14-transitions`，保留包名
`com.hongguotv.nativeapp` 和旧更新器识别的 APK 名称后缀。
只对准确源码的成功 CI unsigned APK 使用原证书签名，并核对签名前后应用内容。

不卸载、不清除数据。若需回滚代码，应使用原证书将已知稳定源码构建为更高版本码的
覆盖升级包，再通过同一发布流程分发；旧版本直接降级不作为常规回滚流程。
