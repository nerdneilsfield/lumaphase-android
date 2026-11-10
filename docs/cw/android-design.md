# Android 光学时间码显示端设计

日期：2026-11-10。目标设备：OnePlus 13。最低 Android API：33。

共享格式版本：`1.0`，已与 macOS 主 agent 对齐。

本文规定 Android 显示端的实现边界和数据流。跨平台编码、坐标和文件格式以同目录的 [OPTC-1 共享契约](optc-1-contract.md) 为准。本文是实现设计，仓库尚无应用源码；刷新率、时间戳支持情况和光学精度均待真机验证。

## 1. 职责与输入输出

应用根据会话配置生成 OPTC-1 图案，在手机屏幕上显示，并导出图案身份、呈现记录和实际像素布局。应用不采集相机图像，也不申请相机或麦克风权限。

识别后端负责状态解码、屏幕响应标定、曝光时间估计和多双目时钟映射。公共触发相位扫描与相机曝光设置由采集控制器执行。Android 应用不能通过更换图案内容代替相机触发相位调整。

输入包括实验配置、显示布局、操作员记录的亮度及色彩和调光设置，以及可选的屏幕标定文件。输出是共享契约规定的会话目录；压缩或通过系统文件选择器导出均不改变目录内的格式。

| 配置 | 显示请求 | 相机配置与用途 |
|---|---|---|
| `A120-S1` | 120 Hz | 30 Hz、1 ms 主实验；默认动态显示 90 s。 |
| `A120-PHASE` | 120 Hz | 采集控制器对全部双目执行公共相位扫描。 |
| `A120-E12` | 120 Hz | 12 ms 曝光积分实验，结果独立于主实验保存。 |
| `A-CAL` | 固定本次显示设置 | 播放几何、灰度和互补翻转标定图案。 |

## 2. 最小实现结构

设置和会话管理使用 Kotlin；光学图案由 `SurfaceView` 上的 EGL/OpenGL ES 渲染。Compose 可以用于设置页，但不驱动逐帧图案更新。编码器使用 Kotlin 即可，C++/JNI 只承担原生 EGL 渲染及扩展探针。

| 组件 | 责任与线程 |
|---|---|
| `SessionActivity` | 在主线程提供设置、全屏、方向固定和导出入口。 |
| `SessionController` | 管理会话阶段、分段和结束原因。 |
| `RenderThread` | 专用 Looper 线程；持有 Choreographer、EGL 上下文和窗口 Surface。 |
| `PatternEncoder` | 按共享契约生成 F68 或 C52 模块；不读取时钟、不执行 I/O。 |
| `EglRenderer` | 绘制整数像素矩形，设置目标呈现时间并交换缓冲区。 |
| `PresentationProbe` | 查询扩展及时间戳类型支持情况，按 EGL 帧 ID 查询结果。 |
| `DisplayMonitor` | 监听显示配置变化，将事件交给会话控制器。 |
| `LogWriter` | 在后台消费有界队列并追加写日志。 |
| `Exporter` | 导出已结束会话。 |

这些名称表达职责，不要求每项都建立独立接口或通用框架。会话控制器把配置变化按顺序交给渲染线程，EGL 创建、绘制和销毁也在该线程顺序执行。

## 3. 编码和像素布局

编码器使用完整 64 位 `render_seq`；新动态状态生成时才递增序号，采集段切换时保留会话内的序号连续性。低 16 位构成屏幕载荷，完整序号与会话身份共同确定状态。

Surface 重建或恢复后再次提交动态状态时分配新序号，连续性表示不回零，不表示复用旧序号。屏幕自然保持旧状态而没有新提交时不消耗序号。

| 区域 | F68 模块索引 | 内容 |
|---|---|---|
| 参考区 | 0–3 | `L,H,H,L`。 |
| 状态区 | 4–35 | 16 位 Gray，MSB-first，互补编码。 |
| 校验区 | 36–51 | 对原二进制载荷计算 CRC-8，MSB-first，互补编码。 |
| 循环指示区 | 52–67 | 按索引 0–7 编码 one-hot，亮位为 `n mod 8`。 |

默认 `L=32`、`H=224`。C52 去掉循环指示区。CRC 输入为低字节、高字节，多项式 `0x07`，初值及输出异或均为 `0x00`，不反射。

CR-001 在不改变编码数组的前提下新增网格布局：横向 `F68-G6x12`/`C52-G5x12`，Android 竖向默认 `F68-G9x8`/`C52-G9x6`；原 `F68`/`C52` 单排继续保留。槽位 `i` 放在 `row=i/cols`、`column=i%cols`，两轴均用 floor 整除，动态矩形被完整铺满且格宽、格高最多相差 1 像素；列数为偶数，原互补位对不跨逻辑行。`modules` 始终只有 68 或 52 项并按原索引排列，剩余槽位写入独立 `padding`（`slot_index`、`role="padding"`、最终 `rect_px`、实际 L/H 码值），显示端同样绘制填充。Android 按实际 Surface/drawable 的宽高方向选择：高不小于宽（含正方形）用 9×8/9×6，宽大于高用 6×12/5×12；该选择在扣除 marker 之前确定，`pattern_rotation_deg` 只变换已选几何，不改变选择或模块索引顺序。`layout_id` 是会话内不可变对象身份；drawable 尺寸、图案旋转或几何选择变化都产生新的布局对象和新段，不覆盖旧布局。

应用默认竖屏，按共享契约将逻辑图案转换为最终 drawable/Surface 输出像素坐标：左上原点、x 向右、y 向下，矩形采用半开边界。OpenGL 的坐标方向差异由渲染器处理。系统缩放时输出像素不一定对应面板原生像素，屏幕标定需使用相同坐标或提供显式映射；布局尺寸不使用 dp。

图案的 `pattern_rotation_deg` 与显示配置的 `display_rotation_deg` 分开保存。模块矩形及标记顶点已经应用图案旋转，后端不再旋转一次。定位标记使用 `DICT_4X4_50`，逻辑左上、右上、右下、左下的 ID 分别为 0、1、2、3；标记随图案旋转，ID 不重新分配。

`Display.getRotation()` 返回 `Surface.ROTATION_0/90/180/270`，分别映射到 `display_rotation_deg=0/90/180/270`。角度描述图形的补偿旋转，与设备物理转动方向相反，不从传感器姿态另算角度。[Android Display.getRotation](https://developer.android.com/reference/android/view/Display#getRotation())

EGL 颜色配置、渲染目标格式和 shader 输出共同决定最终 SDR 码值。使用 sRGB 目标时需考虑传递函数，不能只把 shader 输出设为 `32/255` 或 `224/255` 就宣称输出码值正确；静态验证同时检查目标格式、颜色空间和输出读回。

`pixel_format` 是不透明比较值，来自实际选中的 EGLConfig：依次查询 `EGL_RED_SIZE`、`EGL_GREEN_SIZE`、`EGL_BLUE_SIZE`、`EGL_ALPHA_SIZE`、`EGL_BUFFER_SIZE`，固定写成 `EGL_CONFIG(R=…,G=…,B=…,A=…,BUFFER_SIZE=…)`。任一查询失败写 `UNKNOWN` 并保留诊断，不能把请求的格式或纹理内部格式冒充实际 surface 格式。

图案横截面沿实测扫描方向重复，编码模块排列方向尽量垂直于扫描方向。扫描方向由标定确定，不能根据相机图像行号或竖屏方向推定。布局旋转、Surface 尺寸或显示模式变化时结束当前采集段，为新采集段保存对应布局。

## 4. VSync 与逐帧渲染

Surface 可用后，渲染线程创建自己的 Choreographer 和 EGL 资源，在预热前完成 shader 编译和固定大小缓冲区分配。

1. 在 `VsyncCallback` 内读取首选 `FrameTimeline`，立即复制 `vsync_id`、预计呈现时间和截止时间。
2. 为新动态状态分配 `render_seq`，生成模块并绘制完整图案。
3. 扩展受支持时，在交换前获取下一次交换的 EGL 帧 ID。
4. `EGL_ANDROID_presentation_time` 受支持时，设置目标呈现时间。
5. 调用 `eglSwapBuffers()`，按共享契约记录提交结果及身份关联。
6. 查询此前提交且仍待确认的帧，将新的时间戳结果交给记录器。
7. 仍需绘制时注册下一次 `VsyncCallback`。

标准 `target_present` 固定来自所选 `FrameTimeline.getExpectedPresentationTimeNanos()`，`deadline` 来自同一时间线的 `getDeadlineNanos()`。调用 `eglPresentationTimeANDROID` 时使用同一预计值，但把请求独立记录在 `platform_timestamps.egl_presentation_request`；扩展支持与否不改变标准字段来源。

`FrameData` 及其时间线对象只在回调内有效，日志保存复制后的标量。回调为一次性注册，不能假定注册一次后持续执行。[Android VsyncCallback](https://developer.android.com/reference/android/view/Choreographer.VsyncCallback)、[Choreographer](https://developer.android.com/reference/android/view/Choreographer)

`render_seq`、EGL 帧 ID 和 `vsync_id` 分别表示图案身份、交换身份和调度时间线，三者不能互相替代。EGL 帧 ID 的关联还应保留原采集段身份，避免重建 Surface 后误关联历史结果。

## 5. 呈现时间探针

初始化时查询 `EGL_ANDROID_get_frame_timestamps`，加载扩展函数，启用 `EGL_TIMESTAMPS_ANDROID`，并逐项查询所需时间戳的支持情况。扩展存在不代表所有时间戳类型都受支持。[Khronos 扩展规范](https://registry.khronos.org/EGL/extensions/ANDROID/EGL_ANDROID_get_frame_timestamps.txt)

| 原生结果 | Android 端处理 |
|---|---|
| 有效时间戳 | 追加呈现结果，并保留原始值和共享契约规定的相对时间。 |
| `EGL_TIMESTAMP_PENDING_ANDROID` | 保留待查询项，在后续回调中再次查询。 |
| `EGL_TIMESTAMP_INVALID_ANDROID` | 保存事件未发生的状态，不能解释为成功呈现。 |
| 扩展或该类型不支持 | 字段为 `null`，保存 `UNSUPPORTED` 状态。 |
| 查询调用失败 | 状态及原因为 `QUERY_FAILED`，原生错误保留，不生成实际呈现时间。 |
| 查询历史已过期 | 平台明确报告时使用 `HISTORY_EXPIRED`，不根据帧龄自行推断。 |
| 原生值有效但时钟域未证实 | 使用 `CLOCK_DOMAIN_UNVERIFIED`，原生值留在平台数据，标准时间为空。 |

`EGL_DISPLAY_PRESENT_TIME_ANDROID` 表示开始扫描输出的时间，不是屏幕所有位置同时发光的时刻。像素发光时间还要结合屏幕标定中的扫描延迟和亮暗响应。

待查询队列有固定上限，渲染线程不等待某一帧变成有效状态。EGL 只维护有限历史，应在后续回调中及时查询；队列达到上限、Surface 即将销毁或会话结束时，对未取得结果的帧按共享契约记录未确认原因。已经确定的旧帧身份不能改成新采集段的身份。

预计时间保存为目标呈现时间。无论扩展缺失、查询失败还是结果长期待定，都不能将预计时间填入实际呈现字段。没有实际呈现信息时，后端可以用参考双目重建时间轴，并标注其来源。

## 6. 刷新率、方向和生命周期

Surface 建立后读取支持的显示模式、当前模式和实际像素尺寸，再调用：

```kotlin
surface.setFrameRate(
    120f,
    Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
    Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS,
)
```

只在配置变化时更新请求。Android 不保证满足请求，实际模式和有效呈现间隔分别记录，不能以请求成功作为 120 Hz 已实现的证据。[Android 帧率文档](https://developer.android.com/media/optimize/performance/frame-rate)

会话期间监听 `DisplayManager.DisplayListener`。实际显示模式、Surface 尺寸、布局方向或标定键发生变化时，结束当前采集段，更新配置后重新预热。应用进入后台时结束当前段并停止调度；恢复后创建新段。渲染线程停止使用 Surface 后才销毁 EGL 资源，不能继续向已销毁的窗口 Surface 提交。

运行期间保持屏幕常亮、隐藏系统栏并固定方向。设置页、调试文字和动画不覆盖动态图案。操作员固定亮度、色彩模式和调光模式；应用记录可读取的系统值及人工填写值，不把无法读取的值写成已确认的系统状态。

## 7. 会话和导出

默认主实验依次运行 10 s 预热、2 s 会话卡、90 s 动态图案和 2 s 结束卡。会话卡内容与身份格式使用共享契约；预热和卡片不进入动态同步误差统计。

`A120-E12` 与 `A-CAL` 分别创建独立 `session_id` 并导出，不作为主实验的普通采集段。

每次阶段切换和采集段变化追加配置事件。日志在应用本地目录保存；文件选择器或共享机制只负责导出，不参与渲染。逐帧回调不执行文件写入、压缩和分享操作。

六个会话文件及其字段由共享契约定义。新会话使用 schema 1.1；`layout.json` 保存不可变布局集合，每个对象完整写 `geometry_profile`、`grid_rows`、`grid_cols`、`module_count`、`padding_count`、`module_order=row-major`、`sampling_inner_fraction=0.6` 和独立 `padding`，单排也写这组扩展（1×68/52、`padding=[]`）。每段通过 `layout_id` 引用，旧段布局不会被新尺寸或新几何覆盖。Android 目前只写 `layout.json`，没有导入读取 caller，因此本轮未新增 1.0 解析器；需要读取 1.0 的消费者按契约第 3.5 节处理缺失的整组扩展。

Android 将 `vsync_id`、`egl_frame_id` 和每次 Surface 创建时分配的 `surface_instance_id` 放入 `platform_ids`。应用另分配会话内唯一的 `native_frame_id`，以 `(session_id,native_frame_id)` 关联提交及结果。显示模式保存于段配置，平台原始旋转枚举保存在平台数据中。

标准时间前缀统一为 `target_present`、`system_present`、`submit`、`deadline`、`render_complete`、`latch`，各自保存 `_raw_ns`、`_ns` 及 `timestamp_status` 对象中的独立状态。`recorded_raw_ns` 和 `recorded_ns` 表示事件生成时间。原设计的 `egl_render_complete_ns` 和 `egl_latch_ns` 在统一格式中分别使用 `render_complete_ns` 和 `latch_ns`，不再重复输出同义字段。原始 EGL 返回值及哨兵保存在 `platform_timestamps`。

`render_seq` 在内存中使用无符号 64 位语义，在 JSON 中使用无前导零的十进制字符串；纳秒字段使用有符号 64 位 JSON 整数。Android 原生纳秒不经过浮点转换。标准相对时间等于同一时钟域的原始纳秒减 `manifest.time_origin.raw_ns`；尚未确认时钟域的原生值只进入平台数据。

实际呈现结果可以晚于分段事件。结果保留提交时的段、布局和 Surface 身份；日志行顺序不是显示顺序。后端按提交主键合并 `SUBMITTED`、`UNCONFIRMED` 和后到的 `PRESENTED`，有效呈现结果优先于此前的未确认记录。渲染完成时间有效时，实际呈现字段仍可以为 `PENDING` 或 `UNSUPPORTED`。

日志队列溢出会破坏逐帧追溯，记录器按共享契约留下丢失数量和会话不完整标记。导出前停止生成新事件，处理已接收事件和待查询项，再生成摘要。写入失败时不报告完整导出成功。

结束卡结束后最多再等待 2 s 处理待确认结果，将这个期限写入 manifest。剩余项按原因记为未确认，再封存会话；封存之后不修改导出包。

屏幕标定按设备、系统构建、显示模式、实际像素尺寸、亮度、色彩模式、调光模式和布局方向关联。缺失时 `display_profile_id=null`，`display_profile.json` 中 `profiles=[]`，相应段绑定为 `MISSING`。屏幕模型的数值 schema 由识别后端制定，本轮只规定导入容器和配置匹配，不能将空容器解释成有效标定。

## 8. 实现与验证顺序

先实现编码器并核对现有协议向量，再用静态图案检查像素边界、定位标记和导出坐标。随后实现动态 VSync、EGL 帧 ID 关联、呈现探针及逐帧日志，最后接入会话控制、导出和标定播放。

| 检查对象 | 最小证据 |
|---|---|
| 编码一致性 | 现有 14 组向量逐项一致；遍历 65,536 个载荷，检查 Gray 往返、F68 长度 68 和亮模块数 34。 |
| 布局一致性 | 静态渲染像素与 `layout.json` 的矩形和定位标记一致；共同 CR-001 fixture 的 24 个 placement 案例、14 个载荷向量和全部 65,536 个载荷的网格重排检查通过。 |
| 动态追溯 | 真机短会话中，每条呈现结果能够关联到同一图案的提交记录。 |
| 时间来源 | 有效、待定、无效和不支持状态各自保留；目标时间不进入实际时间字段。 |
| 分段 | 切到后台再恢复、改变显示模式或 Surface 尺寸后，身份和布局对应新段。 |
| 互操作 | Android 和 Mac 使用同一编码向量、契约文档及日志字段，后端按相同规则读取。 |

2026-11-10 已在临时脚本中核对 14 组向量，并遍历全部载荷验证 Gray 往返、F68 长度和亮模块数；CRC 的 `123456789` 检查值为 `0xF4`。这些结果验证协议计算，不代表 Kotlin 编码器或 Android 图形路径已通过测试。

OnePlus 13 上的 EGL 时间戳可用性、持续实际刷新率和扫描响应尚未实测。原设计中的状态识别率 99%、毫秒误差指标和 100 μs 档位需要相机采集、屏幕标定及独立已知延迟实验验证，不能由编译或显示端日志单独证明。

## 9. 依据

- 原始设计：`optical_sync_design/02_Android显示应用设计.md`。
- 公共编码权威：`optical_sync_design/03_识别算法与多双目对时设计.md` 第 2 节。
- 公共测试向量：`optical_sync_design/validation/protocol_test_vectors.json`。
- 跨平台格式：[OPTC-1 共享契约](optc-1-contract.md)。
