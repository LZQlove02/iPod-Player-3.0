# iPod 播放器 3.0 · iPod Player 3.0

[![License](https://img.shields.io/github/license/LZQlove02/iPod-Player-3.0)](LICENSE)
[![Release](https://img.shields.io/github/v/release/LZQlove02/iPod-Player-3.0?label=download)](https://github.com/LZQlove02/iPod-Player-3.0/releases/latest)
![Platform](https://img.shields.io/badge/platform-Android%208.0%2B%20(API%2026)-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-7F52FF?logo=kotlin&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-BOM%202024.10.01-4285F4?logo=android&logoColor=white)
![Media3](https://img.shields.io/badge/Media3-1.4.1-2D2D2D)
![AGP](https://img.shields.io/badge/Gradle-AGP%208.7.3-green?logo=gradle&logoColor=white)

个人自用 · Android 本地音乐 · iPod Classic 6/7 交互仿妆。

> **English** — A personal-use **Android local music player** that recreates the iPod Classic
> 6th/7th-gen experience: full device chrome (LCD + Click Wheel), Cover Flow 3D, gesture-driven
> wheel, 10-band graphic EQ with savable curves, LRC lyrics, local JSON playlists, notification
> controls and a 中/EN interface. Kotlin · Jetpack Compose · Media3, MIT licensed.

## 需求摘要

| 项 | 设定 |
|---|---|
| 平台 | Android 8.0+（API 26） |
| 技术 | Kotlin · Jetpack Compose · Media3 |
| 形态 | 完整机身（上 LCD + 下 Click Wheel） |
| 曲源 | 仅本地 MediaStore 音乐 |
| 配色 | **银色默认**，可切换黑色 / U2 黑红（配色集中在 `ui/theme/Theme.kt`） |
| 歌词 | 音频旁同名 `.lrc`（UTF-8 / GBK / UTF-16 自动识别，支持 `[offset:]`） |
| 播放列表 | 本地 JSON，可新建 / 加歌 / 移除歌曲 / 重命名 / 删除 |
| 其它 | Cover Flow 3D · 10 段均衡器（曲线可保存） · 通知栏控制 · 中/英界面 |

## 下载安装

到 [Releases](https://github.com/LZQlove02/iPod-Player-3.0/releases/latest) 下载
`iPod-Player-3.0-v3.0.0-release.apk`（Android 8.0+），允许「未知来源」后安装即可。

发布包由**独立 release 密钥**签名（证书 `CN=LZQlove02, OU=iPodPlayer3`，指纹见 Release 说明），
**不是** debug 签名，可直接覆盖升级；SHA256 也写在对应 Release 说明里。
如果你装过更早的 debug 签名包，签名不同**无法覆盖**，先卸载再装。

## 用 Android Studio 打开

1. 打开 Android Studio → **Open** → 选择本目录（`iPod播放器3.0`）
2. 等待 Gradle Sync（首次会拉依赖）
3. 连接手机（开启 USB 调试）→ Run

命令行编译（`JAVA_HOME` 必须指向**完整 JDK**，不是 JRE）：

```bat
gradlew.bat :app:assembleDebug      :: 产物 app\build\outputs\apk\debug\app-debug.apk
gradlew.bat :app:assembleRelease    :: 产物 app\build\outputs\apk\release\app-release.apk（R8 混淆 + 资源压缩）
gradlew.bat :app:testDebugUnitTest  :: 单元测试
```

> - 项目路径含中文，已在 `gradle.properties` 中设置 `android.overridePathCheck=true`。
> - `gradle.properties` 里**不要**加 `-Dfile.encoding=UTF-8`：守护进程的默认编码决定
>   Gradle 写「@argfile」（命令行过长时的参数文件）用哪种编码，而 JVM 读 @argfile 用
>   系统本地编码。一旦写成 UTF-8，含中文的路径（用户目录 / 项目路径）在参数文件里就会
>   乱码，单元测试 worker 会直接 `ClassNotFoundException: GradleWorkerMain`。
> - release 签名：仓库**不含**密钥；根目录没有 `keystore.properties` 时自动回退到 debug 签名（保证能装）。
>   正式签名请把密钥库放在**仓库外**（下例 `../ipod-release.jks`，天然不会被 git 跟踪），再在根目录写
>   `keystore.properties`（`storeFile` / `storePassword` / `keyAlias` / `keyPassword`，已被 `.gitignore` 忽略）：

```properties
storeFile=../ipod-release.jks
storePassword=******
keyAlias=ipod
keyPassword=******
```

## 圆盘手势

| 操作 | 行为 |
|---|---|
| 绕圈滑动 | 列表滚动 / Cover Flow 翻页；正在播放/歌词页无极快进退（约 22s/圈） |
| 中心 | 确认 / 翻开 Cover Flow；播放列表详情页顶部四行是「添加歌曲 / 移除歌曲 / 重命名 / 删除」 |
| MENU（或系统返回键） | 返回 |
| ▶ + 双竖线 | 播放/暂停 |
| ⏭ / ⏮ | 切歌（在列表中则滚动） |

> 读屏（TalkBack）可用：滚轮挂了 5 个自定义操作（返回 / 播放暂停 / 上一项 / 下一项 / 确认）。

## 播放列表

音乐 → **加入播放列表** → 选歌 → 选列表（第 0 项「新建并加入」）。
进入某个列表后，顶部四行分别是「添加歌曲」「从列表移除歌曲」「重命名播放列表」「删除播放列表」；「添加歌曲」会打开全曲库选歌页，每确认一次加入一首，可连续添加。

## 歌词

把 `Song Title.lrc` 放在音频同目录（或 `lyrics/` 子目录），在「正在播放」按确认进入歌词页。
Android 10+ 的分区存储读不到 `.lrc`（它不是媒体类型），需要在「设置 → 歌词文件夹」里授予一个目录。

## 目录结构

```text
app/src/main/java/com/ipodplayer3/app/
  data/     模型、曲库（含纯计算 LibraryAggregator）、歌词、播放列表、Media3 播放服务 + 10 段 EQ
  ui/       主题、圆盘、机身、Cover Flow、设置、文案（从 res/values 取）
  MainActivity.kt
app/src/main/res/values/strings.xml     中文（默认）
app/src/main/res/values-en/strings.xml  英文
app/src/test/                           单元测试（JVM）
docs/                                   静态预览页 index.html / coverflow.html、优化清单.md
```

> `docs/index.html` 与 `docs/coverflow.html` 是纯静态界面预览，浏览器直接打开即可看机身与 Cover Flow 效果，
> 不需要装 Android Studio。

## 说明

- 自研实现；交互参考公开项目（MIT）的圆盘思路，**未拷贝** BSD/AGPL 源码。
- 均衡器是自实现的 10 段 GraphicEQ（Media3 AudioProcessor），预设 + 逐段调节，曲线随设置保存；
  平台 `Equalizer/BassBoost/Virtualizer` 仅为兼容性挂载且保持关闭，避免二次染色。
- 已知限制：内置存储 + SD 卡同时有音乐时，播放 URI 仍按主卷拼装（`_ID` 只在单卷内唯一）；
  双卷设备需要按 `VOLUME_NAME` 逐行拼 URI，见 [docs/优化清单.md](docs/优化清单.md)。

## 开源协议

[MIT](LICENSE) © LZQlove02
