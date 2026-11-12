# LumaPhase Android

LumaPhase 的 Android 光学时间码显示端，在手机上显示 `OPTC-1` 图案并记录会话日志，供外部相机拍摄和时间关联。应用使用 Kotlin、Jetpack Compose 和 OpenGL ES，另提供基于 OpenCV 5.1.0 的手机相机识别测试；精细光学时间估计和多设备时钟拟合由 Python core 处理。

当前可构建 debug APK，支持新网格和原单排布局。应用由 DengQi 开发，处于设备验证阶段：编码、几何、会话逻辑和 APK 构建已验证，真实屏幕显示、相机识别和文件导出仍需真机验证。

## 安装 APK

运行要求为 **Android 13（API 33）或更高版本**。当前本机构建产物位于：

```text
app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

这是 debug 构建，版本为 `0.1.0`，包名为 `dev.lumaphase.android`。现在按 CPU 架构分别生成 APK，每个安装包只带本机所需的原生库。普通 ARM64 手机使用上面的 `arm64-v8a` 包，约 64.3 MB；同功能版本按架构分包相比四架构合包可缩小约 71%。同目录另有 `app-armeabi-v7a-debug.apk`、`app-x86-debug.apk` 和 `app-x86_64-debug.apk`，供对应架构设备使用；不要在 ARM 手机安装 x86 包。可通过 `adb shell getprop ro.product.cpu.abilist` 查看设备支持的架构。各架构构建已完成，尚未逐设备验证。APK 是本地构建产物，不随 Git 源码提交。

将 APK 传到手机后，可通过系统文件管理器安装。也可在电脑上使用 Android SDK Platform-Tools：先启用手机的 USB 调试，连接并在手机上授权电脑，然后在仓库根目录运行：

```sh
adb devices
adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
adb shell am start -n dev.lumaphase.android/.MainActivity
```

`adb devices` 应将目标手机列为 `device`；`unauthorized` 表示手机尚未授权。安装成功时 `adb install` 输出 `Success`，启动后应看到 `OPTC-1 pattern` 配置页。连接多台设备时，为每条 adb 命令加上 `-s <设备序列号>`。本次开发没有连接 Android 设备，以上安装和启动命令已对照包配置核查，尚未实际执行到手机。

## 选择布局

默认选择 **F68 大Tag · 9×8 / 6×12**。大 Tag 选项利用屏幕上下留白扩大四个定位标记，中间编码区、模块和填充格的实际矩形与对应普通网格完全相同。原网格和单排选项继续保留。网格保留原编码信息，把密集竖条重排为二维格子，以增加单个模块在相机画面中的可见宽度。能否识别仍取决于模块投影的宽、高、曝光和几何定位，不能只由手机屏幕分辨率判断。

| 界面选项 | 竖向 Surface，包括正方形 | 横向 Surface | 数据模块 / 填充格 |
| --- | --- | --- | --- |
| F68 大Tag · 9×8 / 6×12 | `F68-G9x8` | `F68-G6x12` | 68 / 4 |
| C52 大Tag · 9×6 / 5×12 | `C52-G9x6` | `C52-G5x12` | 52 / 2 或 8 |
| F68 grid · 9×8 / 6×12 | `F68-G9x8` | `F68-G6x12` | 68 / 4 |
| C52 grid · 9×6 / 5×12 | `C52-G9x6` | `C52-G5x12` | 52 / 2 或 8 |
| F68 · 1×68 | `F68` | `F68` | 68 / 0 |
| C52 · 1×52 | `C52` | `C52` | 52 / 0 |

方向按实际 Surface 的宽、高选择，图案的 `pattern_rotation_deg` 不改变这项选择。当前静态和动态预览 Activity 均锁定竖屏；横向分支是对实际横向绘制目标的支持，当前没有横屏切换按钮。图案旋转可选 0°、90°、180°、270°，与设备方向分开。

以 1080×2400 为例，0°/180° 的大 Tag 黑框边长从 126 px 增至 384 px，面积约为原来的 9.29 倍；90°/270° 为 288 px。具体尺寸由实际 Surface 和留白计算。大 Tag 与普通布局的 `geometry_profile` 可以相同，但标记坐标不同，不能混用参考预设或布局文件。

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

普通会话使用 schema `1.1`，CR-002 会话使用 `1.2`，光学协议仍是 `OPTC-1`。布局名称由 `geometry_profile` 表示，`layout_profile` 仍为 `F68` 或 `C52`，`layout_id` 标识不可变几何对象。导出矩形采用左上原点、整数像素半开边界，已经包含图案旋转；读取端按矩形采样，不再次旋转。完整字段见[共享数据契约](docs/cw/optc-1-contract.md)和 [CR-002 扩展](docs/cw/cr-002-contract.md)。

## CR-002 大 Tag 与显示诊断

配置页的 **CR-002 显示诊断** 提供三个独立会话入口。先选择原有布局和图案旋转，再选择入口；结束封存后返回配置页，通过 **Export session (six files)** 导出。

| 入口 | 显示与操作 |
| --- | --- |
| **TAG 横屏** | 切换并锁定横屏，在同一 Surface 显示 3 倍 Tag 和 0/128/255 参考区。外部相机定位核对完成后，点击画面进入原预热、开始卡、RUN 和结束卡。 |
| **A** | 白、黑、2×2 棋盘依次显示，每种先等待 1 s，再采样 3 s，共六段；应用只显示和记录，图像由外部相机采集。 |
| **B** | 连续 60 s VSync 黑白交替，已分配的 `native_frame_id` 偶数为白、奇数为黑；失败消耗 ID，不按最终确认顺序重新排列颜色。 |

普通 RUN 的“大Tag”选项已经放大标记，无需再点 **TAG 横屏**。该前置标定入口另要求将所选 RUN Tag 再放大 3 倍；使用它时请选择原普通网格，选大 Tag 网格可能因无法容纳而明确报错，不会自动换回小 Tag。

TAG 的模块尺寸固定为原 RUN Tag 的 3 倍，白静区至少一个放大后的模块。原竖屏几何无法容纳该倍率，因此这个会话全程使用横屏，普通竖屏入口继续保留。实际尺寸仍放不下时明确报错，不缩小倍率。确认后的尺寸或方向改变会重新显示大 Tag，要求再次点击确认，再恢复原阶段的剩余时长和连续序号。

点击表示操作者确认，不代表应用已经完成四路相机定位或 100 帧稳定性验证。大 Tag 和 A/B 诊断帧均为 `CALIBRATION`，不携带时间码，不消耗 `render_seq`。schema 1.2 用 `layout_type` 明确区分时间码、大 Tag 标定目标和全屏诊断；诊断画面不会导出虚构的时间码模块或标记。

这些入口已构建并检查会话逻辑，但尚未在设备上验证启动与横屏切换、真实 GL 像素、A/B 实际持续时间和六文件导出。颜色诊断用于取得实验数据，当前没有据此补偿粉色伪影。

## 手机相机识别测试

配置页选择 **打开相机取帧**，授予相机权限后使用后置相机预览。首次默认使用内置 Android 竖屏 F68 大 Tag 网格，可以直接扫描；在“内置参考预设”中选择与被测屏幕对应的布局和图案旋转。应用记住上次选择，下次打开后自动恢复并连续识别，无需逐张拍摄。离开页面或切后台会释放相机。没有后置相机的设备仍可安装和使用显示功能。

| 内置来源 | 参考尺寸 | 可选布局 |
| --- | --- | --- |
| Android 竖屏 | 1080×2400 | F68-G9x8／C52-G9x6 的大 Tag 与普通版本、原 F68／C52 单排 |
| Android 横屏 | 2400×1080 | F68-G6x12、C52-G5x12 |
| Mac | 1512×982 | F68-G6x12、C52-G5x12、原 F68／C52 单排 |

每个预设支持 0°、90°、180°、270° 图案旋转。这些是明确选择的参考几何，不是自动检测出的设备配置。被测设备的实际几何不同或预设识别不稳定时，选择 **导入 layout.json**，使用被测显示设备实际导出的文件；有多个布局时明确选择一个 `layout_id`。导入文件和内置选择分别保存，可以切换。

自动模式要求画面中同时出现四个 ArUco 标记（ID 0–3），显示定位框、低 16 位 payload 候选及 CRC／Runner 状态。绿色表示存在硬判决唯一且校验通过的带，黄色表示已定位但未通过，红色表示定位或校验失败；旧单排各薄带分别解码，不能把混合状态当作同一个稳定码字。镜头移开后，新分析结果会清除旧定位和 payload。

自动定位失败时，可选择 **拍摄一帧**，应用会冻结实际分析的灰度图并自动识别同一帧，显示 detected／missing IDs、ID 矩阵尺寸、图像尺寸和时间戳，便于核对检测输入。仍未定位时，再选择 **手动四角**，依次点击动态编码区域外框的左上、右上、右下、左下角；不要选择 ArUco 标记或整个屏幕外壳。点击黑边留白不会计入坐标。选择 **恢复预览** 返回连续自动识别。初始化或相机错误后，可使用 **重新打开相机** 显式恢复。

Tag 诊断分别显示四个预期 ID 的原始图像边长、最短边和最短边除以 6 的模块尺寸代理值。缺失标记的尺寸为空。60 px 边长和 8 px 模块尺寸只是远距实验参考，不作为识别硬门槛；透视下的代理值也不等于真实模块宽度。

固定手机和被测屏幕的位置后，可在冻结帧上选完手动四角，保存固定工站，再显式选择 **启用固定 RUN**，复用四角连续识别。保存后不会自动启用。工站绑定源布局完整几何、相机、帧尺寸、裁剪和方向；参数失配会停止固定 RUN，要求重新标定。手机或屏幕发生物理移动不会被自动发现，移动后必须重新选四角。这是本机私有的未标定平面映射，不是供 Python core 使用的 KB4 几何标定文件。

文件导入支持符合契约的 schema 1.0 单排、schema 1.1 单排和网格，以及 schema 1.2 的 `OPTC` 布局。大 Tag 标定目标和全屏诊断不能作为时间码布局导入；损坏或不完整的布局也会被拒绝。早期 Android 版本把内部图案区域误记为完整画布，其文件会被拒绝；请用本版本重新导出。应用不根据手机型号或标记 ID 猜测布局。

识别结果只包含低 16 位状态，不能直接得到完整 `render_seq`、同步误差或精细光学时间。当前采用未标定的平面单应近似，未处理镜头畸变和屏幕响应标定。新连续识别与比例修复尚未在真实设备上验证；编译和 JVM 测试不代表现场识别通过。

## 时间戳与验证范围

应用通过专用线程上的 VSync 回调生成动态状态，使用最小 JNI 桥查询 EGL 呈现时间戳。预计呈现时间、呈现请求和实际呈现证据分别记录；缺少实际时间不会用预计时间补齐。不支持、pending、invalid、查询失败和时钟域未核实分别保留状态。

EGL 原生时间值目前按 `CLOCK_DOMAIN_UNVERIFIED` 保存，标准实际时间字段保持为空；原生值保留在平台时间戳条目中。缺少实测屏幕扫描与亮暗响应标定时，这些记录不能直接给出精细光学曝光时间。

当前 JVM 测试结果为 198 项通过，已重新构建。ARM64 APK 约 64.3 MB，其余架构各自打包。检查覆盖共同布局案例、协议向量、全载荷解码、旧单排回归、会话逻辑、布局导入、灰度帧复制/旋转、预览矩阵合成、逐带采样/解码、Tag 尺寸诊断、固定工站参数绑定，以及 CR-002 几何、阶段与段关联、重新确认后的剩余时长。实际 Builder 的 24 个布局也已通过 Python core 的读取校验；该输入使用测试序列化器，未覆盖生产 `JsonWriter` 字节。尚未在 Android 设备上验证实际 GL 像素、EGL 属性与时间戳支持、120 Hz 请求效果、生命周期回调顺序、新版相机比例/连续识别、OpenCV JNI、固定工站恢复、SAF 导出，以及远距识别率和时序精度。

## 从源码构建

不需要 Android Studio。构建环境使用 JDK 21、Android 命令行 SDK 和仓库的 Gradle Wrapper；首次构建需要访问已配置的依赖仓库。OpenCV AAR 由 Git LFS 保存，克隆仓库前安装 Git LFS，克隆后运行 `git lfs pull` 取得本地依赖；之后构建不下载该 AAR。

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

构建成功后 APK 位于 `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`，测试结果位于 `app/build/test-results/testDebugUnitTest/`。仓库配置的 Gradle JVM 堆上限为 2 GiB，命令将 worker 数限制为 2。构建失败时先检查 `JAVA_HOME` 是否指向实际 JDK，以及 SDK 中是否已安装指定版本的 NDK/CMake。

## 文档与问题反馈

- [Android 设计](docs/cw/android-design.md)说明显示、时钟与平台边界。
- [CR-001 实施决定](docs/cw/cr-001-plan.md)说明网格格式和旧布局保留规则。
- [共同布局测试数据](docs/cw/fixtures/cr-001-layouts.json)提供跨平台几何及载荷预期。
- [实施记录](docs/cw/android-implementation-plan.md)保存检查结果和未验证项。

反馈设备问题时，附上手机型号、Android 版本、所选布局与旋转、会话摘要，以及可取得的六文件导出包。报告图案识别问题时，保留原始相机图像、曝光设置和屏幕在图像中的位置，便于区分几何定位、空间分辨率与显示过渡问题。

## 许可证

项目采用 [MIT License](LICENSE)。内置 ArUco 字典数据取自 OpenCV 4.10.0，来源和 Apache-2.0 许可说明见 [ArUcoDict4x4_50.kt](app/src/main/java/dev/lumaphase/android/layout/ArUcoDict4x4_50.kt)。

相机识别使用固定在 [third_party/opencv-5.1.0](third_party/opencv-5.1.0/SOURCE.md) 的官方 Android AAR，许可证为 Apache-2.0；构建直接使用本地文件。
