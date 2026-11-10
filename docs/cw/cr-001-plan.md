# CR-001 三端实施决定

2026-11-10。需求来源：`/Users/dengqi/Downloads/LumaPhase_远距低分辨率布局_最小改动说明.md`，版本1.1。本文件由总协调维护，记录格式决定和任务顺序；详细共享规则定稿进入 `optc-1-contract.md`。

## 范围

原 F68/C52 单排、原显示会话/日志、原 Python 双目曝光/时钟拟合和可调用可视化继续完成。新增 Mac F68-G6x12、C52-G5x12，Android 竖屏 F68-G9x8、C52-G9x6；Android横向实际绘制矩形选横向网格。F68-G4x18为可选实验，不阻塞首批。编码器、Gray/CRC/Runner、render_seq 和光学协议 OPTC-1 不变。

首批保持各平台原动态区、标记ID/字典和布局边界，不强求两平台原动态区相同。文档1.5扩大可见面积作为后续现场几何调整，不能覆盖2.5的兼容要求。人工四角ROI用于自动定位失败的固定工站；不扩大tag或切换字典。

## 统一格式决定

Android自动方向补充裁决：以实际Surface/drawable的width/height判定，height>=width用9×8/9×6，width>height用6×12/5×12。不以扣除marker带后的动态区判方向，避免16:9竖屏误选横向网格；图案rotation仍独立变换几何，不改变自动选择方向。

新写入会话统一 schema_version=1.1，涉及原有携带版本的文档；事件行不另加版本。新读取器继续接受1.0旧单排，不承诺旧读取器能解释网格。协议名保持OPTC-1。

- layout_id 保留会话内不可变几何对象身份；layout_profile 保留 F68/C52。
- 新增 geometry_profile，值为 F68、C52 或四种网格名称；不再并存 grid_profile/layout_kind 同义判别字段。
- 新版所有布局写完整扩展组：grid_rows、grid_cols、module_count、padding_count、module_order（row-major）、sampling_inner_fraction（0.6）、padding。单排为1×68/52，padding=[]；采样仍走原单排策略，不能据0.6裁掉原纵向时序信息。
- modules 始终恰好68/52项，保留index和最终drawable rect_px。padding独立，每项为slot_index、role="padding"、rect_px、level；level为实际整数码值，按空槽顺序L/H交替并引用本布局level_low/high，不使用另一套字符串枚举。
- 行列指旋转前逻辑网格；两轴均用 floor 分界，互补对不跨逻辑行，再整体沿原矩阵旋转。导出矩形已经旋转，消费者不得二次旋转。
- 不新增 dynamic_roi_px、module_rects_px、payload_id 或 coordinate_space 等同义字段；保持原动态矩形、坐标系统和六文件关系。
- 1.0整组扩展缺失按旧单排解释；1.1缺字段或未知几何明确拒绝，不能回退。显式扩展与计数、索引、填充和几何不符均报错。

网格采样使用模块中央60%宽和高，padding不参与解码/拟合/P10。采样位置保留屏幕坐标和原图坐标。稳定CRC和多排TRANSITION_FIT分别处理；缺实测profile不得捏造精细光学时间。人工ROI明确drawable或动态区参考角与原图角的对应关系；自动有效优先，人工兜底，冲突保留诊断，不自动覆盖文件。sync不接相机采集，也不控制硬件。

pixel_format允许来自实际渲染配置的、平台限定的确定性描述，是不透明比较值；不得把请求格式冒充实际值或跨API归一化。原Mac真实Metal名称继续保留，Android保留EGL实际属性描述；查询失败不能构造成功值。具体共享条文由Mac作者写入契约后协调者核对。

## 共同验证与任务顺序

共同几何fixture使用显式逻辑画布121×109、动态区[7,11,108,94]，覆盖四必需网格的四旋转与旧单排。fixture直接给完整modules/padding，不由各平台画布比例反推；平台原布局边界单独回归。独立算例：F68-G6x12首格[7,11,15,24]，末数据格[65,80,74,94]；90度首格[85,7,98,15]。复用14组原协议向量，全载荷重排后数组必须逐项不变。CR提到的reference脚本尚未定位，不声称已运行。

1. Android原T4日志/接线修复交叉复核，完成可验证的软件部分，保留真机限制。
2. Mac作者仅修订共享契约和共同fixture，协调者核对、同步三份；sync报告当前writer身份避免重复写入。
3. 按统一契约依次实现Mac网格、Android网格、sync网格/ROI/过渡拟合与诊断，单一产品作者串行，不并行改产品。
4. 各端保留旧布局回归，新布局使用相同fixture；core原功能与可视化也须在冻结快照核验。

相机实测四路60s A/B、P10 4/6px、有效比例95%、已知5/10/20ms延迟和光学精度是实验目标，尚无数据，不宣称通过。代码以整文件提交，不拆hunks，不push。
