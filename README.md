<div align="center">
  <img src="docs/assets/toki.svg" width="96" height="96" alt="Toki icon">
  <h1>Toki</h1>
  <p>More control over your TikTok experience.</p>
  <p><strong>English</strong> · <a href="README.zh-CN.md">简体中文</a></p>
  <p>
    <a href="https://github.com/MeiYongAI/Toki/releases">Download</a> ·
    <a href="https://t.me/toki_lsposed">Telegram</a> ·
    <a href="#development">Development</a> ·
    <a href="#support">Support</a>
  </p>
</div>

Toki is an LSPosed module for TikTok, with a Material 3 interface and 57 language options.

> **AI-generated project.** This module is developed with AI-generated code. It may contain errors; review changes and test carefully before relying on it.

## Features

- **Feed filters** — ads across video feeds, including creator profiles; recommendation filters for LIVE, photos, AI-labeled videos and photos, topic/creator cards, keywords, duration, views and likes. Block offline video insertion.
- **Playback controls** — custom speeds, auto-hide controls, fullscreen playback, progress bar options and auto-scroll unlocking.
- **Media & tools** — watermark-free downloads, custom save folders, audio restriction handling, translation options and comment text copying.
- **Region settings** — region/SIM, language, time zone and location spoofing within TikTok; creator region display.
- **Easy management** — feature status, settings import/export and automatic method discovery.

## Get started

**Requires:** Android 9 or newer, a working LSPosed installation supporting **libxposed API 102**, and TikTok. Toki does not work as a standalone app without the framework.

**Adapted TikTok versions:** `47.0.3` · `46.8.3`. Builds from different stores may behave differently; other versions are not guaranteed to work.

1. Install Toki from [Releases](https://github.com/MeiYongAI/Toki/releases).
2. Enable it in LSPosed and select your installed TikTok in the module scope.
3. Open Toki and choose the features you want.
4. Open TikTok. If a method-discovery dialog appears, let it finish. TikTok closes when discovery succeeds; open it again to apply the results.

Supported package names: `com.zhiliaoapp.musically` and `com.ss.android.ugc.trill`.

**Moving from 0.x:** Toki 1.0.0 uses a new module package, `io.github.meiyongai.toki`, and a new release signing key. It installs separately from `com.seepd.toki`; disable the previous module in LSPosed before enabling this one. Settings are not migrated automatically. Keep any needed configuration before uninstalling. See the [release notes](CHANGELOG.md).

## Community

Join the [Telegram group](https://t.me/toki_lsposed) for discussion and feedback. When reporting a problem, include your Toki/TikTok versions, TikTok download source, steps to reproduce and relevant LSPosed logs. Remove personal information before sharing logs.

## Development

### Requirements

- Android Studio with JDK 21 to run Gradle (Java source/target: 17)
- Android SDK 37
- Android Gradle Plugin 9.4.0 and Gradle 9.7.1 (provided by the wrapper)

The app targets Android 35 and supports Android 9 (API 28) and newer. Dependencies are resolved from Google Maven and Maven Central.

### Build and test

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:lintRelease
./gradlew :app:assembleRelease
```

On Windows, use `gradlew.bat`. The main source sets are `app/src/main/java`, `app/src/main/res` and `app/src/test/java`. Release builds enable R8 and resource shrinking. With the local signing configuration present, the signed APK is written to `app/build/outputs/apk/release/app-release.apk`.

For your own signed build, copy [keystore.properties.example](keystore/keystore.properties.example) to `keystore/keystore.properties` and supply your own keystore and credentials. Without that file, the release APK is unsigned. Set `JAVA_HOME` or Android Studio's Gradle JDK locally; no machine-specific JDK path is committed.

The release keystore is intentionally kept outside version control. Keep `keystore/toki-release.jks` and `keystore/keystore.properties` private and backed up; losing this key prevents future updates from being installed over the same app. The current public certificate fingerprint is:

`SHA-256 74:09:5F:B8:C2:88:80:15:FC:DD:B9:3E:3C:14:F6:F6:3D:2A:EA:33:40:AB:5E:F9:6D:60:B0:C0:14:E1:6C:07`

Please run the unit tests and lint before submitting changes. Do not commit generated files from `app/build`.

## License

[MIT](LICENSE). Dependency credits and licenses are listed in [Third-party notices](THIRD_PARTY_NOTICES.md).

## Support

If Toki is useful to you, you can support development through [Ko-fi](https://ko-fi.com/meiyongai) or [Alipay](app/src/main/res/drawable-nodpi/alipay.jpg). These options are also available in Toki under **Support Toki**. Donations are optional.

<details>
<summary>USDT · TRC20 / Tron</summary>

`TXoTeZLpbQdn4wZF51858bC3zCwS822HbB`

Use the TRC20 (Tron) network only. Verify the address and network before sending.

</details>

## Disclaimer

Toki is an independent project, not affiliated with or endorsed by TikTok or ByteDance. It modifies app behavior and is provided **as is**, without warranties of compatibility, reliability or account safety. Use it at your own risk and follow applicable laws and platform terms. Respect creators' rights; download or reuse content only with permission.
