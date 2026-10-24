# Study start diagnosis — s21-reenter

日期：2026-10-21。对象：sessionId `b2e3dda0-1d31-4026-887c-ab46cbff32a7`、证据目录 `1792570521869`。本段仅分析本地证据，没有启动/点击设备或构建。

## 2026-10-21 20:32 更正19:25的过度结论

19:25曾看到田径运动课成绩单，页面标示16:19、学分+25、力量+20，底部有继续学习，证据为 `.build-tmp/device-acceptance/vd4-now.png`。**这只提示游戏中可能发生过开课/学习，不能证明成绩单归属于本文件的sessionId或某个attempt。**

此前本节把成绩单写成“那节课实际开成了”、声称页面始终未被触碰并据此认定是学习面板漏识别，这些说法证据不足，现明确撤回，不作为诊断事实。不能由一次晚间截图证明其间没有操作，也不能把页面上的16:19直接等同本attempt已确认开始时间。

| 证据 | 可以得出 | 不可以得出 |
| --- | --- | --- |
| s21原始run/verdict | 应用未确认开始，STOPPED/FAILED、0/1 | 游戏一定未开课，或一定已开课 |
| 后来16:19成绩单 | 当时屏幕存在对应课名的结果内容，值得追查关联 | 同session/attempt完成、无人触碰、必然是ROI漏检 |
| 约120秒确认窗后停止 | 开始确认超时 | 能精确还原tap送达时刻或30分钟后的结果归属 |

仍需同run事件、输入投递、检查点和相邻帧证明成绩单归属，才可进一步判断学习已经开始/完成。当前不补判历史局PASS，不盲目再次开课。以下原始证据和诊断分支继续有效。

## 可直接采用的结论

**已证实的是“开始学习未被确认”，尚不能证明“游戏没有开课”。根因仍未确定，当前不应改start坐标、延长120秒等待或自动再点。**

- 原图排除了这一帧上的金币不足和明显低健康：学费66、金币81、体力100、清洁100。
- 原图为田径运动课30分钟、蓝色选中边框、蓝色去上课按钮；不是QQ宿主页或错误返回页。
- 当前[PetProfile.kt](../../../VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/automation/PetProfile.kt#L57) startButton=(360,1384)落在原图蓝按钮内（按钮约x80..640、y1336..1432）。不存在当前坐标明显落出按钮的证据；这不证明当时实际注入一定成功或帧后未改变。
- 最终STALE健康是长时间等待后的终态展示，实际stopReason是LEARNING_NOT_CONFIRMED_AFTER_START，不能把STALE倒推为该局停止原因。
- 后来工具层级看到“继续学习/分享”不能归属此attempt，不能补判本会话成功。

## 实际读取的证据

| 材料 | 观测 |
| --- | --- |
| [course-before-start原图](../../../VirtualDisplay/.build-tmp/device-acceptance/s21-reenter/round-1-course-before-start.png) | 720×1600；田径30分钟；66(当前81)；体力消耗15(当前100)、清洁消耗6(当前100)；去上课按钮可见 |
| [run-record.json](../../../VirtualDisplay/.build-tmp/device-acceptance/s21-reenter/run-record.json) | terminalState FAILED，completedRounds0，courseStarts0，feed_count1、clean_count1，stopReason如上 |
| [verdict.json](../../../VirtualDisplay/.build-tmp/device-acceptance/s21-reenter/verdict.json) | STOPPED，completed0/requested1；184次ImageReader超时且184次Shizuku fallback成功，P50 744ms/P95 880ms |
| [runtime-state.json](../../../VirtualDisplay/.build-tmp/device-acceptance/s21-reenter/runtime-state.json) | START_COURSE，pauseCount0，最后确认时间见下；无新的开始/完成确认 |
| [PetStudyFlow.kt](../../../VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/automation/PetStudyFlow.kt#L40) | 检查页面/健康→记attempt/预留预算→一次tap→最多120秒循环识别learning probes→未匹配抛该错误；没有再次tap |
| [PetProfile.kt](../../../VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/automation/PetProfile.kt#L61) | learning_initial ROI[60,925,420,1000)，learning_expanded ROI[330,925,530,1000)，用于底部正在学习栏 |

课程页显示体力15/清洁6是实际消耗事实。遵照lead澄清，**学习不强制体力/清洁门槛≠学习不消耗**；轨迹可记录实际消耗，但不得把15/6或护理目标值自动设为STUDY硬门槛。

## 时间线（Asia/Shanghai，取JSON时间戳）

| 时间 | 事实 |
| --- | --- |
| 16:15:22.043 | run-record.startedAt |
| 16:19:00.989 | ready-before-course的100/100健康观察时间 |
| 16:19:49.987 | runtime.lastConfirmedAt；只是最后模板确认，不是已确认学习开始 |
| 16:21:51.564左右 | startedAt+runtimeMs；结束于未确认开始 |

末态检查距最后健康观察170575ms。最后模板确认至终止约121.6秒，与源码120秒等待加捕获开销相符；不能据此精确推出tap时间。`courseStarts=0`是ledger没有确认开始，不等价于游戏未响应tap。

## 本地证据缺口

本段glob未找到该sessionId的本地events文件，s21目录也无events.jsonl；其他s20/r19事件流不是本局，不能混用。目录没有学习确认窗口中每次观测的截图，因此无法判断：

1. tap未成功投递/实际页面尚未稳定；
2. 已进入学习，但学习栏位置/形态与两个ROI不一致；
3. 捕获一直回读旧帧或过渡帧；
4. 页面出现提示/弹窗，学习实际没开始。

fallback成功说明返回了图像，不证明图像新鲜、页面正确或ROI匹配。

## Lead下一步：先补证据，再决定修复

只有lead可操作设备。优先拉取以下既有只读资料，不启动新托管：

- `run-as com.ynk.virtualdisplay`下 `files/pet-events/b2e3dda0-1d31-4026-887c-ab46cbff32a7.jsonl`，保存到本地s21目录的events.jsonl。
- 同session检查点 `files/pet-checkpoints/b2e3dda0-1d31-4026-887c-ab46cbff32a7.json`；已有pet_mvp.log中2026-10-21 16:19:00～16:21:52片段。
- 核对输入意图/投递事件是否有start坐标、真实displayId、单次tap；核对course-budget-reserved之后course-start-observe的序列、score、captureSource/帧时间和错误。

这批事件可能只提供分数而没有实际帧。若仍无法识别页面，仅在一次明确授权的单节复验中增加有界观测：tap前、tap后第一个新帧、首次页面变化、120秒截止帧，以及阈值最近的一帧；每帧记录对应事件序号/时间、displayId、ROI得分。只读观察不得自动第二次tap；资料只留本地。不要对整个120秒每300ms无限存全屏。

取证后的分支：

| 证据 | 最小处理方向 |
| --- | --- |
| tap投递前即失败 | 修输入/页面稳定边界，不改学习模板 |
| 正在学习栏真实出现、ROI分数低 | 用真实正/负帧校准ROI/模板；旧课程页、房间、结果页必须仍为负例 |
| 图像陈旧/无新鲜帧 | 修捕获/新鲜度判定，不放宽匹配阈值 |
| 新弹窗或游戏拒绝 | 识别具体拒绝，停止并报告；不盲点去上课 |
| 直接看到结果页 | 必须证明本attempt归属；不能凭继续学习按钮认成功 |

## 可运行的本地只读检查

脚本：[study_probe.py](../../../VirtualDisplay/.build-tmp/study_probe.py)。它不调用adb、Gradle，不写文件。

```powershell
& 'C:/Users/lu158/.dsh/dsh-runtimes/dsh-primary-runtime/dependencies/python/python.exe' -I 'C:/Users/lu158/dsh-qqpet/VirtualDisplay/.build-tmp/study_probe.py'
```

本段实际运行输出UNDETERMINED及EVIDENCE_GAP；脚本为缺少events定义exit2，宿主PowerShell外层显示非零exit1。这是证据缺失反馈，不是新跑游戏失败。lead保存事件后可加 `--events '<本地events.jsonl绝对路径>'`，脚本只筛选匹配session并列关键事件；它仍不会凭事件存在自动判断根因已解决。
