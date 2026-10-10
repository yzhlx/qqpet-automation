package com.ynk.virtualdisplay.ui.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ynk.virtualdisplay.ui.theme.PetTokens

/**
 * The tile icon set. Every glyph is drawn on a 20×20 grid — exactly the viewBox of the SVGs in
 * 参考ui/redesign-v0.1/ui-v2.html — so the shapes, weights and colours match the approved mock-ups
 * while adding no image dependency. Colours are the reference literals; [tint] only overrides them
 * for the tab bar, whose icons are stroked in currentColor (blue when selected, grey otherwise).
 */
enum class PetGlyph {
    Care, Study, Coin, Friend, Shield, Display, Bell, Clock, Grip, Brush, Db, Play, Help, Bug, Info,
    Locate, Cam, Check, Warn, ArrowBack, TabHome, TabRec, TabSet,
}

private const val GRID = 20f

@Composable
fun PetIcon(
    glyph: PetGlyph,
    size: Dp = 18.dp,
    tint: Color? = null,
    alpha: Float = 1f,
) {
    Canvas(Modifier.size(size)) {
        drawGlyph(glyph, tint, alpha)
    }
}

private fun DrawScope.u(v: Float) = v * (size.minDimension / GRID)

/** Filled or stroked path helper on the 20×20 grid. */
private fun DrawScope.fill(color: Color, alpha: Float, build: Path.() -> Unit) {
    val p = Path().apply(build)
    drawPath(p, color, alpha = alpha)
}

private fun DrawScope.stroke(color: Color, alpha: Float, widthUnits: Float, build: Path.() -> Unit) {
    val p = Path().apply(build)
    drawPath(p, color, alpha = alpha, style = Stroke(width = u(widthUnits), cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun DrawScope.circle(cx: Float, cy: Float, r: Float, color: Color, alpha: Float = 1f) =
    drawCircle(color, radius = u(r), center = Offset(u(cx), u(cy)), alpha = alpha)

private fun DrawScope.roundRect(x: Float, y: Float, w: Float, h: Float, r: Float, color: Color, alpha: Float = 1f) =
    drawRoundRect(
        color,
        topLeft = Offset(u(x), u(y)),
        size = Size(u(w), u(h)),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(u(r), u(r)),
        alpha = alpha,
    )

private fun DrawScope.strokeLine(x1: Float, y1: Float, x2: Float, y2: Float, color: Color, w: Float, alpha: Float = 1f) =
    drawLine(color, Offset(u(x1), u(y1)), Offset(u(x2), u(y2)), strokeWidth = u(w), cap = StrokeCap.Round, alpha = alpha)

private fun DrawScope.drawGlyph(glyph: PetGlyph, tint: Color?, alpha: Float) {
    when (glyph) {
        PetGlyph.Care -> fill(PetTokens.Green, alpha) {
            moveTo(u(10f), u(2.6f))
            cubicTo(u(12.6f), u(5.6f), u(14.6f), u(7.7f), u(14.6f), u(10.2f))
            cubicTo(u(14.6f), u(12.8f), u(12.5f), u(14.8f), u(10f), u(14.8f))
            cubicTo(u(7.5f), u(14.8f), u(5.4f), u(12.8f), u(5.4f), u(10.2f))
            cubicTo(u(5.4f), u(7.7f), u(7.4f), u(5.6f), u(10f), u(2.6f))
            close()
        }

        PetGlyph.Study -> {
            fill(PetTokens.Blue, alpha) {
                moveTo(u(3f), u(5.4f))
                cubicTo(u(5.4f), u(4.6f), u(7.6f), u(4.6f), u(10f), u(5.4f))
                lineTo(u(10f), u(14.6f))
                cubicTo(u(7.6f), u(13.8f), u(5.4f), u(13.8f), u(3f), u(14.6f))
                close()
            }
            fill(PetTokens.Blue, alpha * 0.55f) {
                moveTo(u(10f), u(5.4f))
                cubicTo(u(12.4f), u(4.6f), u(14.6f), u(4.6f), u(17f), u(5.4f))
                lineTo(u(17f), u(14.6f))
                cubicTo(u(14.6f), u(13.8f), u(12.4f), u(13.8f), u(10f), u(14.6f))
                close()
            }
        }

        PetGlyph.Coin -> {
            drawCircle(PetTokens.AmberStrong, radius = u(7f), center = Offset(u(10f), u(10f)), style = Stroke(width = u(2f)), alpha = alpha)
            strokeLine(10f, 6.6f, 10f, 13.4f, PetTokens.AmberStrong, 1.8f, alpha)
            strokeLine(7.8f, 9f, 12.2f, 9f, PetTokens.AmberStrong, 1.8f, alpha)
        }

        PetGlyph.Friend -> {
            circle(7.6f, 7.4f, 3.1f, PetTokens.Violet, alpha)
            fill(PetTokens.Violet, alpha * 0.55f) {
                moveTo(u(2.6f), u(16.4f))
                cubicTo(u(2.6f), u(13.7f), u(4.8f), u(12f), u(7.6f), u(12f))
                cubicTo(u(10.4f), u(12f), u(12.6f), u(13.7f), u(12.6f), u(16.4f))
                close()
            }
            circle(14.2f, 8.6f, 2.3f, PetTokens.Violet, alpha * 0.7f)
        }

        PetGlyph.Shield -> fill(tint ?: PetTokens.Blue, alpha) {
            moveTo(u(10f), u(2.4f))
            lineTo(u(16f), u(4.6f))
            lineTo(u(16f), u(9.8f))
            cubicTo(u(16f), u(13.4f), u(13.5f), u(16.2f), u(10f), u(17.6f))
            cubicTo(u(6.5f), u(16.2f), u(4f), u(13.4f), u(4f), u(9.8f))
            lineTo(u(4f), u(4.6f))
            close()
        }

        PetGlyph.Display -> {
            roundRect(2.4f, 3.6f, 15.2f, 10.4f, 2f, PetTokens.Green, alpha)
            roundRect(6.6f, 15.6f, 6.8f, 1.8f, 0.9f, PetTokens.Green, alpha * 0.6f)
        }

        PetGlyph.Bell -> {
            fill(PetTokens.AmberStrong, alpha) {
                moveTo(u(10f), u(2.8f))
                cubicTo(u(7.2f), u(2.8f), u(5f), u(5f), u(5f), u(7.8f))
                lineTo(u(5f), u(11f))
                lineTo(u(3.8f), u(13f))
                lineTo(u(16.2f), u(13f))
                lineTo(u(15f), u(11f))
                lineTo(u(15f), u(7.8f))
                cubicTo(u(15f), u(5f), u(12.8f), u(2.8f), u(10f), u(2.8f))
                close()
            }
            circle(10f, 15.4f, 1.7f, PetTokens.AmberStrong, alpha * 0.6f)
        }

        PetGlyph.Clock -> {
            val c = tint ?: PetTokens.Blue
            drawCircle(c, radius = u(7f), center = Offset(u(10f), u(10f)), style = Stroke(width = u(1.9f)), alpha = alpha)
            strokeLine(10f, 6.2f, 10f, 10f, c, 1.7f, alpha)
            strokeLine(10f, 10f, 12.8f, 11.8f, c, 1.7f, alpha)
        }

        PetGlyph.Grip -> {
            val c = tint ?: PetTokens.Violet
            strokeLine(4f, 6f, 16f, 6f, c, 2f, alpha)
            strokeLine(4f, 10f, 16f, 10f, c, 2f, alpha)
            strokeLine(4f, 14f, 12f, 14f, c, 2f, alpha)
        }

        PetGlyph.Brush -> {
            val c = tint ?: PetTokens.Green
            drawCircle(c, radius = u(7f), center = Offset(u(10f), u(10f)), style = Stroke(width = u(2f)), alpha = alpha)
            fill(c, alpha) {
                moveTo(u(10f), u(6.6f))
                cubicTo(u(11.9f), u(6.6f), u(13.4f), u(8.1f), u(13.4f), u(10f))
                cubicTo(u(13.4f), u(11.9f), u(11.9f), u(13.4f), u(10f), u(13.4f))
                close()
            }
        }

        PetGlyph.Db -> {
            val c = tint ?: PetTokens.Blue
            drawOval18(10f, 5.8f, 6.2f, 2.6f, c, alpha)
            stroke(c, alpha, 1.8f) {
                moveTo(u(3.8f), u(5.8f))
                lineTo(u(3.8f), u(14.2f))
                cubicTo(u(3.8f), u(15.6f), u(6.6f), u(16.8f), u(10f), u(16.8f))
                cubicTo(u(13.4f), u(16.8f), u(16.2f), u(15.6f), u(16.2f), u(14.2f))
                lineTo(u(16.2f), u(5.8f))
            }
        }

        PetGlyph.Play -> {
            val c = tint ?: PetTokens.Green
            drawCircle(c, radius = u(7.4f), center = Offset(u(10f), u(10f)), style = Stroke(width = u(2f)), alpha = alpha)
            fill(c, alpha) {
                moveTo(u(8.4f), u(7.2f))
                lineTo(u(13f), u(10f))
                lineTo(u(8.4f), u(12.8f))
                close()
            }
        }

        PetGlyph.Help -> {
            val c = tint ?: PetTokens.Blue
            drawCircle(c, radius = u(7.4f), center = Offset(u(10f), u(10f)), alpha = alpha * 0.2f)
            stroke(c, alpha, 1.8f) {
                moveTo(u(8f), u(8.2f))
                cubicTo(u(8f), u(6.9f), u(9f), u(6.1f), u(10.1f), u(6.1f))
                cubicTo(u(11.3f), u(6.1f), u(12.2f), u(7f), u(12.2f), u(8f))
                cubicTo(u(12.2f), u(9.4f), u(10.4f), u(9.5f), u(10.1f), u(10.6f))
                lineTo(u(10.1f), u(11.3f))
            }
            circle(10f, 14.4f, 1.1f, c, alpha)
        }

        PetGlyph.Bug -> {
            val c = tint ?: PetTokens.Red
            roundRect(5.4f, 5.4f, 9.2f, 9.6f, 4f, c, alpha * 0.8f)
            strokeLine(4f, 8f, 6f, 8f, c, 1.6f, alpha)
            strokeLine(14f, 8f, 16f, 8f, c, 1.6f, alpha)
            strokeLine(4f, 12f, 6f, 12f, c, 1.6f, alpha)
            strokeLine(14f, 12f, 16f, 12f, c, 1.6f, alpha)
        }

        PetGlyph.Info -> {
            val c = tint ?: PetTokens.Ink2
            drawCircle(c, radius = u(7.4f), center = Offset(u(10f), u(10f)), alpha = alpha * 0.25f)
            strokeLine(10f, 9f, 10f, 13.4f, c, 1.9f, alpha)
            circle(10f, 6.5f, 1.1f, c, alpha)
        }

        PetGlyph.Locate -> {
            val c = tint ?: PetTokens.AmberStrong
            circle(10f, 10f, 3f, c, alpha)
            drawCircle(c, radius = u(6.4f), center = Offset(u(10f), u(10f)), style = Stroke(width = u(1.7f)), alpha = alpha * 0.55f)
            strokeLine(10f, 1.6f, 10f, 3.8f, c, 1.7f, alpha)
            strokeLine(10f, 16.2f, 10f, 18.4f, c, 1.7f, alpha)
            strokeLine(1.6f, 10f, 3.8f, 10f, c, 1.7f, alpha)
            strokeLine(16.2f, 10f, 18.4f, 10f, c, 1.7f, alpha)
        }

        PetGlyph.Cam -> {
            val c = tint ?: PetTokens.Ink3
            drawRoundRect(
                c,
                topLeft = Offset(u(2.6f), u(5.4f)),
                size = Size(u(14.8f), u(10f)),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(u(2.4f), u(2.4f)),
                style = Stroke(width = u(1.7f)),
                alpha = alpha,
            )
            drawCircle(c, radius = u(3.1f), center = Offset(u(10f), u(10.4f)), style = Stroke(width = u(1.7f)), alpha = alpha)
            circle(15.2f, 7.6f, 0.9f, c, alpha)
        }

        PetGlyph.Check -> {
            val c = tint ?: PetTokens.Green
            drawCircle(c, radius = u(6.4f), center = Offset(u(8f), u(8f)), style = Stroke(width = u(1.8f)), alpha = alpha)
            stroke(c, alpha, 1.8f) {
                moveTo(u(5.2f), u(8.2f))
                lineTo(u(7.2f), u(10.2f))
                lineTo(u(10.9f), u(6.2f))
            }
        }

        PetGlyph.Warn -> {
            val c = tint ?: PetTokens.AmberStrong
            fill(c, alpha) {
                moveTo(u(10f), u(2.6f))
                lineTo(u(18f), u(16.6f))
                lineTo(u(2f), u(16.6f))
                close()
            }
            strokeLine(10f, 7.6f, 10f, 12f, Color.White, 1.8f, alpha)
            circle(10f, 14.2f, 1f, Color.White, alpha)
        }

        // Tab bar icons are stroked in currentColor: blue when selected, grey otherwise.
        PetGlyph.ArrowBack -> {
            val c = tint ?: PetTokens.Ink
            stroke(c, alpha, 1.9f) {
                moveTo(u(12.4f), u(4.2f))
                lineTo(u(6.6f), u(10f))
                lineTo(u(12.4f), u(15.8f))
            }
        }

        PetGlyph.TabHome -> {
            val c = tint ?: PetTokens.TabInactive
            stroke(c, alpha, 1.9f) {
                moveTo(u(11f), u(2.4f))
                lineTo(u(19f), u(9f))
                lineTo(u(19f), u(18.2f))
                cubicTo(u(19f), u(19.1f), u(18.3f), u(19.8f), u(17.4f), u(19.8f))
                lineTo(u(4.6f), u(19.8f))
                cubicTo(u(3.7f), u(19.8f), u(3f), u(19.1f), u(3f), u(18.2f))
                lineTo(u(3f), u(9f))
                close()
            }
        }

        PetGlyph.TabRec -> {
            val c = tint ?: PetTokens.TabInactive
            drawRoundRect(
                c,
                topLeft = Offset(u(3.4f), u(3.4f)),
                size = Size(u(15.2f), u(15.2f)),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(u(3f), u(3f)),
                style = Stroke(width = u(1.9f)),
                alpha = alpha,
            )
            strokeLine(7f, 8.4f, 15f, 8.4f, c, 1.9f, alpha)
            strokeLine(7f, 11.6f, 15f, 11.6f, c, 1.9f, alpha)
            strokeLine(7f, 14.8f, 11.6f, 14.8f, c, 1.9f, alpha)
        }

        PetGlyph.TabSet -> {
            val c = tint ?: PetTokens.TabInactive
            drawCircle(c, radius = u(3.2f), center = Offset(u(11f), u(11f)), style = Stroke(width = u(1.9f)), alpha = alpha)
            strokeLine(11f, 2.6f, 11f, 5f, c, 1.9f, alpha)
            strokeLine(11f, 17f, 11f, 19.4f, c, 1.9f, alpha)
            strokeLine(2.6f, 11f, 5f, 11f, c, 1.9f, alpha)
            strokeLine(17f, 11f, 19.4f, 11f, c, 1.9f, alpha)
            strokeLine(5.1f, 5.1f, 6.8f, 6.8f, c, 1.9f, alpha)
            strokeLine(15.2f, 15.2f, 16.9f, 16.9f, c, 1.9f, alpha)
            strokeLine(16.9f, 5.1f, 15.2f, 6.8f, c, 1.9f, alpha)
            strokeLine(6.8f, 15.2f, 5.1f, 16.9f, c, 1.9f, alpha)
        }
    }
}

private fun DrawScope.drawOval18(cx: Float, cy: Float, rx: Float, ry: Float, color: Color, alpha: Float) =
    drawOval(
        color,
        topLeft = Offset(u(cx - rx), u(cy - ry)),
        size = Size(u(rx * 2f), u(ry * 2f)),
        alpha = alpha,
    )
