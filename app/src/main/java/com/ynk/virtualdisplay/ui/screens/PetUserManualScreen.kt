package com.ynk.virtualdisplay.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ynk.virtualdisplay.R
import com.ynk.virtualdisplay.ui.kit.*
import com.ynk.virtualdisplay.ui.main.PetTitleBar
import com.ynk.virtualdisplay.ui.theme.PetTokens

enum class ManualSection(val title: String, val glyph: PetGlyph) {
    INSTALL_AUTH("首次安装与授权", PetGlyph.Shield),
    CORE_ACTIONS("开始打工／学习／护理", PetGlyph.Play),
    PLAN_CONFIG("配置托管方案", PetGlyph.Clock),
    PAUSE_RESUME("暂停、继续和停止", PetGlyph.Warn),
    TROUBLESHOOTING("常见问题与排故", PetGlyph.Bug),
    LIMITS("运行限制与说明", PetGlyph.Info),
}

/**
 * 设置中的「使用攻略」：随应用离线提供，无需网络即可查阅。
 * 按用户目标组织目录，支持从相关功能入口直达对应段落。
 */
@Composable
fun PetUserManualScreen(
    onClose: () -> Unit,
    targetSection: ManualSection? = null,
    onOpenTutorial: (() -> Unit)? = null,
    onOpenDiagnostic: (() -> Unit)? = null,
) {
    var selectedSection by remember { mutableStateOf(targetSection) }
    val scrollState = rememberScrollState()

    Column(Modifier.fillMaxSize().background(PetTokens.Bg)) {
        PetTitleBar(
            title = "使用攻略",
            onBack = onClose,
            subtitle = "随时查阅使用指引、方案配置与故障排查",
            crab = R.drawable.crab_wave,
        )

        // 顶部分类筛选标签
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 15.dp, vertical = 6.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val allSelected = selectedSection == null
            Box(
                Modifier
                    .clip(RoundedCornerShape(PetTokens.ChipRadius))
                    .background(if (allSelected) PetTokens.Blue else PetTokens.Surface)
                    .clickable { selectedSection = null }
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    "全部",
                    fontSize = 13.sp,
                    fontWeight = if (allSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (allSelected) PetTokens.Surface else PetTokens.Ink,
                )
            }

            ManualSection.entries.forEach { sec ->
                val active = selectedSection == sec
                Box(
                    Modifier
                        .clip(RoundedCornerShape(PetTokens.ChipRadius))
                        .background(if (active) PetTokens.Blue else PetTokens.Surface)
                        .clickable { selectedSection = sec }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        sec.title,
                        fontSize = 13.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        color = if (active) PetTokens.Surface else PetTokens.Ink,
                    )
                }
            }
        }

        // 攻略内容区
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(scrollState)
                .padding(horizontal = 15.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 可选教学快捷入口
            if (onOpenTutorial != null) {
                PetCard(pad0 = false) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("想直观了解运行过程？", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = PetTokens.Ink)
                            Spacer(Modifier.height(3.dp))
                            Text("无需消耗金币、不触发真实操作，30 秒模拟演示核心流程。", fontSize = 12.sp, color = PetTokens.Ink2)
                        }
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(PetTokens.ChipRadius))
                                .background(PetTokens.BlueSoft)
                                .clickable { onOpenTutorial() }
                                .padding(horizontal = 12.dp, vertical = 7.dp)
                        ) {
                            Text("查看示例演练", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = PetTokens.BlueInk)
                        }
                    }
                }
            }

            // 1. 首次安装与授权
            if (selectedSection == null || selectedSection == ManualSection.INSTALL_AUTH) {
                PetCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PetIcon(PetGlyph.Shield, 18.dp)
                        Spacer(Modifier.width(8.dp))
                        PetSection("一、首次安装与授权")
                    }
                    Spacer(Modifier.height(8.dp))
                    ManualStepItem(
                        step = "1",
                        title = "为什么需要 Shizuku 配套工具？",
                        body = "蟹啦使用 Android 官方提供的后台副屏技术进行自动化托管，不会占用你的手机主屏幕。创建虚拟副屏与后台画面识别需要系统权限，这正是由 Shizuku 提供的安全桥梁。",
                    )
                    ManualStepItem(
                        step = "2",
                        title = "无线调试配对（Android 11+）",
                        body = "进入手机设置 → 开发者选项 → 开启无线调试 → 点击「使用配对码配对」；在 Shizuku 弹出的通知栏中输入 6 位配对码即可。配对仅需一次，日常只需在 Shizuku 点「启动」。",
                    )
                    ManualStepItem(
                        step = "3",
                        title = "手机没有无线调试？（Android 10 或无 Wi-Fi）",
                        body = "Android 10 系统未内置无线调试，需要通过电脑数据线连接手机，运行一次官方 ADB 启动脚本即可启动 Shizuku。如果设备已 Root，则可直接一键授权启动。",
                    )
                    ManualStepItem(
                        step = "4",
                        title = "手机重启后怎么办？",
                        body = "手机关机重启后系统安全限制会自动停止后台服务。重启后只需打开 Shizuku 点击「启动」，蟹啦就会自动恢复就绪，不需要重新输入配对码或重新安装。",
                    )
                    PetNote("授权拒绝后可在 Shizuku 授权管理列表中重新勾选「蟹啦」。")
                }
            }

            // 2. 开始打工、学习与护理
            if (selectedSection == null || selectedSection == ManualSection.CORE_ACTIONS) {
                PetCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PetIcon(PetGlyph.Play, 18.dp)
                        Spacer(Modifier.width(8.dp))
                        PetSection("二、开始打工、学习、护理与冒险")
                    }
                    Spacer(Modifier.height(8.dp))
                    ManualStepItem(
                        step = "打工",
                        title = "岗位选择与自动领取工资",
                        body = "进入职业小镇选择已符合属性条件的岗位（如武馆跑腿、文职等）。开工前蟹啦会确认房间金币，打工完成后自动等待工资明细弹窗，核对到账金币并安全返回房间。",
                    )
                    ManualStepItem(
                        step = "学习",
                        title = "初级学园选课与成绩单确认",
                        body = "进入学院按目标属性选择课程（支持智力、魅力、力量）。课后蟹啦会等待课程面板停稳，准确识别成绩单并点返回，不会因动画滑动中途判断错误而卡住。",
                    )
                    ManualStepItem(
                        step = "护理",
                        title = "清洁与饱食状态维持",
                        body = "当清洁度或体力低于阈值时，自动执行洗澡或喂食动作。如背包香皂或饼干不足，会安全提示并不盲目购买扣费。",
                    )
                    ManualStepItem(
                        step = "冒险",
                        title = "基础自动冒险与安全退出 (CORE-11)",
                        body = "经户外地图进入「冒险」，支持「附近走走」短途探索与「诗和远方」旅行。开跑前自动检查体力与今日次数，受阻时安全停止；完成后自动核对奇遇结算与金币明细，安全返回房间。",
                    )
                    PetNote("开始前请确保 QQ 已在后台登录养宠账号并停留在宠物房间。")
                }
            }

            // 3. 配置托管方案
            if (selectedSection == null || selectedSection == ManualSection.PLAN_CONFIG) {
                PetCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PetIcon(PetGlyph.Clock, 18.dp)
                        Spacer(Modifier.width(8.dp))
                        PetSection("三、配置托管方案")
                    }
                    Spacer(Modifier.height(8.dp))
                    ManualStepItem(
                        step = "1",
                        title = "自由组合动作与排序",
                        body = "在主页点击「运行方案」，可以勾选打工、学习、护理中的任意单项或多项。蟹啦不会默认强加未勾选的动作，完全遵循你的设定。",
                    )
                    ManualStepItem(
                        step = "2",
                        title = "设置学习节数与打工次数",
                        body = "多轮连续学习（如连续上 2 节课）会在每节结束后自动核对剩余节数并接续开课，全部完成后统一结算。",
                    )
                    ManualStepItem(
                        step = "3",
                        title = "每日防超支预算",
                        body = "可设置每日学习金币净消耗上限或打工时长。达到预设限制后蟹啦会自动安全结束任务，防止消耗过多金币。",
                    )
                    ManualStepItem(
                        step = "4",
                        title = "金币底线与花费上限",
                        body = "在「金币保护」里可以打开金币底线（扣完学费后余额不得低于该值）和每日学习花费上限。今日已确认花费 + 本次方案学费超过上限时，蟹啦会在开课前安全停下，不会先扣了钱再报错。",
                    )
                    ManualStepItem(
                        step = "5",
                        title = "任务节奏：间隔与失败冷却",
                        body = "在「任务节奏」里可以设置相邻动作之间的最小间隔，以及失败后的冷却时间。冷却期间再次启动同一方案会被直接拦下并提示还需等待多久，避免失败后反复空转。",
                    )
                    ManualStepItem(
                        step = "6",
                        title = "本轮策略说明",
                        body = "每一轮托管结束后，主页会列出这轮做成了哪些动作、哪些被跳过以及原因、哪些是方案里没勾选的。记录页的历史详情也会带上同一句摘要，方便回头核对蟹啦当时做了什么。",
                    )
                    PetNote("任务开始前卡片会明确展示预估动作、次数与预期时长。")
                }
            }

            // 4. 暂停、继续和停止
            if (selectedSection == null || selectedSection == ManualSection.PAUSE_RESUME) {
                PetCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PetIcon(PetGlyph.Warn, 18.dp)
                        Spacer(Modifier.width(8.dp))
                        PetSection("四、暂停、继续和停止")
                    }
                    Spacer(Modifier.height(8.dp))
                    ManualStepItem(
                        step = "暂停",
                        title = "点「暂停」后为什么没有马上停住？",
                        body = "为了防止中断扣费或导致打工/课程中途报废，暂停指令在【两步之间】生效。例如第 1 节课正在上时点暂停，蟹啦会安静等第 1 节课下课并确认成绩后才停在房间等你。",
                    )
                    ManualStepItem(
                        step = "继续",
                        title = "点「继续」后会发生什么？",
                        body = "点击继续后，蟹啦会先重新识别当前画面是否仍为房间。如果中途你在 QQ 换了页面，它会安全走回房间再开始下一项，避免误操作。",
                    )
                    ManualStepItem(
                        step = "停止",
                        title = "紧急停止",
                        body = "如果需要立即结束托管，点击「停止托管」，蟹啦会立刻终止当前任务循环，释放输入，不再发送任何点击手势。",
                    )
                }
            }

            // 5. 常见问题与排故
            if (selectedSection == null || selectedSection == ManualSection.TROUBLESHOOTING) {
                PetCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PetIcon(PetGlyph.Bug, 18.dp)
                        Spacer(Modifier.width(8.dp))
                        PetSection("五、常见问题与排故")
                    }
                    Spacer(Modifier.height(8.dp))
                    ManualStepItem(
                        step = "症状",
                        title = "提示「页面不匹配」安全停止",
                        body = "这是蟹啦的安全保护机制：当画面出现陌生弹窗（如网络错误、活动弹窗或断网）时，蟹啦会停止盲目点击以保护账号安全。关闭弹窗返回房间后即可重新开始。",
                    )
                    ManualStepItem(
                        step = "症状",
                        title = "打工结束时断网，工资丢了吗？",
                        body = "没有丢。QQ 宠物打工是服务端计时，网络恢复后重新打开宠物房间或进入武馆，未结算的工资弹窗会自动补发给你的账号。",
                    )
                    ManualStepItem(
                        step = "排查",
                        title = "使用分层诊断定位问题",
                        body = "遇到任何无法启动或检测失败，前往设置点击「检查运行环境」，系统会自动自检基础服务、虚拟副屏与帧捕获能力，并给出具体恢复建议。",
                    )
                    if (onOpenDiagnostic != null) {
                        Spacer(Modifier.height(6.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(PetTokens.TileRadius))
                                .background(PetTokens.BlueSoft)
                                .clickable { onOpenDiagnostic() }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("立即运行环境分层诊断", fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = PetTokens.BlueInk)
                        }
                    }
                }
            }

            // 6. 运行限制与说明
            if (selectedSection == null || selectedSection == ManualSection.LIMITS) {
                PetCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PetIcon(PetGlyph.Info, 18.dp)
                        Spacer(Modifier.width(8.dp))
                        PetSection("六、运行限制与说明")
                    }
                    Spacer(Modifier.height(8.dp))
                    ManualStepItem(
                        step = "锁屏",
                        title = "保持屏幕常亮",
                        body = "大部分 Android 系统在手机锁屏后会切断副屏图形渲染通道并休眠后台进程。长任务请保持屏幕亮起，建议插上充电器并延长系统自动熄屏时间。",
                    )
                    ManualStepItem(
                        step = "网络",
                        title = "保持网络稳定",
                        body = "QQ 宠物各动作均需与腾讯服务器通信，打工或学习期间如 Wi-Fi 断开，会导致无法收到结算页面。",
                    )
                    ManualStepItem(
                        step = "后台",
                        title = "电池优化无限制",
                        body = "请在系统设置中将蟹啦的省电策略设为「无限制」，防止系统在多任务切换时误杀后台托管服务。",
                    )
                    PetNote("托管画面固定为 720×1600 虚拟分辨率，与主屏幕分辨率和机型无关。")
                }
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun ManualStepItem(step: String, title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(PetTokens.BlueSoft)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(step, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = PetTokens.BlueInk)
            }
            Spacer(Modifier.width(8.dp))
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = PetTokens.Ink)
        }
        Spacer(Modifier.height(4.dp))
        Text(body, fontSize = 13.sp, color = PetTokens.Ink2, lineHeight = 19.sp)
    }
}
