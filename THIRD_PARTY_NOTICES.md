# 第三方素材与许可（third-party notices）

本工程除 AndroidX / Material Components（Apache-2.0，走 Gradle 依赖）外，只有一处内嵌的第三方素材：

## 应用图标图形 —— Google Material Design Icons

| 项 | 内容 |
|---|---|
| 素材 | `photo_library`（filled / 黑色 24dp 矢量版） |
| 来源仓库 | https://github.com/google/material-design-icons |
| 出处文件 | `android/image/photo_library/materialicons/black/res/drawable/baseline_photo_library_24.xml`（另取 `src/image/photo_library/materialicons/24px.svg` 做渲染核对） |
| 许可 | **Apache License 2.0**（仓库根目录 `LICENSE`，副本见 `docs/third-party/material-design-icons-LICENSE.txt`） |
| 落位 | `app/src/main/res/drawable/ic_launcher_foreground.xml`、`ic_launcher_monochrome.xml`（同一 `pathData`，仅填充色不同） |
| 变换 | 图形墨迹在原 24 视口内占 `(2,2)-(22,22)`（**边长 20 的方形**）；按 `group scale=2.2 / translate=27.6` 居中缩放 → 墨迹 **44×44**，四角半径 `44/√2 = 31.1 < 33`（66 安全区的内切圆半径），四角不会被圆角遮罩切掉。尺寸依据见下节。 |
| 底板色 | `app/src/main/res/values{,-night}/colors_icon.xml` → `@android:color/system_accent1_100` / 夜间 `@android:color/system_accent2_800`（系统主题色，非第三方素材） |

### 尺寸依据（官方规范）

出处 `developer.android.com/develop/ui/views/launch/icon_design_adaptive`，原文：

1. 「将所有图层的大小调整为 **108x108 dp**。」
2. 「The outer **18 dp on each of the four sides** of the layers is reserved for masking and to create visual effects」⇒ 中间 **72×72** 方框。
3. 「The **66x66 safe zone** depicted is the area that is never clipped by a shaped mask defined by an OEM.」——66 是**方框边长**，真正永不被裁的是它的**内切圆，半径 33**。
4. 「Use a logo that's at least **48x48 dp**. It must not exceed **66x66 dp**, because the inner 66x66 dp of the icon appears within the masked [viewport].」——48 是清晰度下限，66 是"塞满可视范围"的上限。

⚠️ **教训**：第 4 条的 48/66 是给"铺满方框的图形"的；本图形是 **20×20 的正方形**，四角半径 = 边长/√2，所以边长上限是 `33×√2 ≈ 46.7`。v1.11 初版误按"最长边 66"缩放（scale 3.3），四角半径 46.7 ≫ 33 → 用户实测"图标过大，边角被裁掉"。现取 44（余量 2.2）。

对照表：边长 66→四角半径 46.7 ❌｜50→35.4 ❌｜48→33.9 ⚠️贴边｜**44→31.1 ✅（当前）**

Apache-2.0 要求保留版权与许可声明：原始 `LICENSE` 副本已随工程保存于 `docs/third-party/`。
