package com.ynk.virtualdisplay.ui.kit

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ynk.virtualdisplay.ui.theme.PetTokens

/**
 * Shared v0.1 design primitives. Every pet screen is built from these so the three tabs stay
 * visually identical to the redesign-v0.1 references. No image library: evidence PNGs are decoded
 * with [android.graphics.BitmapFactory] directly (see [rememberEvidenceBitmap]).
 */

val PetMono = FontFamily.Monospace

@Composable
fun PetCard(
    modifier: Modifier = Modifier,
    pad0: Boolean = false,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(PetTokens.Surface, RoundedCornerShape(PetTokens.CardRadius))
            .padding(if (pad0) PaddingValues(horizontal = 14.dp, vertical = 13.dp) else PaddingValues(14.dp)),
    ) { content() }
}

/** Section eyebrow label ("本次方案", "运行截图" …). */
@Composable
fun PetSection(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = PetTokens.Ink2,
        letterSpacing = 0.3.sp)
}

/** Small muted note. */
@Composable
fun PetNote(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, fontSize = 11.5.sp, color = PetTokens.Ink3, lineHeight = 17.sp)
}

/** Card header row: section title on the left, an optional muted note on the right. */
@Composable
fun PetRowHeader(section: String, note: String? = null, noteColor: Color = PetTokens.Ink3) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        PetSection(section)
        if (note != null) Text(note, fontSize = 11.5.sp, color = noteColor)
    }
}

private val pillShapes = RoundedCornerShape(PetTokens.PillRadius)

/** Status pill with a leading dot, matching the reference `.pill` tokens. */
@Composable
fun PetPill(text: String, background: Color, foreground: Color, withDot: Boolean = true, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(background, pillShapes)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (withDot) Box(Modifier.size(7.dp).background(foreground, CircleShape))
        Spacer(Modifier.width(5.dp))
        Text(text, fontSize = 11.5.sp, fontWeight = FontWeight(650), color = foreground)
    }
}

/** Chip: the compact pill without a dot, for inline status tags. */
@Composable
fun PetChip(text: String, background: Color, foreground: Color, modifier: Modifier = Modifier) {
    Text(
        text, modifier
            .background(background, pillShapes)
            .padding(horizontal = 7.dp, vertical = 2.5.dp),
        fontSize = 10.5.sp, fontWeight = FontWeight(650), color = foreground, maxLines = 1,
    )
}

@Composable
fun PetDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(PetTokens.Line))
}

/**
 * List row: leading tile, title + sub, trailing composable, optional chevron. Matches `.lr`/`.grp`
 * in the reference. [trailing] sits at the right edge; [showChevron] adds the grey arrow.
 */
@Composable
fun PetListRow(
    tileBg: Color,
    icon: @Composable () -> Unit,
    title: String,
    sub: String? = null,
    dimmed: Boolean = false,
    showChevron: Boolean = false,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(33.dp).background(tileBg, RoundedCornerShape(PetTokens.TileRadius)),
            contentAlignment = Alignment.Center) { icon() }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight(650),
                color = if (dimmed) PetTokens.Ink3 else PetTokens.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null) {
                Spacer(Modifier.height(3.dp))
                Text(sub, fontSize = 11.5.sp, color = if (dimmed) PetTokens.Chevron else PetTokens.Ink3,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
        if (showChevron) {
            Spacer(Modifier.width(8.dp))
            PetChevron()
        }
    }
}

/** Compact 14px chevron used on group/list rows. */
@Composable
fun PetChevron(modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier.size(15.dp)) {
        val s = size.width
        val cap = androidx.compose.ui.graphics.StrokeCap.Round
        drawLine(PetTokens.Chevron, androidx.compose.ui.geometry.Offset(s * 0.37f, s * 0.22f),
            androidx.compose.ui.geometry.Offset(s * 0.66f, s * 0.5f), s * 0.11f, cap = cap)
        drawLine(PetTokens.Chevron, androidx.compose.ui.geometry.Offset(s * 0.66f, s * 0.5f),
            androidx.compose.ui.geometry.Offset(s * 0.37f, s * 0.78f), s * 0.11f, cap = cap)
    }
}

/** Filled status dot for the 3-stat header. */
@Composable
fun PetDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(7.dp).background(color, CircleShape))
}

/** The big "≈" estimate marker that precedes every on-screen reading. */
@Composable
fun PetApproxValue(prefix: Boolean, value: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        Text(if (prefix) "≈" else "", fontSize = 15.sp, fontWeight = FontWeight(600), color = PetTokens.Ink3)
        Text(value, fontSize = 23.sp, fontWeight = FontWeight.Bold, color = PetTokens.Ink,
            fontFamily = PetMono, letterSpacing = (-0.4).sp, lineHeight = 24.sp)
    }
}

/** Progress bar matching `.bar`: 9px track, blue rounded fill. */
@Composable
fun PetProgressBar(fraction: Float, modifier: Modifier = Modifier, color: Color = PetTokens.Blue) {
    val f = fraction.coerceIn(0f, 1f)
    Box(modifier.fillMaxWidth().height(9.dp).background(PetTokens.BarTrack, pillShapes)) {
        Box(Modifier.fillMaxWidth(f).height(9.dp).background(color, pillShapes))
    }
}

/** One segment per round: finished segments are full, the running one is pale with a dot. */
@Composable
fun PetSegmentBar(total: Int, completed: Int, running: Boolean, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(total.coerceAtLeast(1)) { index ->
            val current = running && index == completed
            Box(
                Modifier.weight(1f).height(9.dp).background(
                    when {
                        index < completed -> PetTokens.Blue
                        current -> PetTokens.BlueSoft
                        else -> PetTokens.BarTrack
                    },
                    pillShapes,
                ),
                contentAlignment = Alignment.Center,
            ) {
                if (current) Box(Modifier.size(5.dp).background(PetTokens.Blue, CircleShape))
            }
        }
    }
}

/** − value + control for a bounded number; the ends stop instead of wrapping. */
@Composable
fun PetStepper(value: Int, range: IntRange, step: Int, enabled: Boolean, onValue: (Int) -> Unit) {
    val shape = RoundedCornerShape(PetTokens.TileRadius)
    Row(Modifier.border(1.dp, PetTokens.Line, shape), verticalAlignment = Alignment.CenterVertically) {
        StepperKey("−", enabled && value > range.first) { onValue((value - step).coerceIn(range)) }
        Text("$value", Modifier.width(44.dp), fontSize = 15.sp, fontWeight = FontWeight.Bold,
            color = PetTokens.Ink, textAlign = TextAlign.Center)
        StepperKey("+", enabled && value < range.last) { onValue((value + step).coerceIn(range)) }
    }
}

@Composable
private fun StepperKey(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(38.dp).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 19.sp, color = if (enabled) PetTokens.Ink2 else PetTokens.Chevron)
    }
}

/**
 * The bottom-primary button. [tone] maps to the reference `.btn` variants: primary (blue),
 * ghost (white + inset border), danger (white + red inset border).
 *
 * A DISABLED button is genuinely non-interactive: [PetButtonTone.DISABLED] swallows the tap instead
 * of running [onClick]. Every other tone wires the real handler — an unhandled `onClick` would make
 * the primary controls of the whole app decorative, which screenshots cannot reveal.
 */
enum class PetButtonTone { PRIMARY, GHOST, DANGER, DISABLED }

@Composable
fun PetFootButton(
    text: String,
    tone: PetButtonTone,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(PetTokens.BtnRadius)
    val (bg, fg, border) = when (tone) {
        PetButtonTone.PRIMARY -> Triple(PetTokens.Blue, Color.White, Color.Transparent)
        PetButtonTone.GHOST -> Triple(PetTokens.Surface, PetTokens.Ink, PetTokens.Line)
        PetButtonTone.DANGER -> Triple(PetTokens.Surface, PetTokens.RedInk, PetTokens.RedBorder)
        PetButtonTone.DISABLED -> Triple(PetTokens.GraySoft, PetTokens.Ink3, Color.Transparent)
    }
    val enabled = tone != PetButtonTone.DISABLED
    Box(
        modifier = modifier
            .height(PetTokens.BtnHeight)
            .background(bg, shape)
            .then(if (border != Color.Transparent) Modifier.border(1.5.dp, border, shape) else Modifier)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = fg,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Decode an evidence PNG into a Compose [androidx.compose.ui.graphics.ImageBitmap]; null when missing/broken. */
@Composable
fun rememberEvidenceBitmap(path: String?, reqSizePx: Int): androidx.compose.ui.graphics.ImageBitmap? {
    if (path.isNullOrBlank()) return null
    return remember(path, reqSizePx) {
        runCatching {
            val opts = BitmapFactory.Options().apply { inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888 }
            val full = BitmapFactory.decodeFile(path, opts) ?: return@runCatching null
            val longest = maxOf(full.width, full.height).coerceAtLeast(1)
            val scaled = if (longest > reqSizePx) {
                val ratio = reqSizePx.toFloat() / longest
                android.graphics.Bitmap.createScaledBitmap(
                    full,
                    (full.width * ratio).toInt().coerceAtLeast(1),
                    (full.height * ratio).toInt().coerceAtLeast(1),
                    true,
                ).also { if (it !== full) full.recycle() }
            } else full
            scaled.asImageBitmap()
        }.getOrNull()
    }
}

/** A small linear gradient used behind an evidence frame with no decodable bitmap yet. */
@Composable
fun PetPlaceholderFilm(modifier: Modifier = Modifier) {
    Box(modifier.background(Brush.linearGradient(listOf(PetTokens.FilmTop, PetTokens.FilmBottom))),
        contentAlignment = Alignment.Center) {
        PetIcon(PetGlyph.Cam, 20.dp, alpha = 0.5f)
    }
}


/**
 * MKA-08「减少动效」：开启后等待指示不再旋转，用静态文字代替；关闭时是常规 spinner。
 * 全应用的等待指示都走这一个入口，开关一处生效。
 */
@Composable
fun CalmSpinner(
    context: android.content.Context,
    modifier: Modifier = Modifier,
    color: Color = PetTokens.Ink2,
) {
    if (com.ynk.virtualdisplay.ui.theme.AppearancePrefs.reduceMotion(context)) {
        Text("处理中…", fontSize = 13.sp, color = color, modifier = modifier)
    } else {
        CircularProgressIndicator(modifier = modifier, color = color)
    }
}
