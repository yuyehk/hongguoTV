# 红果 TV · Android 14 定制版

当前源码版本：**0.11.10-android14-transitions**（versionCode 24）。面向小米电视 S85 Mini LED / Android 14，源码位于 [`kotlin-tv/`](kotlin-tv/)。安装包以实际交付的签名包为准。

[下载 Releases](https://github.com/yuyehk/hongguoTV/releases) · [Actions 打包记录](https://github.com/yuyehk/hongguoTV/actions/workflows/android-release.yml)

## 本版本功能

- 原生取流返回空响应时，可从失败页面选择「官网播放」，用遥控器方向键移动光标、确认键点击。官网自己的登录与播放规则仍适用。

- 播放接入当前五头签名与 video_tab 取流，前台播放及下一集预载复用匿名设备身份；格式异常可有界回退，不可用模型可在重试时重新获取。
- 修复预载等待或暂停期间的分段连接整体超时，支持恢复读取；实际网络读取超时和关闭取消仍生效。
- 片源只返回固定的 ByteVC2 文件时，向同一片源请求一次兼容编码。已验证《祁教授，借个婚可好》第一集可取得标准 H.265 资源。
- 修复选集面板丢弃已预载下一集、未预载切集提前清空画面的问题；慢网等待保留上一帧并暂停旧集声音，保留正确的播放意图、定时停止和错误提示。
- 右上角未登录显示“登录”，登录后显示头像；头像菜单统一提供云端记录、刷新、退出、新增、切换与管理账号，最多保存 10 个账号。
- 使用 Android Keystore 加密账号；保留旧登录态迁移，并隔离不同账号的云端记录与待上传进度。
- 保留单播放器换集预加载、自动连播和手动下一集优化，以及本机收藏、观看进度、定时停止和异常提示。
- 打开应用时后台检查公开 Releases，临时网络故障会重试一次，播放中发现新版会等到回首页再提示。「工具」可手动检查或查看上次检查结果。下载安装前会校验 SHA-256、包名、版本和签名。
- 保留之前移植的片源、搜索、预约、评论、外观、备份等功能。

详见 [0.11.2 换集修复说明](kotlin-tv/EPISODE_TRANSITIONS_0_11_2.md)、[多账号说明](kotlin-tv/MULTI_ACCOUNT.md)、[完整功能说明](kotlin-tv/FEATURE_COMPLETION.md)、[功能移植说明](kotlin-tv/FEATURE_PORT.md)。

## 构建与验证

使用 JDK 17、Android SDK 36、项目自带 Gradle wrapper：

```sh
./kotlin-tv/gradlew -p kotlin-tv :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Windows 使用 `kotlin-tv\gradlew.bat`。Release 签名需要自行配置私有 `keystore.properties`；仓库不包含原安装包的签名私钥或任何登录数据。

仓库 Actions 从源码测试并编译未签名 Release，原证书签名在维护者本机完成；校验后的安装包再通过发布任务进入 Releases。Actions 的 unsigned artifact 不能直接安装。流程见 [GitHub 打包与发布](docs/GITHUB_RELEASE.md)。

0.11.1 交付时已完成 272 项 core 单元测试、199 项 app 单元测试及 21 项 Android 14 模拟器运行测试；另已验证原签名覆盖升级及最终 Release 的公开剧集播放。账号操作使用合成身份验证，尚未进行真实账号授权或实体电视验收。参见 [验证摘要](docs/releases/0.11.1/verification.json)。

## 来源与许可

基于 [N3urda/hongguoTV](https://github.com/N3urda/hongguoTV) 定制，保留 GPL 许可及上游归属。移植来源与组件声明见 [NOTICE](kotlin-tv/NOTICE.md) 和仓库 [LICENSE](LICENSE)。
