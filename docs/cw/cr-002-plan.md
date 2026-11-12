# CR-002 远距定位与颜色诊断实施记录

更新日期：2026-11-12。依据：用户提供的《LumaPhase CR-002：远距 Tag 与粉色伪影的最小修订》。用户已授权增量实现；现有新网格、旧单排、完整编码、显示日志、识别和时钟求解继续保留。

## 当前决定

先实现不依赖公共文件格式变更的部分，再统一跨模式几何文件和显示诊断日志。本文是协调记录，不把提案中的字段视为已发布接口。

- 大 Tag 固定使用原整数 cell 的 3 倍，原字典、ID 和自身角序不变，白静区至少一个新 cell。放不下时明确报错，不静默缩小；RUN 恢复原几何。
- Android 原竖屏 RUN 几何放大 3 倍后无法容纳，因此新增 TAG→RUN 会话全程锁定横屏，使用实际横屏 RUN 布局构造目标；原竖屏入口保留。2400×1080、1920×1080 四旋转的固定倍率已由实际 Builder 核对。
- Tag 尺寸来自原始检测角点，分别报告四边、最短边和最短边除以 6 的代理值。缺失 Tag 保留记录、尺寸为空。60 px、72–96 px、8 px 是实验设计目标，不作为默认硬拒条件，不伪造概率置信度。
- Android 固定工站先复用手动动态区四角和未标定平面单应，仅作单路识别测试。私有存储明确绑定源几何、相机、尺寸、裁剪和方向，失配要求重标；不能导出冒充 core 的 KB4 normalized 映射。
- core 继续使用 normalized camera H 和真实镜头投影，公开映射文件统一前不把 Android 私有映射当成共享格式。
- 全屏白、黑和低频网格必须反映实际画面。schema 1.2 已通过显式类型允许无标记诊断画面；OPTC 仍要求真实四标记，不能导出未绘制的标记。
- 颜色诊断先区分静态、动态、RAW/RGB 输入域，缺失辐射标定不声称线性亮度已经成立。P2 响应补偿等实验数据，不根据截图归因或修改时钟模型。

## 任务与负责人

| 任务 | 唯一负责人 | 状态 | 验证范围 |
| --- | --- | --- | --- |
| Android Tag 诊断、私有手动工站连续 RUN | tab b0f15fd7；ctx_49075be961db | 已本地提交 39069a7 | 180 项测试通过、离线 APK；生命周期修复源码已核，真机另验 |
| Mac 3 倍 Tag、A/B 诊断与 schema 1.2 会话 | tab 09e8590f；run_8898215d51ec | 已本地提交 50e65f6 | 81 项测试、实际离屏 Metal、生产序列化样例与负例；前台未测 |
| Android 大 Tag、A/B 诊断与 schema 1.2 会话 | tab b0f15fd7；ctx_f22a5db5969a | 已本地提交 c224aea | 189 项测试、APK 与 merged Manifest；启动、GL 和设备导出未测 |
| Android RUN 利用上下留白扩大 Tag | tab b0f15fd7；ctx_eecb455c8a38 | 已本地提交 2100f64 | 198 项测试；sync 独立原尺寸图全链 24/24 通过 |
| core 原图 Tag 尺寸和四路摘要 | run_dca836ededc8 | 已本地提交 6479e71；band 提交 092f1a3 | 568 项测试、缺失与重复 Tag 的 PNG 已查看；现场稳定性未测 |
| schema 1.2 core 读取与历史边界 | run_dca836ededc8 | 已本地提交 eb00ba0 | core 主 agent 独立联合检查 177 项通过，原空历史崩溃返回 INVALID；writer 回报全套 620 项通过 |
| 原 R4 跨帧序列、R5 时钟参考时间线 | run_dca836ededc8；ctx_c4122ab3f502 | R4 running，R5 queued | 精确整数、多次回绕、掉帧与歧义保留；不平滑时钟阶跃 |
| CALIBRATE/RUN 公共几何文件 | 总协调 | queued | normalized KB4 绑定与三端格式一致；Android 私有映射不能替代 |
| RAW/线性亮度与颜色位置曲线 | core；统一输入域后串行实现 | queued | 显式 Bayer/黑白参考/原图支持和独立数值 oracle |

当前三端提案分别位于 `/tmp/lumaphase-cr002-android-proposal.md`、`/tmp/lumaphase-cr002-macos-proposal.md`、`/tmp/lumaphase-cr002-core-proposal.md`。它们是实现依据，未冻结为公共 schema。

## 公共布局格式协调

Mac 已完成大 Tag、人工继续 RUN、A/B 诊断与会话导出接线，本地提交 `50e65f6`。81 项测试通过，四旋转实际 Metal 像素与官方字典核对一致，生产序列化样例和负例已检查。最后的设置窗口遮挡修复已重新构建并验证签名；前台窗口、快捷键和实际呈现未运行。证据见 `/tmp/lumaphase-cr002-macos-commit-evidence.md`。

schema 1.2 的精确字段已写入 [CR-002 契约](cr-002-contract.md)，三仓副本 SHA-256 为 `101c5b6079007fed9d55ae90c283a9b4e952318fc41a8b0ab41249d1ba775cfe`。`layout_type` 区分 `OPTC`、`CALIBRATION_TARGET`、`FULLSCREEN_DIAGNOSTIC`。共同字段仍描述真实 drawable、完整逻辑画布、矩阵和不可变布局 ID；OPTC 保留原 grid/modules/padding 字段，大 Tag 保存真实标记及参考区域，全屏诊断保存实际画面定义，不伪造时间码模块或未显示的标记。该类型表达用途差异，不替代 `geometry_profile`。

段继续通过 `layout_id` 引用当时实际布局。标定和诊断使用已有 CALIBRATION 阶段/帧类型，时间码序号字段为空；不增加同义日志事件。旧 1.0/1.1 合法布局按原 OPTC 格式继续读取。Mac 与 Android 已完成显示端接线，core 已提交 schema 1.2 读取及历史边界处理；生产跨端文件尚未联调，共同 fixture 尚待补齐。跨模式公共几何文件和 RAW 输入格式仍未定义，不能用本机私有工站数据替代。

Android `c224aea` 修正了 Manifest 入口、阶段事件先于建段、尺寸或方向改变后未重确认、native frame ID 分配顺序，以及横屏启动实例的生命周期访问。最终 APK SHA-256 为 `04820516661329b4fb7c4fc6830cb8950ced5a6736590fa19b25a2a1d2cb672f`。189 项 JVM 测试和构建通过，协调者核对 XML、APK 哈希、merged Manifest 和生命周期差量；真实 Activity 启动、GL 像素、A/B 持续时间和生产 JsonWriter 六文件字节尚无设备运行证据。报告位于 `/tmp/lumaphase-cr002-android-display-typed-evidence.md`。

## 尚未完成的现场验证

用户随后要求在普通 RUN 中利用竖屏长度放大 Tag，并明确要求 sync 支持。Android 新增 F68/C52 大 Tag 网格选项和独立相机预设，原四选项与旧六预设保留。dynamic/modules/padding 坐标与对应旧布局逐矩形相同，只改变 markers。1080×2400 下黑框为 384 px（0°/180°）或 288 px（90°/270°），旧版为 126 px。该布局仍为 OPTC，geometry_profile 和 schema 不变，不能与 CR-002 的独立三倍标定画面混同。

初次跨端图像验证为 20/24；四个横屏大 Tag 图官方检测失败。真实 Builder 随后修正窄带静区与物理外边的对齐，不修改检测阈值或给输入图补边。修正样例 SHA-256 为 `dfdae06854060fa782360eff6951e2e681f09ebfac42110db690983b069ca8ec`，作者复跑为 24/24，随后 sync 独立原尺寸复验也为 24/24。独立复验未读取 compare 图、未缩放或补边、未改检测阈值；官方检测四 Tag 后逐模块电平、CRC 和 payload 7 均正确，C52 Runner 为 NOT_APPLICABLE。结果位于 `/tmp/lumaphase-android-integration-fixed-independent/report.json`，协调者已核对 passed=24/total=24。原失败输入、报告和日志保留，原 report.json 曾被作者复跑覆盖，未声称恢复该文件。样例仍为 host Gson 序列化及 Builder 像素图，不是设备生产 JsonWriter 字节。

最终 ARM64 APK SHA-256 为 `0127da2a43de98b3afd09b140fd272daa366af024e08cf679132431f6953390d`，64,272,646 bytes。用户用于录制的固定副本 `app/build/outputs/apk/recording/lumaphase-large-tags-arm64-2438acfe.apk` 未覆盖，限定竖屏 0°，其对应几何与最终版一致。运行中采集请使用本次会话导出的真实布局，避免混用新旧预设。

四路原始帧 Tag 检出率、尺寸目标、至少 100 个唯一帧的稳定性、曝光 1/4/8 ms 对照和颜色响应均无现场数据。100 帧数量不能替代稳定性判据；无实测阈值时不输出稳定通过。Android 原现场只检出一个 ID 的原因也尚未由实际灰度帧闭环确认，CR-002 不代表该问题已经消失。
