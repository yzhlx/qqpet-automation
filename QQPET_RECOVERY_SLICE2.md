# QQPET_RECOVERY_SLICE2

## Verdict

PASS：四项恢复能力已接入原有 Session，锁屏恢复、capture 中断恢复、页面不符 STOP 已完成手机故障注入验证。本轮使用只读首页观测 Session，未新增课程，未声称重新完成三轮学习。

## Frozen Slice1

- branch：`feature/qqpet-mvp-session`
- immutable Slice1 SHA：`2dbc2763dcf8aa798ee86cbd50ea3fecc6cd46f9`
- annotated tag：`qqpet-slice1-verified`
- 三轮实验基准：`3a2eb37b26e32addb5e476ece66dc9e11a5830b6`
- 原 Slice1 APK 和测试包分别备份为 `deliverables/frozen-slice1-debug.apk`、`deliverables/frozen-slice1-gates.apk`。
- 原 APK SHA256：`77821acd889e2f5edaf79f14f2e236d6c134ffc2809e1476206a3d49ccc9674d`。
- 原测试包 SHA256：`1a2aa95ad77619a20c911e1ab2cf36aade46d0d4e4128575dd14a3659af55810`。
- 新恢复版本独立提交，不改写上述历史；最终 source SHA / APK hash 记录在 `deliverables/recovery-artifact.json`。

## Implementation

1. `SessionRunState` 明确表达 RUNNING / PAUSED / RESUMING / STOPPED。`PetRuntime` 与持久化 SESSION_STATE 事件同步记录恢复状态。
2. 每次故障恢复最多 3 次探测、总窗口 120 秒，单次探测最多 30 秒且受剩余总窗口约束。锁屏期间不消耗探测次数，但仍计入总窗口；到期停止。恢复过程没有游戏输入。
3. `FileCheckpointStore` 使用 App 私有目录下的 `AtomicFile`：`files/pet-checkpoints/<sessionId>.json`。字段仅有 sessionId、round、phase、trustedPage、最近健康观测、观测时间、是否要求健康重读，以及 actionPending 安全标记。支持四种健康观测状态的完整序列化。
4. 恢复时加载并核对同一 Session 的 checkpoint；验证 display、当前新帧、0.97 页面模板，以及需要时重新读取的健康数据。旧缓存不能解除暂停。页面未知或不同、健康数据未知或过期、checkpoint 身份不符均 STOP。

`PetRecoveryBoundary` 是原实验 Session、原 FSM 和实机故障测试共用的恢复入口。原 capture / input 实现保留；输入前加入暂停检查和未确认动作标记。FrameTrustGate 仅在受控 RESUMING 期间重启有界采集，独立 Session 输入屏障保持关闭，直到全部验证通过。

动作已经发送、但动作后页面尚未确认时，checkpoint 标记 actionPending。此时停止，不自动重放或猜测动作结果。若恢复时课程自然结束、页面从 LEARNING 变成 RESULT，也按 checkpoint 不一致停止。checkpoint 目前支持同一活跃 Session 的安全恢复，不实现进程重启后的自动续跑。

原生产 FSM 的长时间等待改为约每秒一次有界画面观察，因此等待课程期间也能发现锁屏或 capture 故障。实验 Session 原有观察循环保留。课程时钟仍按真实经过时间计算，不因暂停而假定游戏计时暂停。

## Phone fault gates

设备 FOA-AL00 / Android 12，QQ 9.3.70（16410），display 4，720×1600 @ 320dpi。所有故障 Session 都没有向 QQ 输入；使用原恢复入口、真实 Shizuku 当前截图和原页面模板。

| Gate | Run | 实际结果 | physicalDelta | App 游戏输入 |
|---|---|---|---|---|
| Capture 中断恢复 | 1791256959629 | RUNNING → PAUSED → RESUMING（失败）→ PAUSED → RESUMING → RUNNING | 0 | 0 |
| 恢复页面不符 | 1791256988485 | PAUSED → RESUMING → STOPPED，`RECOVERY_PAGE_MISMATCH:HOME:HEALTH` | 0 | 0 |
| 人工锁屏 / 解锁 | 1791257067692 | 用户真实锁屏 → PAUSED → 用户解锁 → RESUMING → RUNNING | 0 | 0 |

Capture gate 在 App 测试传输层临时返回 capture 失败，正常 fast path 的等待窗口设为 0，保证故障不能被 ImageReader 偶发新帧掩盖。首次恢复仍保持故障；第二次解除故障后通过真实 Shizuku 捕获 display 4 并匹配首页。未重建或重绑显示器。

页面不符 gate 恢复时注入 `TEST_PAGE_FIXTURE` 健康页录制帧，验证 checkpoint HOME 与 HEALTH 不同即停止。它是手机内的确定性页面故障注入，不是声称 QQ 自行跳到了健康页。

人工锁屏 gate 通过 KeyguardManager 检测真实锁屏和解锁，USER 来源事件单独记录。PAUSED 到 RESUMING 约 46.7 秒，重新观察后恢复；未由 ADB 模拟按电源键。

三个故障 gate 均在 PAUSED / RESUMING 期间断言输入屏障关闭；恢复成功后校验 sessionId、round、phase 未变化。事件 sequence 分别为连续的 18 / 17 / 12 条，均无 INPUT_SENT。

## Regression

- unit：23/23，包括新增恢复测试 9/9。
- 原手机回归及 checkpoint 原子持久化：9/9。
- 手机故障注入：3/3。
- Static Shizuku capture：10/10，display 4，p50 759ms / p95 1553ms，physicalDelta=0。
- Hybrid capture：强制 ImageReader timeout 2 次，Shizuku fallback 2/2；打开、关闭健康面板均验证通过，physicalDelta=0。fallback p50 643ms / p95 963ms。
- `test`、`assembleDebug`、`assembleDebugAndroidTest` 成功；主 APK 和测试 APK 安装成功。
- 原学习页面录制帧回归通过；本轮没有新开课，也没有执行额外三轮。
- ImageReader fast path、Shizuku fallback、TemplateMatcher、0.97 阈值、displayId<=0 禁止输入、动作后重新观察、8 秒转场确认、SOAP 连续 MOVE 和默认健康保护均保留。

## Evidence and artifacts

- `evidence/recovery/`：测试结果、各 gate 的 verdict、checkpoint、连续事件日志、原始证据 SHA256 manifest。
- `deliverables/recovery-evidence.zip`：本轮 before / after 帧、事件及截图 gate 证据。
- 证据 ZIP SHA256：`2ce9bab5c1fb0971759856756e376617489cbcb4fc6ed123d5e32eb0015ac6be`。
- 新 APK：`deliverables/qqpet-recovery-debug.apk`。
- 新测试 APK：`deliverables/qqpet-recovery-gates.apk`。
- 最终提交及安装包 hashes：`deliverables/recovery-artifact.json`。

## Blockers and next step

阻塞项：无。恢复时若需要健康数据，但当前已知页面无法重新读取健康数值，则 STOP，不额外导航猜测。

下一步：在一轮已授权学习中验证同一恢复入口；保持页面一致才恢复，不扩展玩法。
