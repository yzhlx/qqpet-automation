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
                        body = "进入手机设置 → 开发者选项 → 开启无线调试；在 Shizuku 点「配对」，" +
                            "6 位配对码显示在系统的「使用配对码配对设备」弹窗里，下拉通知栏把数字填入 Shizuku 的配对通知。" +
                            "看到「配对成功」只代表配对完成，还要回 Shizuku 点「启动」，显示「已启动」才算可用。",
                    )
                    ManualStepItem(
                        step = "3",
                        title = "手机没有无线调试？（Android 10 或无 Wi-Fi）",
                        body = "Android 10 或无线调试一直失败时，用 USB 数据线连接电脑：手机开启 USB 调试，" +
                            "电脑装好 platform-tools（Android 开发者网站可下载）后执行\n" +
                            "adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh\n" +
                            "看到 Shizuku「已启动」即可。已 Root 可直接在 Shizuku 点「启动」；" +
                            "没有电脑且未 Root 时无法启动，只能先浏览功能。",
                    )
                    ManualStepItem(
                        step = "4",
                        title = "Shizuku 停止或断连怎么办？",
                        body = "手机重启、系统清理后台或手动停止，都可能让 Shizuku 退出。重新打开 Shizuku 点击「启动」，蟹啦会重新检测并恢复就绪；通常不需要重新输入配对码或重装。若系统清除了无线调试授权，按页面提示重新配对。",
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
                    ManualStepItem(
                        step = "签到",
                        title = "每日签到",
                        body = "在方案里勾选「每日签到」，蟹啦会打开签到页，先看今天是不是已经领过：已经领过就直接跳过，不会重复点；今天没领才领一次，"
                            + "领完核对到账再安全回房，结果写进记录。",
                    )
                    ManualStepItem(
                        step = "信箱",
                        title = "信箱待领奖励",
                        body = "勾选「信箱待领」后，蟹啦会去信箱页看有没有能明确识别的奖励：空信箱直接安全跳过，有奖励时只领认得出的那几项，"
                            + "读不出来的会跳过并写明原因，不会乱点。",
                    )
                    ManualStepItem(
                        step = "福袋",
                        title = "福袋任务",
                        body = "勾选「福袋任务」后，蟹啦会按你设的扫描间隔和今日上限去做福袋任务。福袋出问题不会连累整轮：它会被记成跳过，"
                            + "方案里后面的动作照常执行。",
                    )
                    PetNote("开始前请确保 QQ 已在后台登录养宠账号并停留在宠物房间。登录由你在 QQ 内完成，蟹啦不读取账号或密码；无法确认时会提示你先核对房间，不会替你猜测。")
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
                        body = "在主页点击「运行方案」，可以勾选每日签到、信箱待领、福袋任务、打工、学习、护理、冒险中的任意单项或多项。"
                            + "蟹啦不会默认强加未勾选的动作，完全遵循你的设定。",
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
                    ManualStepItem(
                        step = "7",
                        title = "优先模式与受阻转移",
                        body = "「仅金币不足时打工」打开是学习优先：金币够时先学习，金币不够才先打工；关闭就是打工优先，先打工再学习。再打开「受阻时按规则转移」，某个动作因为体力不足、金币不够或页面没认出来而受阻时，蟹啦不会让整轮停下，而是按你排的顺序继续做后面的动作，每轮最多转移一次。转移动了哪一步、后来做了什么，主页的策略说明里都会写清楚。",
                    )
                    ManualStepItem(
                        step = "8",
                        title = "属性目标与今日停止线",
                        body = "在「培养目标属性」里选力量、智力或魅力，蟹啦会自动换成对应课程的初级学园课。打开「今日停止线」还能设今天最多上几节课：达到上限后本轮自动跳过学习，方案里后面的动作照常做。只有已经确认上完的节数才计入今天的数量。",
                    )
                    ManualStepItem(
                        step = "9",
                        title = "均衡目标",
                        body = "想在三项属性之间均衡练习，就选「均衡」：第 1 节力量、第 2 节智力、第 3 节魅力，第 4 节回到力量，按这个顺序轮换上不同的课。学费和时长按每一节自己那门课计算。记录页会写明每个属性这轮上了几节、课程页上写明的收益；房间页不公开宠物当前的属性数值，所以那个数字读不到，蟹啦不会替你猜。均衡默认关闭，要用请自己选。",
                    )
                    ManualStepItem(
                        step = "10",
                        title = "课程收益比较",
                        body = "在课程列表下方点「读取课程收益」，蟹啦会逐门打开已解锁课程的详情页，只读学费和页面上写明的属性收益，不点开课、不花金币。读完后列表会显示每门课的学费与收益，并给出最省学费、每分钟最省、每点属性最省的比较；读不到或超过 7 天的数据会标为未知，不参与比较。比较只做提示，不会自动替你换课。",
                    )
                    ManualStepItem(
                        step = "11",
                        title = "岗位收益比较",
                        body = "在打工卡片下方点「读取岗位收益」，蟹啦会逐家进入已知职业的岗位页，"
                        + "只选中时长档读金币、工分与消耗，绝不点「去打工」，不花金币、不消耗体力。"
                        + "读完后每个岗位会写出每分钟金币与工分；只有已验证可执行且两个以上时才做排名，"
                        + "否则仍按安全默认岗位执行。某家职业没开放或读不全只会跳过并写明原因，"
                        + "已读到的岗位会保留；超过 7 天的数据视为未知，不参与比较。比较只做提示，不会自动替你换岗。",
                    )
                    ManualStepItem(
                        step = "12",
                        title = "中断后先确认，不重复扣费",
                        body = "任务被息屏、系统回收进程或手动打断时，蟹啦会先记下「这一步到底做没做还不确定」。"
                        + "下一次开始前会被拦下来，请到「运行方案」页的「待确认动作」里选「已经做完」或「没有发生」。"
                        + "选「已经做完」后今天不会再重复这个动作（学习会少上一节）；选「没有发生」则可以重新开始。"
                        + "确认之前蟹啦不会替你再交一次学费或再领一次奖励——未知不等于没发生。",
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
                        body = "如果需要立即结束托管，点击「停止托管」，蟹啦会立刻终止当前任务循环，释放输入，不再发送任何点击手势。"
                            + "任务运行时通知栏里有一条「蟹啦」通知：点上面的「停止」同样能立刻结束；点通知正文会回到 App，看当前进度和停止原因。",
                    )
                    ManualStepItem(
                        step = "夜间",
                        title = "夜里不想被打扰？",
                        body = "在「设置 → 夜间暂停」里打开开关，选好每天的暂停时段（例如 23:00 到次日 07:00，时间以 30 分钟为步进）。"
                            + "这段时间里点「开始托管」会被直接拦下，并告诉你到点时间；已经跑着的任务走到这个时段会先停在两步之间，"
                            + "等时间到了重新确认画面再继续。开始和结束时间相同等于没有设置窗口。"
                            + "设置好之后，主页「托管方案」上方会有一行状态：此刻是在暂停中、几点恢复，还是现在就可以开始。",
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
                        step = "症状",
                        title = "开始前提示「上一次被中断，无法确认」",
                        body = "上一次任务中途被息屏、回收或打断，那一步到底有没有扣费、有没有开课蟹啦并不知道。"
                        + "它不会替你猜，也不会直接再来一次：请到「运行方案」页的「待确认动作」里选「已经做完」或「没有发生」，"
                        + "确认后就能正常开始。若游戏里那一节其实还在上课，请等它上完再确认。",
                    )
                    ManualStepItem(
                        step = "症状",
                        title = "配对后一直没启动 / 找不到配对码通知",
                        body = "配对成功不等于 Shizuku 已启动：回到 Shizuku 主界面点「启动」，看到「已启动」才算完成。" +
                            "找不到输入配对码的通知：下拉通知栏找「使用配对码配对设备」；仍没有就回无线调试页面" +
                            "重新点「使用配对码配对设备」，再在 Shizuku 点「配对」。配对码过期只重做这一步，" +
                            "不用重装；蟹啦不会读取或保存配对码。",
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
