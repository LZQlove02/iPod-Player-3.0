# iPod 播放器 3.0

个人自用 · Android 本地音乐 · iPod Classic 6/7 交互仿妆。

## 需求摘要

| 项 | 设定 |
|---|---|
| 平台 | Android 8.0+（API 26） |
| 技术 | Kotlin · Jetpack Compose · Media3 |
| 形态 | 完整机身（上 LCD + 下 Click Wheel） |
| 曲源 | 仅本地 MediaStore 音乐 |
| 配色 | **银色默认**，可切换黑色 / U2 黑红 |
| 歌词 | 音频旁同名 `.lrc`，随进度滚动 |
| 播放列表 | 本地可编辑（JSON） |
| 其它 | Cover Flow 3D · 均衡器预设 · 通知栏控制 · 中/英界面 |

## 用 Android Studio 打开

1. 打开 Android Studio → **Open** → 选择本目录（`iPod播放器3.0`）
2. 等待 Gradle Sync（首次会拉依赖）
3. 连接手机（开启 USB 调试）→ Run

命令行编译：

```bat
gradlew.bat :app:assembleDebug
```

产物：`app\build\outputs\apk\debug\app-debug.apk`

> 项目路径含中文时，已在 `gradle.properties` 中设置 `android.overridePathCheck=true`。

## 圆盘手势

| 操作 | 行为 |
|---|---|
| 绕圈滑动 | 列表滚动 / Cover Flow 翻页 / 正在播放快进退 |
| 中心 | 确认 / 翻开 Cover Flow |
| MENU | 返回 |
| ▶❙❙ | 播放/暂停 |
| ⏭ / ⏮ | 切歌（在列表中则滚动） |

## 歌词

把 `Song Title.lrc` 放在音频同目录（或 `lyrics/` 子目录），在「正在播放」按确认进入歌词页。

## 目录结构

```text
app/src/main/java/com/ipodplayer3/app/
  data/     模型、曲库、歌词、播放列表、Media3 播放服务
  ui/       主题、圆盘、机身、Cover Flow、设置、文案
  MainActivity.kt
```

## 说明

- 自研实现；交互参考公开项目（MIT）的圆盘思路，**未拷贝** BSD/AGPL 源码。
- 均衡器为预设 + 低音/高音简单调节（设备音效能力可能有差异）。
