# Android 显示端实施计划

## 新任务：手机相机识别测试

用户现场截图只报告ID3，协调者用实际Python OpenCV5.0对原图检出0/1/2/3，Mac离屏四旋转逐像素核对亦通过；现场Android根因尚未确证。新增修复按ids.total读取完整CV_32SC1数组并与corners同扁平索引，兼容Nx1/1xN；不得把形状假设当作现场已复现原因。拍摄一帧现冻结实际GrayFrame并自动同帧分析，显示IDs/shape/时间戳/尺寸/耗时，Mac插值文案已修。冻结请求使用生产FreezeCaptureState，重复请求不取消首请求、切来源取消旧capture，独立复核 `/tmp/lumaphase-marker-input-final-review.md` 确认两处P2闭合。协调者另把方向赋值置于markReady前，避免发布未初始化方向。作者169项测试零失败；协调者offline构建exit0（原测试任务命中缓存），真机JNI/实际ids形状/原现场识别仍未验证。README同步诊断操作，按整文件提交。

内置参考布局增量已完成并通过独立窄复核：Android六个预设由实际Builder生成，Mac四个选项读取实际Mac Builder生成的16布局资产（SHA-256 `b950f1b13ad2b8cb8ef53089eb184cfda87948083087aa2044d63c607af21baf`）。首次默认Android竖屏F68网格，内置/导入分别记忆；所有旋转有唯一layoutId，换旋转走原startLive失效旧generation。报告 `/tmp/lumaphase-camera-builtins-evidence.md`、`/tmp/lumaphase-camera-builtins-final-review.md`。作者运行161 tests零失败/错误/跳过；提交范围纳入这些新增测试，避免把未提交测试计数当作源码验证。新版APK SHA-256 `b5b9f024c3bf44235cb059fb82e1feb051198b5dd6de49916087a10dfe17a2d2`。真机预设切换、Compose恢复、相机定位和JNI运行仍未验证；参考尺寸不代表被测设备的实际配置。

2026-11-11，连续识别最终窄复核四项通过，报告 `/tmp/lumaphase-camera-preview-final-review.md`：TextureView附加矩阵采用 `M=N∘D⁻¹` 并验证与默认铺满合成，旧单排共同逻辑长轴切带覆盖90/270精确带数与方向，两组外部payload按带独立解码，手动忙提示不清旧reading。协调者已读取报告、测试XML与APK，151 tests零失败/错误/跳过；APK SHA-256 `9d7ff672b3eedaa183b21524f9d59a677162b0194a1e57434a0269655cd1365a`。README由协调者在作者源码冻结后更新，属于已授权文档工作，不是作者修改冻结源码；新增入口说明亦由协调者同步为连续识别。源码与构建接受准备整文件提交，设备TextureView/OpenCV JNI/连续帧/手动触控仍未验证，不宣称现场效果通过。

2026-11-11，上一采集/画布切片已整文件提交 `be556fe`，终版只读复核通过。用户真机反馈“能跑了”，但图片比例不正确，并明确要求相机打开后连续显示识别数据；该反馈只证明应用可运行，不证明相机方向/识别正确。当前唯一writer b0f任务 `task_38a899b56420` / `ctx_8b1bb00bae92` 修复raw/upright/View等比例映射并收紧相机UI；a668任务 `task_37902da94a98` / `ctx_17008ba5280d` 只读核对core到连续ArUco/采样/解码的最短接线方案。自动识别OpenCV官方AAR下载许可已询问，等待明确答复，不阻塞比例修复；不得把当前采集页标作识别完成。

用户随后明确“不行就上opencv5”并要求继续，OpenCV下载已获授权。协调者直接核查官方GitHub release与Maven metadata，5.1.0为正式发行，Android AAR HTTP200、147907333字节，采用5.1.0固定本地依赖。原计划是4.10.0而非4.1，不再等待旧下载问题答复。比例修复已reported（124测试通过，APK SHA-256 `003d25f94f2fa4be140db8c932cc4f066e317ae52b670a9ecd1bd57a1aeff29b`），未接受未提交；a668任务 `task_81d0e06a7adf` / `ctx_f5056811c136` 先复核实际TextureView变换，b0f任务 `task_e4d1ebec24e0` / `ctx_e0b6f1a40631` 先固定官方AAR并核实真实Java/native API、实现定位/采样pipeline，暂不修改比例审阅中的六文件。下一步串行接连续相机流和结果叠加；缺参考/模块不可伪造PASS，停止/未定位清除旧成功结果，分析线程有界单飞并随owner释放。

连续识别UI任务 `task_6de8daec7ffb` / `ctx_c538ece52d09` 已reported，整树冻结、未接受未提交。OpenCV5.1.0本地AAR已固定，真实Java API为 `org.opencv.geometry.Geometry.findHomography`；连续单飞分析、私有源布局记忆、定位/逐band解码显示及冻结帧手动四角已接线。协调者读取XML确认140 tests、0 failures/errors/skipped并核对APK 223,918,647字节、SHA-256 `683b8f8d2e77f07bc0d44d250632d7778b3a7d42d9fc4f049c5faa817ff12dde`。报告 `/tmp/lumaphase-camera-live-ui-evidence.md`，a668正在只读终版复核 `/tmp/lumaphase-camera-live-final-review.md`；预览实际Matrix合成、JNI、真实连续帧及触摸均未在设备上验证，不能记作用户所见比例或识别已修好。

用户授权新增相机测试入口，明确自动识别四角ArUco并允许手动四角兜底，参考lumaphase-sync实际实现；原显示功能保留。目标是导入被测源的实际layout.json、选择布局、预览相机并显示定位/采样与16位payload、CRC/Runner状态。没有显示日志/实测profile时不推算完整render_seq、同步误差或TRANSITION_FIT精细时间。原图与预览的旋转/裁剪映射、导入坐标和手动参考角需一致；手机相机的滚动快门不等于工站全局快门。

当前先完成不依赖新库的纯Kotlin解码切片：tab a668bba3为唯一writer，输入已归一化68/52模块，沿用core严格互补阈值0.35、最多12个软Gray位枚举、Gray/CRC/Runner候选过滤、PASS/SOFT/FAIL与truncated语义；软唯一候选不能提升为hard PASS。以原14向量、全载荷hard往返、CRC/Runner反例和soft阈值外部预期验证。tab b0f15fd7只读核对Camera2/ImageReader生命周期、帧stride/crop、预览映射和OpenCV接入，不同时改代码或构建。

解码切片已核对源码并接受：新增recognition/PayloadDecoder.kt及PayloadDecoderTest.kt，直接对照冻结core protocol.py的oracle，复用现有Crc8/GrayCode。原14向量、65536×2 hard decode、CRC/Runner反例、阈值等号、soft唯一候选与truncated等9项新测试通过，原仓库XML总计100 tests、0 failures/errors/skipped。输入非有限值显式拒绝，区别于core将NaN作soft的行为已记录；后续sampler须保留缺项状态。未接相机、未assemble，新decoder不在现有显示APK内；OpenCV依赖下载确认及平台研究仍待回。

拟采用系统Camera2和OpenCV4.10.0官方Android AAR做检测及几何映射。自动ArUco需求已确认；按全局新增依赖下载前确认规则，OpenCV固定本地依赖下载仍待用户明确回答（官方AAR 112288832字节，已验证URL存在，尚未下载）。平台相机接线和共享几何输入在研究回报后收敛，不预建新相机框架。

Camera首个运行切片已派 `ctx_c4bd6f3058c6` / `task_015335a923dc`，唯一writer b0f15fd7：独立CameraTestActivity/权限与入口、后置Camera2预览、单帧Y复制与upright冻结图、真实layout.json 1.0/1.1导入和显式选择。避免新下载，允许复用已存在Gson2.13.2从test提升runtime，不自写JSON库；不加OpenCV、不放假自动检测按钮。相机/Texture生命周期和异步旧回调有明确所有权；stride/crop/buffer position、四旋转、真实JSON解析独立预期测试。a668只读准备/tmp验证输入，不写仓库或重复构建。后续再串行接自动定位/手动动态区四角、模块采样与decoder；采样归一化保持core参考模块而非研究稿全图分位数，原单排按薄带分别解码，过渡行不混成虚拟稳定码。设备缺失仍是运行验证限制。

2026-11-10，采集切片已 reported，未接受、未提交。协调者直接读取 XML 确认116 tests、0 failures/errors/skipped，并计算 APK 37,994,937字节、SHA-256 `6763f9a85c23f5ac6ff874c84249e0b99995258638aa907359db1f400d0a5f0a`；未运行设备。a668 当前只读审阅 `task_7d4a32f3c05b` / `ctx_21f197c76652`，报告预定 `/tmp/lumaphase-android-camera-capture-review.md`。

同日发现真实导出兼容缺陷：1080×2400 Android Builder 将1036×1632内部图案区域记为pattern画布，并附加(22,384)居中平移，违反共享契约§3.2完整画布纯旋转规则，被core正确拒绝。协调者已核对契约和Builder，决定修Android元数据、保持最终实绘矩形不变，不放宽core或修改共享格式。b0f 当前唯一writer任务 `task_800010b45a62` / `ctx_4b666b08788a`：区分内部区域与完整逻辑画布，并补导入器的矩阵/整数放置校验；报告预定 `/tmp/lumaphase-android-layout-canvas-fix.md`。OpenCV下载确认仍待用户答复。

画布修复现已 reported、源码冻结，报告 `/tmp/lumaphase-android-layout-canvas-fix.md`。完整画布元数据与内部区域已分开，作者报告六几何×四旋转×三个非方形/奇数尺寸的实绘几何哈希不变；新Builder输出可被导入，旧违规Android导出明确拒绝，Mac重建输入可导入。CameraController改用AtomicInteger/CAS修复generation并发自增。协调者独立执行offline unit+assemble命令成功（测试任务UP-TO-DATE，未重新执行测试），直接读取XML为121 tests、0 failures/errors/skipped，APK哈希与报告一致：`a497ddeab242176d55cb84205d6377f0eaea836da7cd4ee39a60738a7f4698c6`。等待a668对冻结终版窄复核，及实际Builder临时输出交core互操作；未接受、未提交，真机与production JsonWriter字节仍未验证。

core互操作已回报通过，协调者读取结果JSON确认：隔离使用已提交 `a16be68b5baaa11802e0134d14469af446c536a7` 源码，实际Android Builder结构输入 `/tmp/lumaphase-android-new-layouts.json`（95,002字节，SHA-256 `c60171ac8b274553ec40adea5d9a45273b558ac712c7a1102961b1448303640e`），validate_layout接受24布局、1440 modules、72 padding、96 markers；公开load_display_session用合成六文件容器读回24布局且逐对象相等。结果 `/var/folders/sj/34br26nn0k558dd541dvb02r0000gn/T/lumaphase-android-interop-euua6vbf/result.json`。此验证不覆盖production JsonWriter字节或设备导出；终版独立源码复核仍待回。

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

CR-001增量已实现并独立复核通过：新默认手机网格F68-G9x8/C52-G9x6，实际Surface横向选F68-G6x12/C52-G5x12，旧F68/C52单排保留。schema1.1、padding独立导出并实画、几何身份和原T4路径保持；以共同24placement/14payload及全65536载荷重排检查，91测试零失败/错误/跳过。最终APK SHA-256 `16680556cfc4e97460792cf4516ae441219b17c8edd2b0eae10c740262a2d543`。F1方向修复按Surface宽高决定而非扣marker动态区。报告 `/tmp/lumaphase-android-cr001-evidence.md`、独立审阅 `/tmp/lumaphase-android-cr001-review.md`；真机、production JsonWriter字节、实际EGL/GL像素和SAF仍未验证，不作为现场指标通过证据。

Planner 结果已读取并经协调者收敛：舍弃手写泛用 JSON 框架、为未来预建的注册器和重复数据类型；原生桥用 eglGetProcAddress；时钟用 System.nanoTime；未引入新平台兼容层。另一探索代理发生 HTTP 524，已要求停止扩展检索、交付已有结论，不将其网络故障作为开工依赖。

环境验证：`/opt/homebrew/opt/openjdk@21/bin/java -version` 返回 21.0.13；通过显式 JAVA_HOME 可运行，当前代理进程未继承环境变量。`sdkmanager` 为已弃用入口，新的 `android sdk --help` 已完成下载初始化并退出 0；支持 list/install 子命令，但尚未核实 SDK 平台包已安装。T1 实现者负责按帮助核实安装需求和构建结果。

T1 源码已落盘，构建尚在 Android 资源/依赖阶段，未接受完成。已实际读取编码器、Compose Activity、构建配置和向量测试；实现者需修正配置页示意高灰 232 为 224，并明确示意与正式 Surface 的区别。现 Gradle 堆上限 2 GB，worker 数尚未限制；最终证据构建要求最多 2 个 worker。没有 adb 设备，真机启动未验证。固定代码快照 `/tmp/lumaphase-android-t1-review-snapshot.md` 已交另一指定代理只读审阅，Task `task_44574fce260f` / Dispatch `ctx_ee844a63471e`；审阅不运行第二个构建、不改源码。

T1 后续证据：构建通过，协调者实际读取 XML 确认 6 tests、0 failures、0 errors、0 skipped；外部向量、全载荷和 ULong 上界均由 Kotlin 测试执行。APK 为 `app/build/outputs/apk/debug/app-debug.apk`，27,966,057 字节，SHA-256 `6d830937d54221ad6b087ac1fcf0db0a7ec0230cf9ba0999449baee8d81d7404`。预览灰度和示意文案已修。原审阅结论无代码阻断，终版窄核对 Task `task_1f941043f911` / Dispatch `ctx_27cc8111d3ff` 已派发。没有连接设备，Activity 启动和交互未验证。

协调者随后成功读取官方 `https://services.gradle.org/distributions/gradle-8.13-bin.zip.sha256`，返回值与 Wrapper 的 `distributionSha256Sum` 及镜像发行包哈希一致：`20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78`。代理报告中“官方哈希未核实”这一限制已消除，无需再下载发行包。

T1 终版独立审阅已返回：spec/quality 均通过，测试与 APK 证据吻合、预览修正有效，C52 以契约前缀定义和外部 F68 向量验证成立。审阅者未获得协调者后来读取的官方哈希结果，其报告该项限制已由上段实际命令消除。继续 T2，保留设备运行未验证的边界。

设备、屏幕标定及相机实验尚未验证。100 μs/毫秒精度、识别覆盖率和 OnePlus 13 时间戳支持不能由构建通过推断。每个任务完成后将实际命令、结果、审阅结论和未验证项补在此处。
