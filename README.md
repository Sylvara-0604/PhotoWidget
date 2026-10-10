# PhotoWidget

A minimal home-screen photo widget for Android — an independent, from-scratch implementation in **Kotlin + Jetpack Compose (Material 3 Expressive)**.

It puts one or more photos from your library onto the home screen as a card with a soft shadow, a themed background plate and the photo itself. Nothing else: no feeds, no accounts, no network permission.

> The concept comes from the AOSP `Gallery2` home-screen photo widget. This project is an independent, modern re-implementation — no Gallery2 code or resources were carried over. See [Provenance](#provenance-and-affiliation).

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

## Tech stack

| Area | What is used |
|---|---|
| Language | **Kotlin** (Kotlin support is built into AGP 9; the Compose compiler plugin is `2.4.20`) |
| UI | **Jetpack Compose** (Compose BOM `2026.09.00`) + Compose Material 3. XML themes come from **Material Components `1.14.0`** (`Theme.Material3Expressive.*`) for the widget host and the configuration dialog, which cannot be Compose. |
| Widget runtime | Classic **`AppWidgetProvider` + `RemoteViews`**, with **`StackView`** as the gesture host and a **`RemoteViewsService` / `RemoteViewsFactory`** for lazy paging |
| Images | `ContentResolver.loadThumbnail()` fast path, **`ImageDecoder`** full-resolution fallback, two-level in-memory `LruCache` |
| Async | **Kotlin Coroutines** (`kotlinx-coroutines-android 1.9.0`) |
| Build | **Gradle 9.8.0** (wrapper) + **Android Gradle Plugin 9.4.1**; `compileSdk 37` (minor 2) / `minSdk 33` / `targetSdk 37`, Java 17 bytecode |
| Release packaging | **R8** code shrinking + obfuscation and **resource shrinking** |
| Architecture | Plain provider / service / renderer split. No DI framework, no network permission, no analytics, no third-party SDKs. |

AndroidX dependencies in use: `core-ktx`, `activity-compose`, `lifecycle-*`, `fragment-ktx`, `recyclerview`, `viewpager2`, `appcompat`, `constraintlayout` — all Apache-2.0, resolved from Google Maven / Maven Central.

## Getting the source

```bash
git clone https://github.com/Sylvara-0604/PhotoWidget.git
cd PhotoWidget
```

## Build

### Prerequisites

- **JDK 17** or newer
- **Android SDK** with **Platform 37 (minor 2)** and matching build-tools installed
- Tell Gradle where the SDK is: either create `local.properties` with `sdk.dir=/absolute/path/to/android-sdk`, or set the `ANDROID_HOME` environment variable

### Commands

```bash
./gradlew assembleDebug     # signed with the standard Android debug key
./gradlew assembleRelease   # unsigned unless a local keystore is configured — see below
```

The first run downloads the Gradle 9.8.0 distribution and the dependency set. Only `arm64-v8a` native code is packaged (`abiFilters`); to build for other ABIs, edit `abiFilters` in `app/build.gradle.kts`.

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

## License and legal notes

### License

[**Apache License 2.0**](LICENSE) · Copyright 2026 Sylvara-0604.

You may use, modify, and redistribute this project, including commercially, provided you honour the conditions in the following sections.

### Patent grant

Apache-2.0 §3 gives every recipient a **perpetual, worldwide, royalty-free patent licence** from the contributors, covering patents necessarily infringed by their contributions. If you file patent litigation alleging that this project infringes a patent you hold, your patent licence terminates.

Scope note: this covers **contributors' own** contributions only. The project makes no representation about any third-party patent, and nothing here is legal advice.

### If you fork or redistribute — what you must do

Apache-2.0 §4 requires you to:

1. keep the [`LICENSE`](LICENSE) file,
2. keep this [`NOTICE`](NOTICE) and [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md),
3. mark any files you changed as changed, and
4. not use the project's name or trademarks in a way that implies endorsement of your fork.

### Provenance and affiliation

- This is an **independent re-implementation**. The *idea* of a home-screen photo widget of this shape comes from the AOSP [`Gallery2`](https://android.googlesource.com/platform/packages/apps/Gallery2/) app, which is Apache-2.0. **No Gallery2 source file, layout, drawable, string or other resource was copied into this repository** — the widget, the Compose UI, the sizing logic and the renderer were written from scratch for this project.
- This project is **not affiliated with, endorsed by, or sponsored by Google LLC**, the Android Open Source Project, or any device manufacturer.
- "Android", "Google" and "Material Design" are trademarks of Google LLC. They appear here **nominatively** — to state what the software is compatible with — and do not imply any endorsement.

### Application ID

The application ID is `com.android.gallery3d.photowidget`, derived from the ID of the stock app the concept comes from. Note that the **`com.android.*` namespace is reserved**: Google Play rejects application IDs beginning with `com.android.`, and some ROMs treat that prefix specially. If you plan to distribute this app on a store or integrate it into a ROM, change `applicationId` **and** `namespace` in `app/build.gradle.kts` to your own reverse-domain ID first.

### Third-party components

- **App icon shape** — `photo_library` from [Google Material Design Icons](https://github.com/google/material-design-icons), Apache-2.0. Exact file of origin, transform applied, and the icon-sizing rationale are documented in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md); the upstream licence copy is kept at [`docs/third-party/material-design-icons-LICENSE.txt`](docs/third-party/material-design-icons-LICENSE.txt). This icon is the only embedded third-party asset in the project.
- **Libraries** — AndroidX / Jetpack, Kotlin, kotlinx.coroutines and Material Components, all Apache-2.0, resolved as build dependencies from Google Maven and Maven Central. No third-party binaries are vendored into this repository.
- **No bundled media** — there are no stock photos, fonts, sounds or sample images in the repository or in the APK.
- **Permissions** — the app requests only photo read access (`READ_MEDIA_IMAGES`, `READ_MEDIA_VISUAL_USER_SELECTED`). It has **no `INTERNET` permission**, so it cannot send anything anywhere.

### No warranty

The software is provided **"AS IS", without warranty of any kind**, express or implied, including but not limited to the warranties of merchantability, fitness for a particular purpose and non-infringement. See [`LICENSE`](LICENSE) §7–8 for the full disclaimer.

---

## 中文说明

Android 桌面图片小部件，用 **Kotlin + Jetpack Compose（Material 3 Expressive）** 独立重写。三种形态：相册（滑动切图）、单图、随机全部。不自动翻页，只有你操作才换图。缩略图先出、后台解清晰版替换；卡片位图按小部件实例的真实像素尺寸生成，1:1 不与显示错位。卡片底板色跟随系统主题图标底色，应用图标含单色主题层，release 包经 R8 与资源压缩后约 2.6 MB。

**要求**：Android 13+ / arm64-v8a。从 [Releases](https://github.com/Sylvara-0604/PhotoWidget/releases) 下载 APK 安装即可（开发密钥签名，Play 保护机制可能提示风险，属正常）。

**拉取与构建**：

```bash
git clone https://github.com/Sylvara-0604/PhotoWidget.git
cd PhotoWidget
./gradlew assembleDebug      # 用 Android 标准调试密钥签名
./gradlew assembleRelease    # 未配置本地 keystore 时为未签名
```

需要 JDK 17+ 与 Android SDK Platform 37（minor 2）；SDK 路径写在 `local.properties` 的 `sdk.dir` 或环境变量 `ANDROID_HOME`。release 签名走工程根 `keystore.properties`（已 gitignore，不入库）。

**法律与来源**：
- 本工程以 **Apache License 2.0** 授权（含 §3 专利授权），Copyright 2026 Sylvara-0604；复制、修改、再分发需保留 `LICENSE`、`NOTICE`、`THIRD_PARTY_NOTICES.md`，并标注你所修改的文件。
- **独立重写**：小部件的构想源自 AOSP `Gallery2`，但**未复制其任何源码或资源**；与 Google LLC、AOSP 及任何设备厂商**无隶属或背书关系**；"Android"/"Google"/"Material Design" 为 Google LLC 商标，此处仅作指称性使用。
- 唯一内嵌第三方素材是应用图标图形（Google Material Design Icons `photo_library`，Apache-2.0），出处与变换过程见 `THIRD_PARTY_NOTICES.md`。工程内无第三方二进制、无自带图片/字体，APK **不含 INTERNET 权限**。
- 注意：`com.android.*` 是保留命名空间（Google Play 会拒绝以此开头的 applicationId），若要上架或集成进 ROM，请先把 `applicationId` 与 `namespace` 改成自己的域名反转。
