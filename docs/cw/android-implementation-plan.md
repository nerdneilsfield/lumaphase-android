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

状态：done，本地提交 `ed8b549`。实现、测试和 APK 构建完成，终版独立审阅 spec/quality 均通过。唯一实现者为指定 OpenCode tab `a668bba3-7f06-43df-acfd-f5c63c09c07a`，Task `task_9bb2fdd4eb10`，Dispatch `ctx_6bbdd119eac5`。

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

状态：done，本地提交 `75cf683`；29测试通过，独立窄复审 spec/quality 均通过。Task `task_0dbc317203cb` / Dispatch `ctx_d47fa75cfda8`；设备验证继续明确为未验证。

首次交付已核验：24 个测试零失败/错误/跳过，APK SHA-256 `03101e1c7c94cbb88889fb4c91fbe3e1bfb9b029cd4f2739fc2491a6dac6d931`。协调者初读发现实际渲染以 Surface 尺寸建布局，导出却用 maximumWindowMetrics 重建，未满足同一几何来源；已交作者修复为实际预览快照并在没有快照时不宣称导出实际布局。附带核对 MSAA 请求与注释。修复 Task `task_c6ae4cb8cd22` / Dispatch `ctx_361fde679043`；独立只读审阅 Task `task_d44e7e3831a2` / Dispatch `ctx_0b457f1c2467`，固定包 `/tmp/lumaphase-android-t2-review-snapshot.md`。修复、复查完成前不接受 T2、不进入 T3。

快照导出修复后 28 测试通过，协调者读到实际布局回传及APK证据。随后独立审阅发现 ArUco 数据单元少一格 border 偏移（主agent已读源码确认），以及隐藏系统栏调用在 setContentView 前可能被空控制器跳过。当前同一作者修复轮 `task_8ff66c5f8a54` / `ctx_383ebe52f45d` 合并修这两项，并去掉无单采样 EGL 配置时仍降级渲染的分支；新增6×6黑边网格外部预期。完成后冻结源码、重建并窄复审，不能用早期通过的测试/APK证明后改代码。

该修复轮已交付：主agent实际读取最终源码及XML，29测试零失败/错误/跳过；APK SHA-256 `90a21103d2cb9e34b2c581ae5fa8ed5679b088dd1b504606f81ae21b354531b6`。ArUco borderPx 内缩、窗口获焦隐藏系统栏及只接受实际单采样RGB888配置均已落盘。已派独立审阅者仅复核这三项、实际布局快照和最终证据，不重跑构建、不扩大范围。尚未提交T2，无设备运行证据。

T2 窄复审已返回，四项全部 ADDRESSED，最终文件哈希及测试/APK吻合。剩余为设备行为和真实 JsonWriter 字节未在 host 执行。审阅另保留 onDestroy 与 surfaceDestroyed 顺序导致 EGL 释放排队晚于线程退出的条件性风险；T3 本就重做渲染线程生命周期，将明确先停止调度、在所属线程释放 EGL，再退出线程，不增加兼容框架。

依赖 T1。新文件集中于 `layout/` 和 `render/`，复用同一编码入口；MainActivity 只增加实际可用的静态预览入口。

- [ ] 使用整数半开矩形构造 F68/C52，导出旋转后的最终 Surface 坐标；四角 ID 随图案旋转，不重编号。
- [ ] 从 OpenCV 实际字典核对 ID 0–3 位图与许可后内置最少数据，不引入运行时 OpenCV。
- [ ] 静态 Surface 渲染与布局导出使用同一布局对象，确认颜色格式和码值语义。
- [ ] 验证四种旋转、非整除宽度；设备可用时按导出矩形检查截图和标记识别。

### T3：逐帧 VSync、EGL 和实际呈现探针

状态：代码及构建已接受，真机验证未完成。Task `task_a3a141ff1264` / Dispatch `ctx_530dea345ead`，仍由同一指定会话唯一实现。T2 提交 `75cf683` 为基线。

首版代码已落盘，48 项 Kotlin 测试仅在临时禁用 native 构建的副本运行，不能记为 T3 原生构建通过。NDK 约 908 MB 下载缓慢，作者按官方 repository XML 校验值直取同一固定官方包，仍待实际原仓库构建 `.so` 和 APK。当前不启动第二个下载/Gradle。下载期间派独立只读审阅 Task `task_b06d88fbdec9` / Dispatch `ctx_e3adaf693cd2`，固定包 `/tmp/lumaphase-android-t3-review-snapshot.md`；重点 VSync、帧身份、JNI 返回状态和线程退出顺序。不进入T4、不提交T3。

独立审阅返回 spec/quality 均不通过，报告 `/tmp/lumaphase-android-t3-review.md`。协调者核对源码后，将六项合并给当前唯一实现者修复：跨 Surface 的 run/序号连续性、不同几何的不可变 layoutId、取 frame ID 失败时字段状态及即时错误采样、最终 stats 保留 runId、排空后再发送 Activity result、静态路径先释放 EGL 再退出线程。当前为集中修复第 1 轮；不接受首版，不启动第二个下载或构建，终版仍需真实 native 构建证据和窄复审。没有设备，运行行为继续未验证。

修复轮已报告完成。协调者读取原仓库 XML，确认 49 tests、0 failures/errors/skipped；实际 APK SHA-256 为 `78b5d21f64afc9ce4507330bdfe7c976af4e97530de00bd75ad880cd66ee57db`，ZIP 内包含 arm64-v8a、armeabi-v7a、x86、x86_64 四份 `liboptc_egl_timestamps.so`。实现者报告真实 CMake/NDK 编译成功，完整证据在 `/private/var/folders/sj/34br26nn0k558dd541dvb02r0000gn/T/opencode/harness/t3-evidence.md`。已交原审阅者对六项修复及源码哈希做只读窄复审；尚未提交 T3，真机短会话仍未验证。

依赖 T2。增加渲染线程与最小 JNI 桥，NDK/CMake 固定版本在此任务按已核查的 SDK 包确定。

- [ ] 渲染线程持有 Looper、Choreographer 和 EGL 生命周期。复制 FrameTimeline 标量，以新动态状态分配序号，交换前取得帧 ID。
- [ ] 时间探针逐项查询支持情况，原生查询使用调用线程当前 display/draw surface。返回成功标志与值，不自行把 0 当作帧 ID 失败哨兵。
- [ ] 每种时间分别保留 VALID/PENDING/INVALID/UNSUPPORTED/QUERY_FAILED/时钟域未核实状态，预计时间不填实际呈现字段。
- [ ] 有界待查询队列按旧提交身份关联迟到结果，Surface 销毁前处理已有结果。
- [ ] 编译实际原生目标，并在设备上运行短动态会话验证身份关联；实际扩展支持和刷新率以真机结果为准。

### T4：会话、分段、日志与导出

最终软件复核已接受：报告 `/tmp/lumaphase-android-t4-final-review.md`，独立作者核对wiring L1/L2/L4/L5/L6及日志D7-D10，spec/quality通过。协调者读取完整报告并独立核对原仓库81 tests、0 failures/errors/skipped；APK SHA-256 `dcba0971ec30fce43b479c7fbbf27b1e855c3eba04d4e7d1d71032ee77753005`。剩余四个源码/测试文件按整文件提交。此接受仅指原单排的软件实现与构建，不包括真机Activity/Surface时序、SAF与production JsonWriter字节；writer永不结束、被拒绝包内关闭诊断时序和恢复原因歧义保留报告。CR-001为独立新增任务，不替代原功能或既有验证限制。

生命周期复核 `/tmp/lumaphase-android-t4-lifecycle-review.md`：D1/D5/D13和正常首段原因通过，D2/D4/D6仍有真实接线缺口L1（新view绕过owner排空/故障状态）、L2（旧Activity未解绑effects），另有恢复顺序/首段前暂停/封存后重建边界。改变负责人：原审阅者 tab `b0f15fd7-7f5a-48b4-935d-561ee2cae54c` 接手窄修复并成为唯一作者；原实现者暂停写入。仅修接线及直接相关边界，SessionLog/Exporter/Json和D7-D10仍留下一任务。要求验证 completion落在unbind/bind之间及新view生命周期入口，而非仅先bind再fire。T4未接受未提交。

生命周期子任务已报告（`task_108bf797860f` / `ctx_4a3e79e38833`）：新增 PreviewRunHost 单一调度入口及8个外部事件顺序测试；协调者读取代码/报告并核对70 tests零失败/错误/跳过，APK SHA-256 `48e416daa1e826f8f28ead74aa88defc37c6ae9630f8b805a2d876bcf479a648`。已派只读窄复核 D1-D6+D13，重点 host测试与Android实际接线、旧宿主和drain重建；D7-D10日志收尾未动、仍待下一任务。尚未接受或提交T4。

二次独立复查 `/tmp/lumaphase-android-t4-rereview.md` 仍不通过。多数原16项已修，但 D1-D6（双计时、排空恢复、首段原因、completion丢失/旧宿主、swap错误二次读取、日志故障停止未锁存）及 D7-D10（关闭失败传播、无界tail、不可写诊断、部分打开FD）成立，另有 D13环境刷新。达到整批修复两轮后改变范围与验证方法：先由原唯一作者只修生命周期/排空及D13，要求具体事件顺序和回调次数证据；日志close/tail留下一独立串行任务。T4仍未接受未提交，禁止用62测试总数代替生命周期证据。

第 2 轮修复已报告：新增 EGL 所属线程上的有界 pending 查询（16ms tick，最多2s，无新提交），后台阶段保存剩余时长，写失败使用 RENDER_FAILURE。协调者核对 62 tests 零失败/错误/跳过、APK SHA-256 `9ca5ea5c485080cd070c2fcd31b4a0e38eaef4669faaf046a5db7e79adfe5fa3`。已交原独立审阅者复查原16项和直接引入的生命周期问题，特别首次/重建双计时、排空时恢复/Surface销毁及无会话实际路径；未接受未提交。

第 1 轮修复已报告，协调者核对 62 tests 零失败/错误/跳过及 APK SHA-256 `b60724b124a262ba28ea0f7fa9333bec30470ccd46c0284c627ff6e13a03ea09`。仍未接受：源码显示 pauseScheduling 后 onVsync 直接返回，pending 查询只由该回调驱动，等待 2s 没有真正查询；后台保留绝对 elapsed deadline 仍消耗阶段时长。已集中退回作者修这两个明确缺口，并核对排空阶段恢复和写失败结束原因，不启动重复审阅或构建。

首审未通过，报告 `/tmp/lumaphase-android-t4-review.md`。已交原作者集中核对修复：阶段 ns/ms 单位、真实配置事件序列化、resize/恢复分段、无会话路径、写失败/溢出传播、原子帧快照、Activity/后台阶段续接、停止提交后排空、计数与 reason、哨兵符号、卡片 UUID 尺寸和文件关闭。60 测试与 APK 证据属首版，不能证明上述路径正确；T4 未接受未提交。用户提出手机条纹过细，具体担忧待澄清，暂不改变模块数和共享协议。

调度更新：T3 已提交 `3aa6fce`；T4 已启动，Task `task_6c32faa21445` / Dispatch `ctx_869a95ec0098`，唯一实现者仍为原指定 OpenCode 会话，当前状态 running。

T4 首版已报告，状态 reported、未接受未提交。协调者核对原仓库 XML：60 tests，0 failures/errors/skipped；APK SHA-256 `631a5ac704dfeb2ecf6699a2637638867a6e2fe9ff554d24f8856eda12a8c3c9` 与证据一致。host 使用真实 SessionController/SessionLog、替代序列化器验证代表性会话，尚未验证 production JsonWriter 的六文件实际字节；无设备，阶段画面、生命周期、排空和 SAF 未验证。已派原审阅者只读审阅本次完整差异，重点并发/溢出停止、持续查询与封存、卡片身份和六文件实际写入路径，报告预定 `/tmp/lumaphase-android-t4-review.md`。不将构建和键名清单测试视为导出端到端通过。

状态：queued，T3 终版窄复审 `task_94868aff2100` / `ctx_e6da4d52aa8c` 已返回，六项全部 ADDRESSED，spec/quality 均通过。协调者读取完整报告 `/tmp/lumaphase-android-t3-rereview.md`，接受编译及源码部分；没有设备，未接受运行行为。T4 继续由原实现者串行完成；明确会话状态不能依赖单个 Activity 实例。过期 run_id 注释在触及该文件时更正；不为纯测试覆盖抽取额外接口。Surface 重建首帧的间隔统计为 null，避免把停顿算成连续 VSync 间隔。

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
