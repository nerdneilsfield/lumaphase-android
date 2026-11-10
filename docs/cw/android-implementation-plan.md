# Android 显示端实施计划

目标：实现可由命令行 Gradle 构建的 Kotlin/Jetpack Compose Android 应用，生成 OPTC-1 图案并导出可追溯的显示会话。用户已授权开发、使用命令行 Android SDK，不安装 Android Studio。

设计依据：[Android 设计](android-design.md)、[共享契约](optc-1-contract.md)。当前没有应用源码，现有 `docs/` 和 `.cw/` 保留。

## 实现约束与决定

采用单一 `app` 模块，minSdk 33，包名和 applicationId 使用 `dev.lumaphase.android`。Compose 只负责配置、预览入口和会话操作；光学图案的调度由专用线程上的 Choreographer 驱动。Kotlin 管理 EGL14/GLES 渲染及 `EGLExt.eglPresentationTimeANDROID`。实际帧时间戳扩展缺公开 Java 绑定时，仅增加一个原生桥文件，使用 `eglGetProcAddress` 查询扩展入口，不用猜测动态库导出或 Java 私有句柄。

标准主机采样使用 `System.nanoTime()`，与 FrameTimeline 时间基一致。契约的 `ANDROID_MONOTONIC` 为时间域标签，`time_origin.native_clock` 记录实际采样 API；不能改用含休眠时间的 elapsedRealtime。EGL 字段时钟域未经核实时保留原生值并标记 `CLOCK_DOMAIN_UNVERIFIED`。

工具链采用经过官方资料核查的固定兼容组合。起点为 AGP 8.13.2、Gradle 8.13、Kotlin 与 Compose compiler 2.2.21、compileSdk/targetSdk 36；实现者先核实这些版本及所选 Compose BOM 存在且兼容，再锁定。构建使用已安装 JDK 21。API 33 已足以支持调度，不增加旧系统路径。NDK/CMake 到实际呈现探针任务再启用，不让首个编码器任务承担原生构建。

使用 Gradle Wrapper，不依赖全局 Gradle/Kotlin。构建时显式设置 JAVA_HOME 与 Android SDK 路径，不改用户 shell 配置；本机 SDK 路径不提交。允许用户已授权的命令行 SDK 平台与构建工具安装；不得静默接受新的系统安全权限或更改系统设置。

不建立泛用 JSON 写入器、LayoutRegistry 或未使用接口框架。优先使用平台 JSON 写入能力及普通集合；外部 JSON 检查可用临时脚本。协议和几何的确定性外部预期值得保留窄测试，避免为每个数据类建立同构测试。

## 任务与验证

所有实现串行进行；另一个代理只读审阅。协调者负责更新计划、核对证据和集成本地提交，不推送、不合并远端分支。

### T1：Compose 工程与公共编码器

状态：实现、测试和 APK 构建完成，终版独立审阅 spec/quality 均通过。唯一实现者为指定 OpenCode tab `a668bba3-7f06-43df-acfd-f5c63c09c07a`，Task `task_9bb2fdd4eb10`，Dispatch `ctx_6bbdd119eac5`。

新文件：Gradle Kotlin DSL 根配置、Wrapper、`app/build.gradle.kts`、Manifest、`MainActivity.kt`、`protocol/PatternEncoder.kt`、窄协议测试及测试资源。只添加本任务需要的 Android 资源。UI 展示协议、F68/C52 选择和当前编码状态；不放置尚未实现功能的假按钮。

公开编码入口使用 `PatternEncoder.encode(renderSeq: ULong, profile: LayoutProfile, levelLow: Int = 32, levelHigh: Int = 224): EncodedPattern`。返回完整序号、低 16 位载荷、Gray、CRC 与模块码值。`LayoutProfile` 只有 F68/C52。编码器不读取时钟、不执行 I/O。

- [x] 核实工具链并锁版本，创建最小 Compose app。
- [x] 从交付目录复制原始 14 组向量到本仓库测试资源，不依赖外部绝对路径运行测试。
- [x] 实现 Gray、CRC-8 和互补模块；CRC 输入低字节再高字节，MSB-first。
- [x] 通过外部向量，并遍历 65,536 个载荷验证 Gray 往返、F68/C52 长度及 34/26 亮模块；覆盖 `ULong.MAX_VALUE` 与 16 位回绕，CRC 检查值 F4。
- [x] 在仓库根运行 `./gradlew :app:testDebugUnitTest :app:assembleDebug`，检查实际测试报告与 APK。本机 lim 不存在；终版使用 `--max-workers=2`、Gradle heap 2 GB；不是 cgroup 总内存限制。
- [x] 由另一指定代理只读审阅 diff，协调者修正成立的问题，再整理仅属于 T1 的本地提交。

APK 编译和 JVM 协议测试不能证明 Activity 已在设备运行。可用 adb 设备时安装启动并查看实际 UI；没有设备则将运行验证明确列为未完成，不伪称通过。

### T2：静态光学图案与像素布局

状态：ready，T1 源码与构建验证已通过；设备验证继续明确为未验证。

依赖 T1。新文件集中于 `layout/` 和 `render/`，复用同一编码入口；MainActivity 只增加实际可用的静态预览入口。

- [ ] 使用整数半开矩形构造 F68/C52，导出旋转后的最终 Surface 坐标；四角 ID 随图案旋转，不重编号。
- [ ] 从 OpenCV 实际字典核对 ID 0–3 位图与许可后内置最少数据，不引入运行时 OpenCV。
- [ ] 静态 Surface 渲染与布局导出使用同一布局对象，确认颜色格式和码值语义。
- [ ] 验证四种旋转、非整除宽度；设备可用时按导出矩形检查截图和标记识别。

### T3：逐帧 VSync、EGL 和实际呈现探针

依赖 T2。增加渲染线程与最小 JNI 桥，NDK/CMake 固定版本在此任务按已核查的 SDK 包确定。

- [ ] 渲染线程持有 Looper、Choreographer 和 EGL 生命周期。复制 FrameTimeline 标量，以新动态状态分配序号，交换前取得帧 ID。
- [ ] 时间探针逐项查询支持情况，原生查询使用调用线程当前 display/draw surface。返回成功标志与值，不自行把 0 当作帧 ID 失败哨兵。
- [ ] 每种时间分别保留 VALID/PENDING/INVALID/UNSUPPORTED/QUERY_FAILED/时钟域未核实状态，预计时间不填实际呈现字段。
- [ ] 有界待查询队列按旧提交身份关联迟到结果，Surface 销毁前处理已有结果。
- [ ] 编译实际原生目标，并在设备上运行短动态会话验证身份关联；实际扩展支持和刷新率以真机结果为准。

### T4：会话、分段、日志与导出

依赖 T3。增加 `session/`、`log/` 和 `export/` 所需的少量实现，UI 连接实际会话控制与系统导出。

- [ ] 实现预热、开始卡、动态、结束卡和封存。恢复/重建后使用新序号，段配置和布局不可变。
- [ ] 提交时冻结身份，结果按主键追加；文件写入在后台，有界队列溢出独立锁存，停止新动态状态并标记不完整。
- [ ] 默认 2 s 排空上限后封存，导出契约六文件；缺标定使用空集合和 MISSING 绑定，不制造标定。
- [ ] 编码器测试、构建及契约 JSON 检查通过；设备上运行默认会话并核对分段、缺失标定和逐帧结果。

## 进展与未验证项

Planner 结果已读取并经协调者收敛：舍弃手写泛用 JSON 框架、为未来预建的注册器和重复数据类型；原生桥用 eglGetProcAddress；时钟用 System.nanoTime；未引入新平台兼容层。另一探索代理发生 HTTP 524，已要求停止扩展检索、交付已有结论，不将其网络故障作为开工依赖。

环境验证：`/opt/homebrew/opt/openjdk@21/bin/java -version` 返回 21.0.13；通过显式 JAVA_HOME 可运行，当前代理进程未继承环境变量。`sdkmanager` 为已弃用入口，新的 `android sdk --help` 已完成下载初始化并退出 0；支持 list/install 子命令，但尚未核实 SDK 平台包已安装。T1 实现者负责按帮助核实安装需求和构建结果。

T1 源码已落盘，构建尚在 Android 资源/依赖阶段，未接受完成。已实际读取编码器、Compose Activity、构建配置和向量测试；实现者需修正配置页示意高灰 232 为 224，并明确示意与正式 Surface 的区别。现 Gradle 堆上限 2 GB，worker 数尚未限制；最终证据构建要求最多 2 个 worker。没有 adb 设备，真机启动未验证。固定代码快照 `/tmp/lumaphase-android-t1-review-snapshot.md` 已交另一指定代理只读审阅，Task `task_44574fce260f` / Dispatch `ctx_ee844a63471e`；审阅不运行第二个构建、不改源码。

T1 后续证据：构建通过，协调者实际读取 XML 确认 6 tests、0 failures、0 errors、0 skipped；外部向量、全载荷和 ULong 上界均由 Kotlin 测试执行。APK 为 `app/build/outputs/apk/debug/app-debug.apk`，27,966,057 字节，SHA-256 `6d830937d54221ad6b087ac1fcf0db0a7ec0230cf9ba0999449baee8d81d7404`。预览灰度和示意文案已修。原审阅结论无代码阻断，终版窄核对 Task `task_1f941043f911` / Dispatch `ctx_27cc8111d3ff` 已派发。没有连接设备，Activity 启动和交互未验证。

协调者随后成功读取官方 `https://services.gradle.org/distributions/gradle-8.13-bin.zip.sha256`，返回值与 Wrapper 的 `distributionSha256Sum` 及镜像发行包哈希一致：`20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78`。代理报告中“官方哈希未核实”这一限制已消除，无需再下载发行包。

T1 终版独立审阅已返回：spec/quality 均通过，测试与 APK 证据吻合、预览修正有效，C52 以契约前缀定义和外部 F68 向量验证成立。审阅者未获得协调者后来读取的官方哈希结果，其报告该项限制已由上段实际命令消除。继续 T2，保留设备运行未验证的边界。

设备、屏幕标定及相机实验尚未验证。100 μs/毫秒精度、识别覆盖率和 OnePlus 13 时间戳支持不能由构建通过推断。每个任务完成后将实际命令、结果、审阅结论和未验证项补在此处。
