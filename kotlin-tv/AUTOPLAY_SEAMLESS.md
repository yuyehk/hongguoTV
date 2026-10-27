# 0.9.2 Android 6 自动连播与手动下一集优化

适用：上次交付的 0.9.0-android6-fix、0.9.1-android6-smooth 的后续修复版；包名不变，versionCode 11，使用同一份本地签名。

## 播放行为

- 单个 Media3 1.8.0 ExoPlayer，当前集结束时直接进入已准备的下一集，不再先 stop/clearMediaItems 后重建播放源。
- 当前播放稳定至少 3 秒且有至少 5 秒缓冲时，解析并缓存下一集前 1 MiB，然后加入播放队列；无需等到片尾。使用 Media3 1.8.0 的 playlist preloading 在主播放加载空闲时准备下一集约 3 秒样本，并对共享分配器检查 12 MiB 目标。最多保留当前集和下一集；自然过期 120 秒后允许重新准备。
- 手动点“下一集”或遥控器跳到下一集时，若队列内容、清晰度和有效期匹配，直接 seek 到现有下一项，不停止、清空或重建播放器。已跳过的当前集保存为未完成，手动跳集不消耗定时停止集数；未准备好时仍正常加载。
- 当前集完成记录、下一集标题/清晰度/观看位置/系统媒体会话随实际媒体边界同步；定时停止优先于自动续播，预加载不消耗按集计数。
- 暂停、选集、音频焦点抑制、后台、退出和低内存时取消不用的下一集；过期或不匹配的预加载不会被使用。
- 当前集缓冲不足时优先保证当前播放。下一集解析或数据读取失败只丢弃预加载，当前集继续；到片尾仍可走普通加载与重试。
- 700ms 内的瞬间缓冲不闪出转圈；真实持续缓冲和失败仍有提示。主动暂停或选集后恢复可重新预加载，不被取消前的重试间隔阻挡。
- 保留 Android 6/API 23、随机设备 ID、修复后的 preload 播放接口，以及设置页/应用更新已移除的状态。

任意手动跳到未准备的剧集、打开选集暂停后选剧集、网络中断、接口异常、设备解码器切换不承诺零等待；不能用隐藏加载图标代替缓冲和解码验证。手动队列 seek 在 Media3 内部可能短暂报告 BUFFERING，需结合首帧耗时判断体感。

## 检查与构建

```
gradlew.bat :core:test :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
adb -s <Android6-TV-emulator> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <Android6-TV-emulator> install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s <Android6-TV-emulator> shell am instrument -w -e class com.hongguotv.nativeapp.AutoplayIntegrationTest com.hongguotv.nativeapp.test/androidx.test.runner.AndroidJUnitRunner
gradlew.bat :app:assembleRelease
```

测试视频为 FFmpeg testsrc2/sine 生成，不是第三方节目；仅打进测试 APK，不打进交付 APK。生成参数：320x180、24fps、12 秒、H.264 CBR 2000k、AAC、faststart。测试在真实 API-23 Activity/Media3 播放器内运行，通过 OkHttp 拦截器提供确定的测试媒体和接口响应。

0.9.1 历史检查：82 项核心测试、6 项 API-23 TV 播放集成测试通过；受控媒体两次自动边界无重新缓冲。0.9.2 额外覆盖已准备的手动切换、长剧集提前准备、连续跳集、未准备的普通加载和共享分配器限制。新增 90 秒低码率测试视频同样由 FFmpeg testsrc2/sine 生成，仅包含在测试 APK。最终结果以本版交付目录的验证说明为准，不能用受控媒体的帧间隔推断所有片源或物理电视的表现。

参考：https://developer.android.com/media/media3/exoplayer/playlists 、https://developer.android.com/blog/posts/elevating-media-playback-introducing-preloading-with-media3-part-1 ，以及当前锁定的 Media3 1.8.0 源码。最终检查结果、APK SHA-256、真实接口录屏和设备验证范围见交付目录验证说明。
