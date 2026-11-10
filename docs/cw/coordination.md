# 三仓库研究与文档协调

日期：2026-11-10。用户已追加授权三端原功能和 CR-001 网格布局实现；原 F68/C52 单排和原实现计划必须继续完成。早期仅研究的权限边界已被本次明确授权更新。

## 当前增量：CR-001

Android CR-001终版窄复核 `ctx_67de7c59ddca` / `task_e32fe3a39d48` 已通过，F1 ADDRESSED，spec/quality通过。91 tests零失败/错误/跳过，APK SHA-256 `16680556cfc4e97460792cf4516ae441219b17c8edd2b0eae10c740262a2d543`；协调者已核对关键修复、XML和APK，审阅者核对全部22文件hash。新旧布局/静态动态卡片导出/padding/schema1.1均接受为软件实现，按整文件本地提交；无Android设备的JsonWriter字节、真实EGL属性/GL像素/SAF/相机指标仍未验证。

Android终版复核quality通过、spec仅F1方向判定待修：原实现按扣marker后的dynamic矩形方向，1080×1920等竖屏误选横向grid。协调裁决按实际Surface width/height选择（高>=宽portrait，反之landscape），rotation独立，已交原writer窄修并补16:9/平板/方形/四旋转外部预期。其余padding/编码/四旋转/会话/导出/EGL格式接线通过，仍未提交CR产品。

Android CR-001已reported，91测试零失败/错误/跳过；另一专用Android tab a668bba3-7f06-43df-acfd-f5c63c09c07a 已回归负责终版只读独立复核 `ctx_deb98b1adcd7` / `task_2f2a1af138bb`，writer b0f15fd7冻结当前树。两专用Android会话按实现/独立验证配合，不并发写同一仓库或重复Gradle。最终报告 `/tmp/lumaphase-android-cr001-evidence.md`，审阅 `/tmp/lumaphase-android-cr001-review.md` 待回；尚未接受提交。

Mac CR-001已本地提交 `d455e46557513c9175075b13238c0197536f80b9`（feat: add CR-001 grid layouts and schema 1.1），协调者用git log核对HEAD。作者报告17整文件暂存与已审快照一致，剩余仅.cw，未push/tag/发布；软件接受与物理未验证边界不变。Android与Python任务继续运行，Mac无需重复构建。

Mac CR-001独立审阅 `/tmp/lumaphase-macos-cr001-review.md` 已返回spec/quality通过。协调者读取全文并重算Sources聚合hash `6b7115b5fe429df783c02cee1fb7fb8aaec05f37e46ec836b0a90dfbf5739197` 与已审树一致，接受软件实现，已交Mac作者整文件本地提交产品/测试及其依赖的contract/fixture，禁push/tag/发布。73测试含离屏GPU/真实JSON检查有效；前台UI、实际呈现/相机与物理指标仍未验证。Android仍独立开发网格，Python继续原R1-R3，不因Mac接受替代两端任务。

Python子协调者报告原core整文件checkpoint已提交 `598006e`（25文件），提交前index与实测工作区一致，导出暂存树快照178 tests及ruff/format/ty通过；此为其报告，非原全部能力完成。R1-R3唯一writer为tab dcc247f1-c270-4820-83a2-4cb13838605a，`ctx_6cc1742b1873` / `task_020cd22a98f5`，范围report/plotting及对应测试。CR与R4/R5后续串行，统一1.1契约/fixture未混入原core完成声明。Android/Mac任务继续，不因Python checkpoint等待。

Mac网格已reported（ctx_fc5988b702db），73测试、离屏Metal与真实JSON证据见 `/tmp/lumaphase-cr001-recon/macos-implementation-report.md`，未提交未接受。已派Android空闲原作者只读跨仓库复核 `ctx_5af5f90da782` / `task_241da145628f`，不写Android；Android网格唯一作者继续 `ctx_9d8b322031e5`。Python子Run已获解除跨仓库产品冻结授权：先保存原core整文件checkpoint，再以单一writer补R1-R3及后续CR/R4/R5；允许不同仓库并行，不再按历史“Mac唯一token”排队。Python允许整文件本地提交，不push；派发身份及证据待子协调者回报。

用户纠正调度：Android不能因Mac实现而停等。共享格式已定稿，立即允许独立仓库产品实现并行，每个仓库内部仍为单一作者。Android CR-001已派原修复作者tab b0f15fd7-7f5a-48b4-935d-561ee2cae54c，Mac继续原任务；不再采用下文历史记录的“三仓库全串行”约束。Android必须保留已实现原单排和原T4，新增手机网格/横向选择及schema1.1导出，完成后独立核验。

Python旧writer均已明确冻结。协调者读取 `/private/tmp/lumaphase-writer-status-20261110.md`，重新计算snapshot的全部产品文件hash，与当前工作区一致。已有178测试、公开8组+20ms smoke、Agg PNG和非editable包导入证据；仅接受现有实现固定快照，不接受原方案完成。原参考显示时间重建、跨帧回绕DP、逐eye中点差、histogram按rig/来源分层及REFRESH粒度仍有缺口，已要求在原implementation-plan记录并后续串行补齐。Mac继续唯一新增产品writer，sync暂不改产品或旧index。

契约及fixture已核验并字节同步三端。协调者独立重算24个placement案例和14个payload全部通过，修正文中首padding右边界83→82。当前contract SHA-256 `64daa324c882bb1eac35cbc6c9d925c63648e3103ceab9052136c133cf6e2e06`，fixture SHA-256 `fce0b745d81059254c697802da7f2e7af11075f7eabb420a261dabcc7469d2d2`。Mac产品实现已启动，唯一新增产品作者为Mac原会话，`ctx_fc5988b702db` / `task_b6bdd1e66f3b`；Android网格、sync CR产品实现排在其后。Android原T4软件复核通过并提交 `9ea13ae`，真机/SAF/JsonWriter字节未验证仍保留。

sync 当前writer已定位：tab `dcc247f1-c270-4820-83a2-4cb13838605a` / `ctx_f326706396f9` / `task_b8c624af25d1`，其子协调者已发冻结指令，待本人确认；不另派writer。子协调者报告原core现工作区178 tests通过、ruff/format/ty通过，前三旧失败为合同修订后旧fixture预期，第四DELTA_HISTOGRAM为实际实现遗漏已修；这些尚为子agent证据，未由总协调独立接受。已要求冻结后按实际工作区而非旧index核验原core同一快照、smoke/绘图/安装包及遗留项。

Mac和sync提案已完整读取，协调决定见 [CR-001三端实施决定](cr-001-plan.md)：采用geometry_profile（不并存layout_kind/grid_profile），schema1.1新写/1.0旧读，padding.level为实际整数码值。Mac已派 `ctx_bc0b02945f59` / `task_97f359174693` 仅写共享契约和显式动态区的共同fixtures，暂不产品实现。sync会话当前协调子Run `run_dca836ededc8`，定稿方向消息已由Orca转入其Run邮箱；当前writer身份仍待回答，禁止另派重复产品作者。

依据：`/Users/dengqi/Downloads/LumaPhase_远距低分辨率布局_最小改动说明.md`（v1.1）。协调者已读全文。新增网格，不替换编码器或旧布局：Mac 主选 F68-G6x12、备选 C52-G5x12；Android 竖屏主选 F68-G9x8、备选 C52-G9x6，横屏按实际几何选择横向网格。协议仍 OPTC-1，68/52 原索引 row-major、偶数列、padding 交替 L/H，中央60%采样。首批按说明2.5保留原动态区边界和原标记，不擅自改字典或标记尺寸。扩大有效面积与真实四路相机 A/B 留待实际数据验证。

格式先对齐再实现：原 layout_id 仍为不可变几何对象身份，layout_profile 保留 F68/C52；网格名称、rows/cols、padding及采样参数以新增字段表示，不能照说明示例替换已有字段/坐标系。六文件事件格式和呈现身份不改变，TRANSITION_FIT 为识别core诊断。人工四角ROI需明确相机坐标、角点顺序和屏幕参考面，不能把屏幕外角当ArUco内角。共享契约由协调者定稿后统一同步，各作者先提出具体差异。

| 当前任务 | 所有者 | 状态 | 输入 / 完成证据 |
| --- | --- | --- | --- |
| Android 原T4日志关闭与失败传播 D7-D10 | tab b0f15fd7-7f5a-48b4-935d-561ee2cae54c | reported，ctx_9b646b0150a5 / task_1acdfdb66d2b；已派原作者交叉只读复核 | 报告81测试通过，含关闭超时/有界tail/部分打开；未独立接受，不改网格 |
| Mac 原实现现状与CR共享格式提案 | tab 09e8590f-9f99-4ed7-8592-d2e92ca92834 | running，ctx_ad89b64f5492 / task_58129b662240；只读产品代码 | docs/cw/cr-001-plan.md；精确新增字段、旧格式兼容、跨端fixtures及实现顺序 |
| sync 原core/可视化与CR接口提案 | 原指定 sync 会话 | running，ctx_ea88fd49dee8 / task_f32cf3edc0e2；只读产品代码 | docs/cw/cr-001-plan.md；grid/KB4/ROI/过渡拟合/可视化及缺标定行为 |

产品实现继续串行；独立只读研究可同时进行。先完成Android原功能收尾，再按统一格式安排显示端和core，不能以CR替代原有能力。sync仍仅可调用core和可视化，不负责相机采集或控制硬件。

sync 已交付并由协调者读完 `docs/cw/cr-001-plan.md`（ctx_ea88fd49dee8）。提案采用 layout_kind/grid_profile 与独立 padding，保持原索引和既有坐标；ROI使用显式屏幕参考角与原图角，自动有效优先、人工兜底并保留冲突；TRANSITION_FIT为core独立诊断，缺profile不能产生精细时间。等待Mac共同格式建议后定稿。该作者报告原core已存在未提交源码、测试172通过4失败且另有owner在修；已要求明确当前writer身份与冻结范围，不能按旧记录当作空仓库或新开第二作者。原core交付仍需同一冻结快照验证，未接受完成。

协调者：Android 主 agent。Orca Run：`run_ef66ac635f3e`。各作者只修改自己仓库的 `docs/cw/`；Kimi 只写独立审阅报告，不改被审文档。共享契约由 Mac 作者维护正文，协调者核对后同步其他仓库。

| 任务 | 所有者 | 输入 | 完成证据 | 状态 | 依赖 | 尝试 |
|---|---|---|---|---|---|---|
| sync 详细研究与方案 | sync Codex，tab `a3a564e6-049d-4831-bc42-8bc3d498fb55` | 原设计、共享契约、三仓库现状 | sync 的 `docs/cw/sync-design.md`；算法、数据接口、可观测性、验证和未知项可逐项追溯 | reported | 已收到正式回报，文件及格式检查通过，算法方案待Kimi审阅 | 1 |
| macOS 文档审阅 | Kimi，tab `d9078bdc-7a96-4b20-a895-776c15a26f41` | Mac 的 `docs/cw/macos-design.md` 与共享契约 | 独立报告含文件行号、具体问题、最小修正及已核实依据 | accepted | 已读取报告并核对修订；旧稿附录不作为当前缺陷 | 1 |
| sync 文档审阅 | 同一 Kimi | sync 方案及共享契约 | 独立报告，核对算法和三端接口；不重复已修订旧稿问题 | reported | 报告已读取，旧稿误报需更正；唯一成立引用问题已交作者 | 1 |
| Mac 共享契约及平台修订 | Mac Codex，tab `09e8590f-9f99-4ed7-8592-d2e92ca92834` | Kimi 意见、协调者裁决 | 文档修订、争议处理记录、准确键名及示例一致 | accepted | 已核对diff、字段和哈希，并同步Android | 1 |
| 三端整合核验 | Android 主 agent | 上述文档、审阅报告和修订结果 | 共享契约副本一致，JSON 示例与链接有效，实测和推断明确区分 | running | 所有作者结果 | 1 |

## 已有格式与后续边界

共享契约当前为 v1.0。Android 与 Mac 的副本已核对一致。新增 sync 需求若改变共享字段，由 sync 作者先提出具体差异和消费者，不自行改写共享契约。

sync 只设计可调用的 core，暴露输入与结果接口。调用方提供图像或采样数据、时间戳及其语义、曝光和硬件配对元数据、显示日志与标定；core 负责图案识别、显示日志关联、屏幕前向模型、双目联合曝光观测、区间约束、时钟映射、原始同步统计和多采集组全局对时。

2026-11-10 用户明确：sync 不负责相机怎样取得数据。相机 SDK、设备发现与连接、取流、采集适配器实现和硬件触发执行均在外部。方案只规定这些外部调用方需要传入的数据；触发调整建议可作为计算结果返回，不直接控制硬件。可调用接口本身不要求 HTTP 服务、CLI 或插件框架。Kimi 审阅时按此边界判断完整性。

屏幕精度和识别率仍需独立采集验证，文档不将设计指标写成实测结果。

同日用户补充 sync 需要可视化。方案纳入可调用的图表数据与绘图接口，覆盖原始曝光差、漂移、分布、相位覆盖、设备差矩阵和已知延迟对比，并区分直接观测、区间和模型预测。可视化形式（函数返回/导出图形，或独立交互界面）已询问用户，答案未到；界面框架暂不定。

本轮不要求代理创建提交或合并分支；交付物是可直接审阅的文档及检查证据。

## Orca 任务身份

| 任务 | Task | Dispatch |
|---|---|---|
| sync 研究 | `task_35c1bf38b75d` | `ctx_5d6d3faf034a` |
| macOS 审阅 | `task_79af6b6bb8da` | `ctx_1ae5c6f00668` |
| sync 审阅 | `task_55bfdb8c1ea9` | `ctx_8d3f17a9f9ff` |
| Mac 契约澄清 | `task_0da230e77f5c` | `ctx_f245145f11e7`，`outcome_unknown`，等待启动或回报 |

结果通过 Orca 通知协调者，收到后核对文件再安排依赖任务。Kimi 的报告分别保存为 Android 仓库的 `docs/cw/macos-review.md` 和 `docs/cw/sync-review.md`。

## 已收到的审阅结果

Kimi 已交付 macOS 审阅报告。协调者已读取报告并要求 Mac 作者补齐实验配置名、明确不可取得原生 drawable 标识时为空。`latch` 的状态需按语义判断：事件有意义但缺公开查询 API 时使用 `UNSUPPORTED`，不能因没有 API 就视为物理事件不适用。报告附录引用了契约修订前的条文，不作为当前版本仍有相同问题的证据；待作者修订后复核。

Mac 已完成正式修订，协调者核对了契约 diff 和平台说明对应段落：四个实验配置的来源、长曝光/标定独立会话、drawable 标识可空、时间条目键和 `latch` 状态语义均已明确。Android 已同步契约并通过字节一致、SHA-256、JSON 示例、链接和围栏检查；当前契约哈希为 `d6832a051d28efbbbb5cc6007133464dc6d96ed770e1b9d97c281cc663ef45c2`。sync 作者已获通知，最终同步其副本。该接受仅针对文档一致性，不表示运行时或时序精度验证完成。

## Android 开发授权与代理

用户指定 Gradle、Kotlin、Jetpack Compose，明确不用 Android Studio，允许命令行 Android SDK 构建 APK。Compose 管理配置 UI，光学图案使用独立 Surface 渲染；原生扩展绑定仅在实际平台约束要求时引入。

先由两个指定会话独立核查，再由主 agent 写入 `docs/cw/android-implementation-plan.md` 并顺序派发实现。不得并行修改工程或实现代码；一个代理实现时，另一个只读审阅。用户当前已授权开发，不再为已有设计和技术路线增加确认门槛。

| 任务 | 会话 | Task / Dispatch | 状态 |
|---|---|---|---|
| 构建链、版本和最小工程探索 | OpenCode tab `b0f15fd7-7f5a-48b4-935d-561ee2cae54c` | `task_b0c552e5e431` / `ctx_2e9b7a26cab0` | reported，向量及环境证据有用，见下方裁决 |
| 实施任务与接口规划 | OpenCode tab `a668bba3-7f06-43df-acfd-f5c63c09c07a` | `task_7f7097c30e4e` / `ctx_156cffc3f9bf` | accepted，已收敛至实施计划并派发T1 |

Planner 已交付临时计划，协调者已实际读取并写入收敛后的 `android-implementation-plan.md`。该会话现转为唯一 T1 实现者：Task `task_9bb2fdd4eb10` / Dispatch `ctx_6bbdd119eac5`。另一代理服务商出现 HTTP 524，已要求停止扩展探索交付已有证据，后续仅负责独立审阅。用户已安装命令行工具；JDK 21.0.13 与 Android CLI 帮助已实际运行通过，SDK 平台和 APK 构建结果待实现者验证。

Explorer 随后完成：SDK 根只有命令行工具，缺平台、build-tools、adb；现有 `*.jar` 忽略会吞掉 wrapper，已通知实现者加精确例外。其建议的整套最新 AGP9/Kotlin2.4/API37 无当前功能需求，不采用；固定已规划兼容组合由首构建核实。其“native 必须持有 EGL”结论不成立，同线程 JNI 可取当前 display/draw surface；不迁移渲染所有权。无设备时不可将构建通过记为运行验证。

T1 已完成并核验：6 个 Kotlin 测试全通过、APK 产出，终版独立 spec/quality 均通过；本地提交 `ed8b549`，未推送。协调者额外读取官方 Gradle sha256 与镜像/Wrapper 校验值一致。T2 已顺序派发给同一实现者（`task_0dbc317203cb` / `ctx_d47fa75cfda8`），实现真实静态 Surface、ArUco 和 layout 导出，尚不实现动态调度或完整会话。T2 完成后由另一指定会话只读审阅；没有设备时保留真机验证缺口。

T2 经修复和独立窄复审通过，29测试全通过，本地提交 `75cf683`，未推送。真实Surface快照导出、ArUco一格黑边、窗口获焦隐藏系统栏与单采样RGB888配置已明确；无设备仍未验证上屏。T3 已派发 `task_a3a141ff1264` / `ctx_530dea345ead`，同一会话唯一实现：Kotlin VSync/EGL、最小原生时间戳桥、独立字段状态及有界pending队列；先释放资源再退出线程。不实现T4完整会话，完成后由另一会话独立审阅。

本机初查：PATH 有 `/usr/bin/java`，未找到 Gradle、kotlinc、sdkmanager；Android SDK 环境变量为空。JDK 实际可用性、可用工具路径和安装需求尚待探索结果，不能声称已有 APK 构建环境。

## sync 审阅核验

Kimi 已返回 `docs/cw/sync-review.md`。协调者实际读取并对照当前契约：其目标时间来源、EGL 请求字段和两项遗留歧义不成立，契约第 1、6.3、7.2 节已有明确规则，已要求 Kimi 更正。32 相位 × 15 帧的引用确实来自 Android 原设计第 2.2 节，已交 sync 作者修正。报告的公式与可视化检查未提出其他实质缺陷；这不代替后续实现与设备验证。

sync 作者已修正唯一成立的来源标注。协调者实际比较修改前后文件，diff 仅改变该句来源；JSON 示例和围栏复查通过。该轮 Orca 完成回报又触发子会话 sandbox 权限提示，通信状态尚未结算，但文件修订已经核验，不重复执行修订或扩大权限。

Kimi 第二轮另提出七项接口/绘图定义问题，已读取报告并交作者合并修订：逐视角诊断类型、三个公开结果的最小结构、单会话轴的匹配前提、图表字面值与部分结果输入、type 7 分位数定义均需补齐。两条建议经协调者调整：profile 共同误差带使用既有联合标定重采样，不能用普通参数协方差直接替代；原始配对关系和呈现时间来源独立分类，REFERENCE_FITTED 单列并保留相关性，参与重建的锚定样本不作为独立验证。Kimi 同步修正报告建议，不再扩大审阅范围。

Kimi 已在报告中撤回三项旧稿误报，协调者实际读到撤回段及准确契约引用。该轮回报尚未包含上一段要求的两条统计建议修正，后续修订任务仍在进行，不将报告整体标为最终接受。

随后已收到并实际核对报告第 3、7 项的修订：明确 profile 联合重采样与参数协方差带的来源区别，以及原始配对与呈现来源的独立分类。报告修正已到位；sync 作者的接口与绘图定义修订仍待回报和文件核验，不再追加审阅范围。

sync 作者随后交付接口补充；协调者已实际读完整 diff，核对了 ViewDiagnosticResult 的返回归属、GraphFitResult/SyncReport/PlotData、单会话匹配前提、图表字面值与空结果规则、type 7 公式，以及标定带/来源分层语义。临时脚本复查 JSON 示例、围栏和共享契约一致性通过。sync 研究与文档修订接受，独立可视化界面未选型、代码及设备验证未开展。
