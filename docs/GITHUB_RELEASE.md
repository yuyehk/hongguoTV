# GitHub 打包与发布

工作流：`.github/workflows/android-release.yml`。

## 云端打包

main 中构建相关文件更新会自动触发，也可在 Actions 中手动运行。JDK17 / SDK36 下执行发布校验脚本测试、core 和 app 单元测试、Android lint 以及 R8 Release 编译。构建产物保存在 `android14-release-unsigned` artifact，包含未签名 APK、测试报告、Lint 报告和本次 `build-verification.json`。

`HONGGUOTV_UNSIGNED_RELEASE=1` 仅供无签名密钥的 CI 编译。未设置时沿用本机原有 signing 配置。unsigned APK 不能直接安装。

## 本机签名

下载实际 CI APK，核对产物摘要里的 SHA256，再用 Android build-tools `zipalign`、`apksigner` 和维护者原有签名密钥处理。私钥、密码以及 `keystore.properties` 不上传 GitHub，不加入 payload 分支。签署后核对包名、versionCode、versionName、证书，并进行覆盖安装/播放验证。

原签名证书 SHA256：`76e72e760843107a1640ad8544fe46b5772e28fa00b8708ba414d5173ea3c9fa`。

## Releases 发布

在 `codex/release-*` 分支中加入 `.release/`：签好的 APK、原始 `build-verification.json`，以及只包含以下字段的 `manifest.json`：

- `tag`：如 `v0.11.1`
- `sourceSha`：实际云端构建的完整40位提交
- `buildRunId`：成功的主分支打包 run ID
- `apk`：`hongguotv-<versionName>.apk` 文件名
- `apkSha256`：签后 APK 的 SHA256
- `certificateSha256`：上面的原签名指纹
- `versionName`、`versionCode`：与 APK 一致

此分支只传递可公开下载的签后 APK 和验证信息。主分支不加入构建二进制。

发布任务使用临时的 `GITHUB_TOKEN`；只有该任务有 contents:write。它独立查询对应打包运行必须成功、源码提交必须吻合，并下载该 run 的真实 artifact，核对原始报告和 unsigned APK 哈希，再逐 ZIP 内容条目比较签后 APK 与实际编译产物（仅排除签名元数据）。随后核对 APK 字节哈希、原签名证书、包名和版本，拒绝路径/环境变量注入。已有 Release 或指向其他源码的同名标签会使任务停止。

随后从精确源码提交导出 source.zip，生成 SHA256SUMS，创建 Draft Release，上传 APK、源码、摘要及校验文件；所有资源大小、摘要核验通过后才公开发布。打包失败不会触发发布，未完成的 draft 不会被当作成功发布。
