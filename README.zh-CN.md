<div align="center">
  <img src="docs/assets/toki.svg" width="96" height="96" alt="Toki 图标">
  <h1>Toki</h1>
  <p>让 TikTok 更合你的使用习惯。</p>
  <p><a href="README.md">English</a> · <strong>简体中文</strong></p>
  <p>
    <a href="https://github.com/MeiYongAI/Toki/releases">下载</a> ·
    <a href="https://t.me/toki_lsposed">Telegram 群组</a> ·
    <a href="#development">开发</a> ·
    <a href="#support">支持开发</a>
  </p>
</div>

Toki 是面向 TikTok 的 LSPosed 模块，采用 Material 3 界面，提供 57 种语言选项。

> **AI 生成声明：** 本模块使用 AI 生成的代码进行开发，可能存在错误。请在使用前了解变更并充分测试，不要将其视为可靠性保证。

## 主要功能

- **信息流过滤**：跨视频列表过滤广告，包含作者主页；推荐页支持过滤直播、图文、标记为 AI 生成的视频与照片、话题及创作者推荐卡片，并按关键词、作者、时长、播放量和点赞量筛选，阻止离线视频插入。
- **播放控制**：自定义倍速、播放时自动清屏、全屏播放、进度条设置与自动连播解锁。
- **媒体与工具**：无水印下载、自定义保存目录、音频限制处理、翻译选项和评论正文复制。
- **区域设置**：在 TikTok 内伪装地区、SIM 卡、语言、时区与位置，显示作者地区。
- **便捷管理**：功能状态查看、配置导入导出与自动查找适配方法。

## 开始使用

**使用条件：** Android 9 及以上、已正常运行且支持 **libxposed API 102** 的 LSPosed，以及 TikTok。Toki 不能脱离框架独立生效。

**已适配 TikTok 版本：** `47.0.3` · `46.8.3`。不同下载渠道的构建可能存在差异，其他版本不保证可用。

1. 从 [Releases](https://github.com/MeiYongAI/Toki/releases) 下载安装 Toki。
2. 在 LSPosed 中启用模块，并在作用域中勾选已安装的 TikTok。
3. 打开 Toki，选择需要的功能。
4. 打开 TikTok。如果出现方法查找窗口，请等待完成；成功后 TikTok 会关闭，再次打开即可应用适配结果。

支持的包名：`com.zhiliaoapp.musically` 和 `com.ss.android.ugc.trill`。

**从 0.x 迁移：** Toki 1.0.0 使用新模块包名 `io.github.meiyongai.toki` 和新的正式签名，会与 `com.seepd.toki` 分开安装。请先在 LSPosed 中停用原模块，再启用新版；配置不会自动迁移，卸载前请保留需要的设置。详情见[更新日志](CHANGELOG.md)。

## 交流与反馈

欢迎加入 [Telegram 群组](https://t.me/toki_lsposed)。反馈问题时，请附上 Toki 与 TikTok 版本、TikTok 下载来源、复现步骤及相关 LSPosed 日志。分享日志前请移除个人信息。

<a id="development"></a>

## 开发

### 开发环境

- Android Studio 与 JDK 21（运行 Gradle；Java 源码与目标级别为 17）
- Android SDK 37
- Android Gradle Plugin 9.4.0，Gradle 9.7.1（项目已提供 Wrapper）

应用目标版本为 Android 35，最低支持 Android 9（API 28）。依赖从 Google Maven 和 Maven Central 获取。

### 构建与测试

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:lintRelease
./gradlew :app:assembleRelease
```

Windows 使用 `gradlew.bat`。主要源码目录为 `app/src/main/java`、`app/src/main/res` 和 `app/src/test/java`。Release 构建启用 R8 与资源压缩；存在本地签名配置时，签名 APK 输出到 `app/build/outputs/apk/release/app-release.apk`。

自行签名时，将 [keystore.properties.example](keystore/keystore.properties.example) 复制为 `keystore/keystore.properties`，填写自己的密钥文件与凭据。没有该文件时，Release APK 不签名。在本机设置 `JAVA_HOME` 或 Android Studio 的 Gradle JDK，仓库不保存特定电脑的 JDK 路径。

Release 密钥不会纳入版本控制。请私下备份并保护 `keystore/toki-release.jks` 和 `keystore/keystore.properties`；丢失该密钥后，后续版本无法覆盖安装到同一应用。当前公开证书指纹为：

`SHA-256 74:09:5F:B8:C2:88:80:15:FC:DD:B9:3E:3C:14:F6:F6:3D:2A:EA:33:40:AB:5E:F9:6D:60:B0:C0:14:E1:6C:07`

提交修改前请运行单元测试和 Lint；不要提交 `app/build` 中的生成文件。

## 许可证

本项目采用 [MIT 许可证](LICENSE)，依赖许可与致谢见[第三方声明](THIRD_PARTY_NOTICES.md)。

<a id="support"></a>

## 支持开发

如果 Toki 对你有帮助，可以通过 [Ko-fi](https://ko-fi.com/meiyongai) 或 [支付宝](app/src/main/res/drawable-nodpi/alipay.jpg) 支持开发，也可在 Toki 首页的「支持开发」中找到赞助入口。赞助完全自愿。

<details>
<summary>USDT · TRC20 / Tron</summary>

`TXoTeZLpbQdn4wZF51858bC3zCwS822HbB`

仅使用 TRC20（Tron）网络，转账前请核对地址与网络。

</details>

## 免责声明

Toki 是独立项目，与 TikTok 或字节跳动不存在隶属或背书关系。本模块会修改应用行为，按现状提供，不保证兼容性、稳定性或账号安全。使用风险由用户自行承担，请遵守适用法律及平台条款。尊重创作者权益，仅在获得许可后下载或再利用内容。
