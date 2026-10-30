package com.ynk.virtualdisplay.floating

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ynk.virtualdisplay.ui.theme.PetTokens

@Composable
fun CapsuleBubble(
    model: CapsuleUiModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = accentOf(model.visual)
    Box(
        modifier
            .size(40.dp)
            .shadow(8.dp, CircleShape)
            .clip(CircleShape)
            .background(PetTokens.Surface)
            .border(2.5.dp, accent, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(bubbleGlyph(model.visual), fontSize = 14.sp, color = accent, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun CapsuleExpandedCard(
    model: CapsuleUiModel,
    onCollapse: () -> Unit,
    onOpenApp: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DisposableEffect(Unit) {
        val token = DisplayPreviewHub.retain()
        onDispose { token.close() }
    }
    val frame by DisplayPreviewHub.frame.collectAsState()
    var previewExpanded by remember { mutableStateOf(false) }
    val accent = accentOf(model.visual)

    Column(
        modifier
            .width(if (previewExpanded) 320.dp else 300.dp)
            .shadow(16.dp, RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            .background(PetTokens.Surface)
            .border(1.dp, PetTokens.Line, RoundedCornerShape(18.dp))
            .padding(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(model.title, color = PetTokens.Ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(model.statusText + " · " + model.durationText, color = accent, fontSize = 12.sp)
            }
            Text(
                "收起",
                color = PetTokens.Ink2,
                fontSize = 12.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(PetTokens.GraySoft)
                    .clickable(onClick = onCollapse)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }

        Spacer(Modifier.height(10.dp))

        Row(
            Modifier
                .fillMaxWidth()
                .height(if (previewExpanded) 280.dp else 148.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier
                    .width(if (previewExpanded) 140.dp else 84.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(12.dp))
                    .background(PetTokens.GraySoft)
                    .clickable { previewExpanded = !previewExpanded },
            ) {
                val image = remember(frame?.generation) {
                    frame?.bitmap?.takeIf { !it.isRecycled }?.asImageBitmap()
                }
                if (image != null) {
                    Image(
                        bitmap = image,
                        contentDescription = "虚拟屏缩略图",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.TopCenter,
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("暂无画面", color = PetTokens.Ink3, fontSize = 11.sp)
                    }
                }
            }

            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                InfoLine("阶段", model.flowText)
                InfoLine("步骤", model.stepText)
                InfoLine("轮次", model.roundText)
                InfoLine("下一步", model.nextStepText)
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CapsuleActionButton("回到 QQPet", PetTokens.BlueSoft, PetTokens.BlueInk, Modifier.weight(1f), onOpenApp)
            if (model.canStop) {
                CapsuleActionButton("停止托管", PetTokens.RedSoft, PetTokens.RedInk, Modifier.weight(1f), onStop)
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Column {
        Text(label, color = PetTokens.Ink3, fontSize = 10.sp)
        Text(value.ifBlank { "—" }, color = PetTokens.Ink, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun CapsuleActionButton(
    text: String,
    background: Color,
    foreground: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = foreground, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun accentOf(visual: CapsuleVisualState): Color = when (visual) {
    CapsuleVisualState.RUNNING -> PetTokens.Green
    CapsuleVisualState.WAITING -> PetTokens.Blue
    CapsuleVisualState.PAUSED -> PetTokens.AmberStrong
    CapsuleVisualState.RESUMING -> PetTokens.Violet
    CapsuleVisualState.COMPLETED -> PetTokens.BlueInk
    CapsuleVisualState.FAILED -> PetTokens.Red
    CapsuleVisualState.STOPPED -> PetTokens.Ink3
}

private fun bubbleGlyph(visual: CapsuleVisualState): String = when (visual) {
    CapsuleVisualState.RUNNING -> "▶"
    CapsuleVisualState.WAITING -> "…"
    CapsuleVisualState.PAUSED -> "Ⅱ"
    CapsuleVisualState.RESUMING -> "↻"
    CapsuleVisualState.COMPLETED -> "✓"
    CapsuleVisualState.FAILED -> "!"
    CapsuleVisualState.STOPPED -> "■"
}
