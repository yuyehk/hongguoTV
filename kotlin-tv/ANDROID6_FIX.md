# Android 6 播放修复版

- 基础代码：N3urda/hongguoTV main，eea73e6，0.9.0。
- 本地版本：0.9.0-android6-fix / versionCode 9；包名 com.hongguotv.nativeapp。
- 最低 Android 6.0 / API 23；compileSdk、targetSdk 保持 36。编译 SDK 版本不等于最低运行版本。
- 根据 https://github.com/N3urda/hongguoTV/issues/17 将播放入口改为 /novel/player/multi_video_model/preload/v1/。保留 video_platform=1024、mixed_video_id_map 的 1004 键、need_all_video_definition=true。仅接受当前分集的返回数据。
- 移除设置导航及页面、应用更新检查/下载/安装代码、安装权限和更新 FileProvider。播放中的倍速、画质、画面模式与定时控制保留。
- device_id 使用 SecureRandom 生成 16 位数字，首次使用后保存于应用私有 SharedPreferences；重启复用，卸载或清除数据后重新生成。参数和签名共用同一个 ID，不读取硬件 ID。
- registerDefaultNetworkCallback 改用 API 21 的 registerNetworkCallback；移除高版本 onBlockedStatusChanged 覆盖；Base64 改用已有 Bouncy Castle 的编码器；PriorityQueue 改为容量与 Comparator 两参数构造方法。
- Manifest 的 networkSecurityConfig 和 enableOnBackInvokedCallback 供新系统使用，Android 6 忽略这些属性，不会调用高版本 API。旧系统通过 usesCleartextTraffic 支持媒体 HTTP 地址。

## 编译

使用 JDK 17、Gradle Wrapper 8.14.3、Android SDK Platform 36 与 Build Tools。通过 local.properties 指定 SDK 路径。先检查，再构建 APK：

```
gradlew.bat :core:test :app:lintDebug
gradlew.bat :core:run --args="--playback-only 7694896136963755032"
gradlew.bat :app:assembleRelease
```

Release 签名由本地 keystore.properties 或 HONGGUOTV_KEYSTORE / HONGGUOTV_STORE_PASSWORD / HONGGUOTV_KEY_ALIAS / HONGGUOTV_KEY_PASSWORD 提供，源码包不包含密钥。本次交付采用独立本地签名，无法直接覆盖上游正式签名包。

Windows 上若 Gradle 出现 Unix domain socket 的 loopback connection 错误，可将 java.io.tmpdir 和 jdk.net.unixdomain.tmpdir 指向一个本地短路径目录。

测试、最终 APK 哈希及 Android 6 运行验证见交付目录内的验证说明。
