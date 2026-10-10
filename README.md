# PhotoWidget

A minimal home-screen photo widget for Android — rewritten from scratch in **Kotlin + Jetpack Compose (Material 3 Expressive)**.

It puts one or more photos from your library onto the home screen as a card with a soft shadow, a themed background plate and the photo itself. Nothing else: no feeds, no accounts, no network permission.

> The concept comes from the AOSP `Gallery2` home-screen photo widget. This project is an independent, modern re-implementation — no code or resources were carried over.

## Features

| | |
|---|---|
| **Three modes** | *Album* (swipe through one album), *Single image*, *Shuffle all images* |
| **User-driven only** | No auto-advance. The photo changes when you swipe, or tap *Next*. |
| **Sharp by default** | The system thumbnail is shown first, then a full-resolution decode is baked in the background and swapped in. Covers/letters are never upscaled. |
| **Pixel-accurate sizing** | The card bitmap is generated at the widget instance's real pixel size (read from `AppWidgetManager` options) — 1:1 with what the launcher draws. |
| **Themed card background** | The plate colour is the system themed-icon background (`@android:color/system_accent1_100`, dark: `system_accent2_800`) — it follows the device wallpaper palette. |
| **Themed app icon** | Adaptive icon with a `<monochrome>` layer for Android 13+ themed icons. |
| **Small** | R8 + resource shrinking: the release APK is ~2.6 MB. |

## Requirements

- Android **13 (API 33)** or newer
- **arm64-v8a** devices only (the APK is not built for other ABIs)
- Home-screen launcher with App Widget support (any standard launcher)

## Install

Download the APK from the [Releases](https://github.com/Sylvara-0604/PhotoWidget/releases) page and install it.

The APK is signed with a development key, so Play Protect may show a warning — that is expected for a self-built side-loaded app. If you previously installed a build signed with a different key, uninstall it first (`adb uninstall com.android.gallery3d.photowidget`); note that removing the app also removes widgets already placed on your home screen.

## Build

```bash
./gradlew assembleDebug     # signed with the standard Android debug key
./gradlew assembleRelease   # see "Signing" below
```

You need a JDK 17+ and the Android SDK (`compileSdk 37`, `minSdk 33`). Point Gradle at it through the usual `local.properties` (`sdk.dir=...`) or `ANDROID_HOME`.

### Signing

`release` builds are **unsigned** unless a `keystore.properties` file exists in the project root:

```properties
storeFile=/absolute/path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

When present, **both `debug` and `release`** are signed with that key, so the two build types can overwrite each other with `adb install -r`. `keystore.properties` is git-ignored — never commit it.

## Project layout

```
app/src/main/java/com/android/gallery3d/photowidget/
├── MainActivity.kt                 Compose home screen (permission state, widget count)
├── config/WidgetConfigActivity.kt  Widget setup flow (album picker / image picker / shuffle)
├── data/
│   ├── MediaRepository.kt          MediaStore queries
│   └── ThumbnailLoader.kt          Two-level bitmap cache + decode
├── ui/theme/                       Material 3 theme
└── widget/
    ├── PhotoWidgetProvider.kt      AppWidgetProvider entry point
    ├── PhotoWidgetService.kt       RemoteViewsService (lazy RemoteViewsFactory)
    ├── CardRenderer.kt             Draws shadow → plate → photo into one bitmap
    ├── WidgetSizing.kt             Resolves the real pixel size of a widget instance
    ├── WidgetConfig.kt / WidgetConfigStore.kt / WidgetKind.kt
    ├── MediaContentObserver.kt     Refreshes the widget when the library changes
    └── PhotoOpenActivity.kt        Transparent trampoline that opens the photo in a viewer
```

## Third-party

Only one embedded asset: the app icon shape is `photo_library` from [Google Material Design Icons](https://github.com/google/material-design-icons) (Apache-2.0). See [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

## License

[Apache License 2.0](LICENSE).

---

## 中文说明

Android 桌面图片小部件，用 Kotlin + Compose（Material 3 Expressive）从零重写。三种形态：相册（滑动切图）、单图、随机全部。不自动翻页，只有你操作才换图。缩略图先出、后台解清晰版替换；卡片位图按小部件实例的真实像素尺寸生成，1:1 不与显示错位。卡片底板色跟随系统主题图标底色，应用图标含单色主题层，release 包经 R8 与资源压缩后约 2.6 MB。

要求 Android 13+ / arm64-v8a。从 [Releases](https://github.com/Sylvara-0604/PhotoWidget/releases) 下载 APK 安装即可（开发密钥签名，Play 保护机制可能提示风险，属正常）。
