package com.ynk.virtualdisplay.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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

enum class TutorialStage(val title: String, val subtitle: String) {
    ROOM_PRECHECK("1. 房间状态检测", "先确认宠物房间画面，再开始任务"),
    PLAN_CONFIG("2. 方案与预算展示", "明确动作、次数、预计时长与资源预算"),
    RUNNING_PAUSE("3. 运行中与两步间暂停", "区分操作中与等待中，暂停在两步之间生效"),
    RESULT_CONFIRM("4. 结果结算与真实核对", "核对到账明细，不把部分完成当作全部成功"),
}

/**
 * 可选使用示例与教学演示：
 * 明确标注「可跳过」，跳过按钮直接可见；不强制试跑、不消耗金币、不自动执行真实任务。
 */
@Composable
fun PetTutorialScreen(
    onClose: () -> Unit,
    onFinished: () -> Unit,
) {
    var currentStage by remember { mutableStateOf(TutorialStage.ROOM_PRECHECK) }
    var pausedInDemo by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(PetTokens.Bg)) {
        // 顶部导航栏，右侧直接放置高亮的「跳过教学」按钮
        Box(Modifier.fillMaxWidth()) {
            PetTitleBar(
                title = "使用示例教学",
                onBack = {
                    onFinished()
                    onClose()
                },
                subtitle = "演示模拟，不消耗金币、不触发真实操作",
                crab = R.drawable.crab_wave,
            )

            // 跳过教学按钮，任何时候直接可见
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 16.dp, top = 14.dp)
                    .clip(RoundedCornerShape(PetTokens.ChipRadius))
                    .background(PetTokens.Surface)
                    .clickable {
                        onFinished()
                        onClose()
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    "跳过教学",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = PetTokens.Ink2,
                )
            }
        }

        // 阶段进度指示条
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 15.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TutorialStage.entries.forEach { stage ->
                val active = stage == currentStage
                val past = stage.ordinal < currentStage.ordinal
                Box(
                    Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(
                            when {
                                active -> PetTokens.Blue
                                past -> PetTokens.BlueSoft
                                else -> PetTokens.Line
                            }
                        )
                )
            }
        }

        // 演示内容卡片
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 15.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 当前阶段标题
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(PetTokens.BlueSoft)
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        "步骤 " + (currentStage.ordinal + 1) + " / 4",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = PetTokens.BlueInk,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(currentStage.title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = PetTokens.Ink)
                    Text(currentStage.subtitle, fontSize = 12.sp, color = PetTokens.Ink2)
                }
            }

            // 模拟画面与解释
            when (currentStage) {
                TutorialStage.ROOM_PRECHECK -> {
                    PetCard {
                        PetSection("模拟演示：后台副屏画面")
                        Spacer(Modifier.height(8.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(140.dp)
                                .clip(RoundedCornerShape(PetTokens.TileRadius))
                                .background(PetTokens.FilmTop)
                                .padding(12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("【虚拟副屏 720×1600】", fontSize = 11.sp, color = PetTokens.Ink3)
                                Spacer(Modifier.height(6.dp))
                                Text("QQ 宠物房间识别成功", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = PetTokens.GreenInk)
                                Spacer(Modifier.height(4.dp))
                                Text("当前金币: 741 · 清洁: 良好 · 体力: 充沛", fontSize = 12.sp, color = PetTokens.Ink2)
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "• 蟹啦全程在独立虚拟屏中运行，绝不抢占你的手机主屏。\n" +
                            "• 每次任务开始前，会自动核对宠物是否在房间内；若出现陌生弹窗会自动停住，绝不盲目点击。",
                            fontSize = 13.sp,
                            color = PetTokens.Ink,
                            lineHeight = 19.sp,
                        )
                    }
                }

                TutorialStage.PLAN_CONFIG -> {
                    PetCard {
                        PetSection("模拟演示：方案配置与预算")
                        Spacer(Modifier.height(8.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(PetTokens.TileRadius))
                                .background(PetTokens.Surface)
                                .padding(12.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("已选动作：帮武馆跑腿（打工 1 次，10 分钟）", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = PetTokens.Ink)
                                Text("预期收益：+71 金币 · 预计消耗：0 金币", fontSize = 12.sp, color = PetTokens.GreenInk)
                                Text("每日防超支预算：最多花费 200 金币", fontSize = 12.sp, color = PetTokens.Ink2)
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "• 蟹啦绝不默认勾选未选动作，完全由你选择打工、学习或护理。\n" +
                            "• 开始前卡片清晰展示动作、次数、预计时长与资源要求；无法确认的数据会标注未知，不把估算当作确认余额。",
                            fontSize = 13.sp,
                            color = PetTokens.Ink,
                            lineHeight = 19.sp,
                        )
                    }
                }

                TutorialStage.RUNNING_PAUSE -> {
                    PetCard {
                        PetSection("模拟演示：运行状态与两步间暂停")
                        Spacer(Modifier.height(8.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(PetTokens.TileRadius))
                                .background(if (pausedInDemo) PetTokens.AmberSoft else PetTokens.BlueSoft)
                                .padding(12.dp)
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column {
                                    Text(
                                        if (pausedInDemo) "已暂停：做完当前这一步就暂停" else "正在打工中（剩余约 8 分钟）",
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (pausedInDemo) PetTokens.AmberInk else PetTokens.BlueInk,
                                    )
                                    Text(
                                        "区分操作中与等待中，状态明确反馈",
                                        fontSize = 11.5.sp,
                                        color = PetTokens.Ink2,
                                    )
                                }
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(PetTokens.ChipRadius))
                                        .background(PetTokens.Surface)
                                        .clickable { pausedInDemo = !pausedInDemo }
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        if (pausedInDemo) "取消暂停" else "点我模拟暂停",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = PetTokens.Ink,
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "• 为什么点暂停不会瞬间中断？因为中途强行中断会导致打工报废或扣费损失！\n" +
                            "• 暂停指令在【两步之间】安全生效：当前课程或打工下课后，停在房间等你点继续。\n" +
                            "• 继续时会先复核画面，在 QQ 换过页面也能安全走回房间。",
                            fontSize = 13.sp,
                            color = PetTokens.Ink,
                            lineHeight = 19.sp,
                        )
                    }
                }

                TutorialStage.RESULT_CONFIRM -> {
                    PetCard {
                        PetSection("模拟演示：真实结果核对")
                        Spacer(Modifier.height(8.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(PetTokens.TileRadius))
                                .background(PetTokens.GreenSoft)
                                .padding(12.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("打工结算完成：工资明细已到账", fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = PetTokens.GreenInk)
                                Text("实际获得金币：+71（房间金币 741 → 812，实测一致）", fontSize = 12.sp, color = PetTokens.Ink)
                                Text("状态：全部完成 · 0 次失败 · 0 次跳过", fontSize = 12.sp, color = PetTokens.Ink2)
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "• 蟹啦对每次打工与学习均做真实到账与成绩单双重核对。\n" +
                            "• 结果明确区分实际完成、跳过与失败，绝不把部分完成包装成全部成功！",
                            fontSize = 13.sp,
                            color = PetTokens.Ink,
                            lineHeight = 19.sp,
                        )
                    }
                }
            }

            PetNote("你可随时在设置中的「使用攻略」重新查看图文步骤或回看此演示。")
            Spacer(Modifier.height(10.dp))
        }

        // 底部动作栏
        Row(
            Modifier
                .fillMaxWidth()
                .background(PetTokens.Surface)
                .padding(horizontal = 15.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 跳过/稍后按钮
            Box(
                Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(PetTokens.BtnRadius))
                    .background(PetTokens.GraySoft)
                    .clickable {
                        onFinished()
                        onClose()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text("跳过教学，直接开始", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = PetTokens.Ink)
            }

            // 下一步 / 完成按钮
            val isLast = currentStage == TutorialStage.RESULT_CONFIRM
            Box(
                Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(PetTokens.BtnRadius))
                    .background(PetTokens.Blue)
                    .clickable {
                        if (isLast) {
                            onFinished()
                            onClose()
                        } else {
                            val nextIdx = currentStage.ordinal + 1
                            currentStage = TutorialStage.entries[nextIdx]
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (isLast) "完成演示，开始使用" else "下一步",
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = PetTokens.Surface,
                )
            }
        }
    }
}
