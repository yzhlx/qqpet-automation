package com.ynk.virtualdisplay.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ynk.virtualdisplay.ui.kit.PetCard
import com.ynk.virtualdisplay.ui.kit.PetChip
import com.ynk.virtualdisplay.ui.kit.PetDivider
import com.ynk.virtualdisplay.ui.kit.PetFootButton
import com.ynk.virtualdisplay.ui.kit.PetGlyph
import com.ynk.virtualdisplay.ui.kit.PetIcon
import com.ynk.virtualdisplay.ui.kit.PetNote
import com.ynk.virtualdisplay.ui.kit.PetSection
import com.ynk.virtualdisplay.ui.kit.PetButtonTone
import com.ynk.virtualdisplay.ui.main.PetTitleBar
import com.ynk.virtualdisplay.ui.theme.PetTokens

/**
 * 托管方案. This screen exists so users can see what a batch will really do, and so the reference's
 * plan sheet has an honest home.
 *
 * The three 日常维持 rows describe behaviour the automation layer actually performs, so they are
 * shown as 已启用, and the switches are rendered as a READ-ONLY state indicator — there is no
 * per-feature enable flag in the engine to write to, and faking one would misrepresent capability.
 * Everything else is 暂未开放 with no operable control at all.
 */
@Composable
fun PetPlanSheetScreen(onClose: () -> Unit, onOpenConsole: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        PetTitleBar("托管方案", onBack = onClose)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 15.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            PetNote("以下是你点「开始托管」后真实会执行的内容。未启用的项目不执行，也不会显示为已完成。")

            PlanGroup(
                title = "日常维持",
                chip = "已启用" to PlanChipTone.GREEN,
                rows = listOf(
                    PlanRow(PetGlyph.Care, "基础护理", "体力或清洁低于阈值时自动恢复", enabled = true),
                    PlanRow(PetGlyph.Study, "自动学习", "进入学院上课，自动鼓励与续课", enabled = true),
                    PlanRow(PetGlyph.Brush, "开课前自动护理", "状态不足先恢复到阈值再开课", enabled = true),
                ),
            )

            PlanGroup(
                title = "收益行动",
                chip = "暂未开放" to PlanChipTone.GRAY,
                rows = listOf(
                    PlanRow(PetGlyph.Coin, "打工 / 工分", "预计下一版开放", enabled = false),
                    PlanRow(PetGlyph.Coin, "PK · 福袋 · 冒险", "预计下一版开放", enabled = false),
                ),
            )

            PlanGroup(
                title = "社交互动",
                chip = "暂未开放" to PlanChipTone.GRAY,
                rows = listOf(
                    PlanRow(PetGlyph.Friend, "串门 · 踩踩 · 回踩", "预计下一版开放", enabled = false),
                ),
            )

            PetCard {
                PetSection("执行边界")
                Spacer(Modifier.height(8.dp))
                listOf(
                    "单批 1–3 节，每节约 10 分钟",
                    "开课前体力与清洁都必须 ≥ 90，否则先护理",
                    "心情只观察记录，不参与判定",
                    "检测到异常自动暂停并尝试恢复，期间不发送输入",
                    "无法确认页面时安全停止，绝不盲点",
                ).forEach { line ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                        Box(Modifier.padding(top = 6.dp).size(5.dp).background(PetTokens.Ink3, CircleShape))
                        Spacer(Modifier.width(9.dp))
                        Text(line, fontSize = 12.5.sp, color = PetTokens.Ink2, lineHeight = 17.sp)
                    }
                }
                Spacer(Modifier.height(8.dp))
                PetDivider()
                Spacer(Modifier.height(9.dp))
                PetNote("这些行为没有独立开关：引擎以固定策略执行，界面只做说明。需要改动请更新校准与代码。")
            }

            Spacer(Modifier.height(4.dp))
        }
        Column(Modifier.padding(horizontal = 15.dp).padding(top = 9.dp, bottom = 12.dp)) {
            PetFootButton("返回托管", PetButtonTone.PRIMARY, onClose, Modifier.fillMaxWidth())
        }
    }
}

private enum class PlanChipTone { GREEN, GRAY }

private data class PlanRow(val glyph: PetGlyph, val name: String, val desc: String, val enabled: Boolean)

@Composable
private fun PlanGroup(title: String, chip: Pair<String, PlanChipTone>, rows: List<PlanRow>) {
    PetCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PetSection(title)
            Spacer(Modifier.weight(1f))
            when (chip.second) {
                PlanChipTone.GREEN -> PetChip(chip.first, PetTokens.GreenSoft, PetTokens.GreenInk)
                PlanChipTone.GRAY -> PetChip(chip.first, PetTokens.GraySoft, PetTokens.Ink2)
            }
        }
        Spacer(Modifier.height(8.dp))
        rows.forEachIndexed { index, row ->
            if (index > 0) PetDivider(Modifier.padding(vertical = 2.dp))
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(33.dp).background(
                        if (row.enabled) PetTokens.BlueSoft else PetTokens.GraySoft,
                        RoundedCornerShape(PetTokens.TileRadius),
                    ),
                    contentAlignment = Alignment.Center,
                ) { PetIcon(row.glyph, 18.dp, if (row.enabled) null else PetTokens.Ink3) }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        row.name, fontSize = 14.sp, fontWeight = FontWeight(650),
                        color = if (row.enabled) PetTokens.Ink else PetTokens.Ink3,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(row.desc, fontSize = 11.5.sp,
                        color = if (row.enabled) PetTokens.Ink3 else androidx.compose.ui.graphics.Color(0xFFB4B9C4))
                }
                Spacer(Modifier.width(8.dp))
                ReadOnlySwitch(row.enabled)
            }
        }
    }
}

/**
 * A switch-shaped state indicator with no click handler. It shows the engine's fixed behaviour and
 * is deliberately not interactive, so it can never imply a setting that does not exist.
 */
@Composable
private fun ReadOnlySwitch(on: Boolean) {
    Box(
        Modifier.width(42.dp).height(25.dp)
            .background(
                if (on) PetTokens.Blue else PetTokens.HandleGray,
                RoundedCornerShape(PetTokens.PillRadius),
            )
            .padding(3.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(19.dp).background(PetTokens.Surface, CircleShape))
    }
}
