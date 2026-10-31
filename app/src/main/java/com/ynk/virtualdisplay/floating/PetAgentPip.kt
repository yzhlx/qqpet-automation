package com.ynk.virtualdisplay.floating

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ynk.virtualdisplay.automation.PetManagedRuntime
import com.ynk.virtualdisplay.automation.PetRuntime
import com.ynk.virtualdisplay.automation.SessionRunState
import com.ynk.virtualdisplay.ui.screens.ManagedRuntimePresentation
import com.ynk.virtualdisplay.ui.theme.PetTokens
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private val CollapsedWidth = 96.dp
private val ExpandedWidth = 220.dp

/** The calibrated automation display is 720x1600, so this window keeps the real game ratio. */
private val PipAspect = 1600f / 720f

/**
 * In-app floating preview of the automation VirtualDisplay.
 *
 * Interaction pattern adapted from Mika ui/AgentPip.kt + ui/PipPhysics.kt (Apache-2.0): tap to
 * expand / collapse, drag with the release fling projected into the nearest corner, then a
 * critically damped spring. View-only by design: touches here never reach the VirtualDisplay and
 * this window never binds a Surface to it, so it cannot starve the automation capture.
 */
@Composable
fun PetAgentPipHost(modifier: Modifier = Modifier) {
    val runtime by PetRuntime.state.collectAsState()
    val managed by PetManagedRuntime.state.collectAsState()
    val active = runtime.running || ManagedRuntimePresentation.isActive(managed.runState)
    if (!active) return

    // Releasing the token stops preview scaling work entirely: no viewer, no processing.
    DisposableEffect(Unit) {
        val token = DisplayPreviewHub.retain()
        onDispose { token.close() }
    }

    val frame by DisplayPreviewHub.frame.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    val accent = when (managed.runState) {
        SessionRunState.RUNNING, SessionRunState.RESUMING -> PetTokens.Green
        SessionRunState.PAUSED -> PetTokens.AmberStrong
        SessionRunState.FAILED -> PetTokens.Red
        SessionRunState.COMPLETED -> PetTokens.BlueInk
        else -> PetTokens.Ink3
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val scope = rememberCoroutineScope()
        val widthDp = if (expanded) ExpandedWidth else CollapsedWidth
        val heightDp = widthDp * PipAspect
        val pipW = with(density) { widthDp.toPx() }
        val pipH = with(density) { heightDp.toPx() }
        val bounds = PipPhysics.Bounds(
            width = constraints.maxWidth.toFloat(),
            height = constraints.maxHeight.toFloat(),
            pipWidth = pipW,
            pipHeight = pipH,
            margin = with(density) { 12.dp.toPx() },
            top = with(density) { 12.dp.toPx() },
            // Clear of the 托管 footer buttons so the preview never hides a real control.
            bottom = with(density) { 96.dp.toPx() },
        )
        var position by remember { mutableStateOf(Offset.Zero) }
        var placed by remember { mutableStateOf(false) }
        var settleJob by remember { mutableStateOf<Job?>(null) }
        val expandedState = rememberUpdatedState(expanded)

        if (!placed) {
            val (x, y) = bounds.position(PipPhysics.Corner.BottomEnd)
            position = Offset(x, y)
            placed = true
        }

        fun settleTo(corner: PipPhysics.Corner, vx: Float, vy: Float) {
            settleJob?.cancel()
            val (tx, ty) = bounds.position(corner)
            settleJob = scope.launch {
                val spring = PipPhysics.Spring()
                var mx = PipPhysics.Motion(position.x, vx)
                var my = PipPhysics.Motion(position.y, vy)
                var previousFrame = 0L
                while (true) {
                    withFrameNanos { frameTimeNanos ->
                        val dt = if (previousFrame == 0L) 1f / 60f
                        else ((frameTimeNanos - previousFrame) / 1_000_000_000.0).toFloat()
                        previousFrame = frameTimeNanos
                        mx = spring.step(mx, tx, dt)
                        my = spring.step(my, ty, dt)
                    }
                    position = Offset(mx.position, my.position)
                    if (spring.settled(mx, tx) && spring.settled(my, ty)) break
                }
                position = Offset(tx, ty)
            }
        }

        // Re-anchor after expand / collapse / resize, clamped back into the drawable area.
        LaunchedEffect(pipW, pipH, bounds.width, bounds.height) {
            if (placed && settleJob?.isActive != true) {
                val maxX = max(bounds.margin, bounds.width - pipW - bounds.margin)
                val maxY = max(
                    bounds.top + bounds.margin,
                    bounds.height - pipH - bounds.bottom - bounds.margin,
                )
                position = Offset(
                    position.x.coerceIn(bounds.margin, maxX),
                    position.y.coerceIn(bounds.top + bounds.margin, maxY),
                )
            }
        }

        Box(
            Modifier
                .offset { IntOffset(position.x.roundToInt(), position.y.roundToInt()) }
                .size(widthDp, heightDp),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .shadow(10.dp, RoundedCornerShape(16.dp))
                    .clip(RoundedCornerShape(16.dp))
                    .background(PetTokens.Surface)
                    .border(2.dp, accent, RoundedCornerShape(16.dp))
                    .pointerInput(pipW, pipH, bounds.width, bounds.height) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val origin = position
                            val tracker = VelocityTracker()
                            tracker.addPosition(down.uptimeMillis, down.position)
                            var total = Offset.Zero
                            var dragging = false
                            drag(down.id) { change ->
                                total += change.positionChange()
                                tracker.addPosition(change.uptimeMillis, origin + total)
                                if (!dragging && total.getDistance() > viewConfiguration.touchSlop) {
                                    dragging = true
                                    settleJob?.cancel()
                                }
                                if (dragging) {
                                    change.consume()
                                    position = origin + total
                                }
                            }
                            if (dragging) {
                                val velocity = tracker.calculateVelocity()
                                val corner = PipPhysics.snapCorner(
                                    position.x, position.y, velocity.x, velocity.y, bounds,
                                )
                                settleTo(corner, velocity.x, velocity.y)
                            } else {
                                expanded = !expandedState.value
                            }
                        }
                    },
            ) {
                val image = remember(frame?.generation) {
                    frame?.bitmap?.takeIf { !it.isRecycled }?.asImageBitmap()
                }
                if (image != null) {
                    Image(
                        bitmap = image,
                        contentDescription = "虚拟屏实时画面",
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(3.dp)
                            .clip(RoundedCornerShape(13.dp)),
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.TopCenter,
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("等待画面", color = PetTokens.Ink3, fontSize = 11.sp)
                    }
                }
            }

            // A sibling of the gesture surface: the collapse button must not fight the drag.
            if (expanded) {
                Text(
                    text = "收起",
                    color = PetTokens.Ink,
                    fontSize = 10.sp,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(PetTokens.Surface.copy(alpha = 0.92f), RoundedCornerShape(10.dp))
                        .clickable { expanded = false }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}
