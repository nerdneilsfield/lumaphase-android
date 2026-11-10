# LumaPhase Android

LumaPhase 的 Android 光学时间码显示端，在手机上显示 `OPTC-1` 图案并记录会话日志，供外部相机拍摄和时间关联。应用使用 Kotlin、Jetpack Compose 和 OpenGL ES，另提供手机相机采集测试入口；完整光学识别和多设备时钟拟合由 Python core 处理。

当前可构建 debug APK，支持新网格和原单排布局。应用由 DengQi 开发，处于设备验证阶段：编码、几何、会话逻辑和 APK 构建已验证，真实屏幕显示、相机识别和文件导出仍需真机验证。

## 安装 APK

运行要求为 **Android 13（API 33）或更高版本**。当前本机构建产物位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

这是 debug 构建，版本为 `0.1.0`，包名为 `dev.lumaphase.android`。同一 APK 包含 `arm64-v8a`、`armeabi-v7a`、`x86` 和 `x86_64` 原生库；这些 ABI 已编译打包，尚未逐设备验证。APK 是本地构建产物，不随 Git 源码提交。

将 APK 传到手机后，可通过系统文件管理器安装。也可在电脑上使用 Android SDK Platform-Tools：先启用手机的 USB 调试，连接并在手机上授权电脑，然后在仓库根目录运行：

```sh
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.lumaphase.android/.MainActivity
```

`adb devices` 应将目标手机列为 `device`；`unauthorized` 表示手机尚未授权。安装成功时 `adb install` 输出 `Success`，启动后应看到 `OPTC-1 pattern` 配置页。连接多台设备时，为每条 adb 命令加上 `-s <设备序列号>`。本次开发没有连接 Android 设备，以上安装和启动命令已对照包配置核查，尚未实际执行到手机。

## 选择布局

默认选择 **F68 grid · 9×8 / 6×12**。网格保留原编码信息，把密集竖条重排为二维格子，以增加单个模块在相机画面中的可见宽度。能否识别仍取决于模块投影的宽、高、曝光和几何定位，不能只由手机屏幕分辨率判断。

| 界面选项 | 竖向 Surface，包括正方形 | 横向 Surface | 数据模块 / 填充格 |
| --- | --- | --- | --- |
| F68 grid · 9×8 / 6×12 | `F68-G9x8` | `F68-G6x12` | 68 / 4 |
| C52 grid · 9×6 / 5×12 | `C52-G9x6` | `C52-G5x12` | 52 / 2 或 8 |
| F68 · 1×68 | `F68` | `F68` | 68 / 0 |
| C52 · 1×52 | `C52` | `C52` | 52 / 0 |

方向按实际 Surface 的宽、高选择，图案的 `pattern_rotation_deg` 不改变这项选择。当前静态和动态预览 Activity 均锁定竖屏；横向分支是对实际横向绘制目标的支持，当前没有横屏切换按钮。图案旋转可选 0°、90°、180°、270°，与设备方向分开。

`F68` 保留亮度参考、16 位 Gray Code、CRC 和 Runner；`C52` 去掉 Runner，保留前 52 个模块。网格使用同一编码数组按行放置，互补模块对不跨逻辑行。末尾填充格交替使用低、高码值，默认分别为 32、224，不参与 Gray、CRC 或 Runner 解码。原四角 ArUco 字典与 ID 保持不变。

## 查看图案和导出布局

1. 在配置页选择布局、`pattern_rotation_deg` 和静态 `render_seq`。序号输入为无符号 64 位十进制整数。
2. 选择 **Open the static optical pattern**，查看真实 Surface 绘制的固定图案，再通过系统返回操作回到配置页。
3. 选择 **Export layout.json**，通过系统文件选择器保存实际预览的布局快照。

`Export layout.json` 在取得已绘制快照前不可用；更改布局或旋转会清除旧快照，需要重新打开静态图案。导出记录的是实际 Surface 像素矩形，不按手机型号或窗口尺寸估算。

只想查看连续变化的图案时，选择 **Open the pattern without a session**。这个入口不记录会话，也不产生六文件导出包。配置页的静态 `render_seq` 输入不设置正式会话的起始序号；新会话的动态序号从 0 开始。

## 运行并导出会话

1. 选择布局和图案旋转，选择 **Start session (A120-S1)**。
2. 保持应用在前台，等待默认阶段依次运行。
3. 结束并封存后，通过系统返回操作回到配置页，查看摘要。提前返回会以 `USER_STOP` 结束会话。
4. 选择 **Export session (six files)**，在系统目录选择器中选择目标目录。应用在其下创建 `session_<uuid>/` 并复制六个文件。

| 阶段 | 默认时长 | 显示内容 |
| --- | --- | --- |
| `WARMUP` | 10 s | 预热卡 |
| `SESSION_CARD` | 2 s | 开始卡与会话身份 |
| `RUNNING` | 90 s | 动态光学时间码 |
| `END_CARD` | 2 s | 结束卡与会话身份 |
| 排空与封存 | 最多 2 s | 停止提交新帧，继续查询已提交帧的结果 |

应用请求 120 Hz，仅允许无缝刷新率切换；请求值不等于面板实际刷新率。默认计划不含后台停留时间。切后台会停止提交并结束当前段，恢复后使用新段，动态序号继续递增。活动会话状态保存在进程内，进程被系统终止后的恢复不属于持久化会话恢复。

图案页隐藏系统栏并保持屏幕常亮。会话结束后不会自动跳回配置页，需要系统返回操作。会话摘要与可导出目录在配置页内存中保存，建议返回后立即导出；当前没有历史会话浏览入口。

只有成功封存并返回可导出目录后，导出按钮才可用。日志队列溢出会停止新动态状态并标记 `INCOMPLETE`；文件写入或关闭失败会阻止应用提供成功导出入口。摘要中的 `data_integrity` 表示记录完整性，不表示所有帧都取得了有效呈现时间。

导出目录包含：

| 文件 | 内容 |
| --- | --- |
| `manifest.json` | 会话、设备、时间原点与分段配置。 |
| `layout.json` | 各段引用的不可变布局、模块及填充格像素矩形。 |
| `display_events.jsonl` | 帧提交、呈现或未确认结果及逐字段时间状态。 |
| `configuration_events.jsonl` | 阶段、分段、日志溢出、渲染失败和关闭事件。 |
| `display_profile.json` | 标定集合及分段绑定；未提供标定时集合为空、绑定为 `MISSING`。 |
| `session_summary.json` | 各段帧计数、未确认原因和数据完整性。 |

当前新会话使用 schema `1.1`，光学协议仍是 `OPTC-1`。布局名称由 `geometry_profile` 表示，`layout_profile` 仍为 `F68` 或 `C52`，`layout_id` 标识不可变几何对象。导出矩形采用左上原点、整数像素半开边界，已经包含图案旋转；读取端按矩形采样，不再次旋转。完整字段见[共享数据契约](docs/cw/optc-1-contract.md)。

## 手机相机采集测试

配置页选择 **Open the camera capture test**，授予相机权限后使用后置相机预览。选择 **Capture one frame** 显示冻结灰度图，选择 **Resume preview** 返回预览；离开页面或切后台会释放相机。没有后置相机的设备仍可安装和使用显示功能。

选择 **Import layout.json** 导入被测显示设备的实际布局文件；文件有多个布局时，通过 `layout_id` 选项明确选择。本页支持符合契约的 schema 1.0 单排和 schema 1.1 单排、网格，拒绝损坏或不完整的布局。早期 Android 版本把内部图案区域误记为完整画布，其文件会被拒绝；请用本版本重新导出。

本页目前只提供预览、冻结帧和布局导入，尚未接入自动 ArUco、手动四角定位或图像解码结果。纯 Kotlin payload 解码器已有单元测试，但还未连接相机页；不能用本页判断 CRC、同步误差或光学时间精度。相机页尚未在真实设备上运行验证。

## 时间戳与验证范围

应用通过专用线程上的 VSync 回调生成动态状态，使用最小 JNI 桥查询 EGL 呈现时间戳。预计呈现时间、呈现请求和实际呈现证据分别记录；缺少实际时间不会用预计时间补齐。不支持、pending、invalid、查询失败和时钟域未核实分别保留状态。

EGL 原生时间值目前按 `CLOCK_DOMAIN_UNVERIFIED` 保存，标准实际时间字段保持为空；原生值保留在平台时间戳条目中。缺少实测屏幕扫描与亮暗响应标定时，这些记录不能直接给出精细光学曝光时间。

当前 JVM 测试结果为 121 项通过，APK 包含四 ABI 原生库。检查覆盖共同布局案例、协议向量、全载荷解码、旧单排回归、会话逻辑、布局导入和灰度帧复制/旋转。实际 Builder 的 24 个布局也已通过 Python core 的读取校验；该输入使用测试序列化器，未覆盖生产 `JsonWriter` 字节。尚未在 Android 设备上验证实际 GL 像素、EGL 属性与时间戳支持、120 Hz 请求效果、生命周期回调顺序、相机预览/取帧、SAF 导出，以及远距识别率和时序精度。

## 从源码构建

不需要 Android Studio。当前已验证的构建环境使用 JDK 21、Android 命令行 SDK 和仓库的 Gradle Wrapper；首次构建需要访问已配置的依赖仓库。

| 工具 | 项目配置 |
| --- | --- |
| Gradle / Android Gradle Plugin | 8.13 / 8.13.2 |
| Kotlin / Compose compiler | 2.2.21 |
| Android SDK | compile / target API 36；min API 33 |
| NDK | 28.2.13676358 |
| CMake | 3.22.1 |

在 Android SDK 管理工具中准备 API 36 平台、Android SDK Build-Tools、Platform-Tools、上述 NDK 和 CMake。将下面两个路径替换为本机 JDK 21 与 SDK 根目录；macOS Homebrew 安装环境中，本项目已验证的路径分别为 `/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home` 和 `/opt/homebrew/share/android-commandlinetools`。

```sh
export JAVA_HOME="/path/to/jdk-21/Contents/Home"
export ANDROID_HOME="/path/to/android-sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
./gradlew :app:testDebugUnitTest :app:assembleDebug --max-workers=2
```

构建成功后 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`，测试结果位于 `app/build/test-results/testDebugUnitTest/`。仓库配置的 Gradle JVM 堆上限为 2 GiB，命令将 worker 数限制为 2。构建失败时先检查 `JAVA_HOME` 是否指向实际 JDK，以及 SDK 中是否已安装指定版本的 NDK/CMake。

## 文档与问题反馈

- [Android 设计](docs/cw/android-design.md)说明显示、时钟与平台边界。
- [CR-001 实施决定](docs/cw/cr-001-plan.md)说明网格格式和旧布局保留规则。
- [共同布局测试数据](docs/cw/fixtures/cr-001-layouts.json)提供跨平台几何及载荷预期。
- [实施记录](docs/cw/android-implementation-plan.md)保存检查结果和未验证项。

反馈设备问题时，附上手机型号、Android 版本、所选布局与旋转、会话摘要，以及可取得的六文件导出包。报告图案识别问题时，保留原始相机图像、曝光设置和屏幕在图像中的位置，便于区分几何定位、空间分辨率与显示过渡问题。

## 许可证

项目采用 [MIT License](LICENSE)。内置 ArUco 字典数据取自 OpenCV 4.10.0，来源和 Apache-2.0 许可说明见 [ArUcoDict4x4_50.kt](app/src/main/java/dev/lumaphase/android/layout/ArUcoDict4x4_50.kt)。
