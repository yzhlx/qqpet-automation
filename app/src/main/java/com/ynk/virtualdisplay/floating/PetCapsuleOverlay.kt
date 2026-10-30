package com.ynk.virtualdisplay.floating

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.ynk.virtualdisplay.automation.PetAutomationService
import com.ynk.virtualdisplay.automation.PetManagedRuntime
import com.ynk.virtualdisplay.automation.PetRuntime
import com.ynk.virtualdisplay.ui.main.MainActivity
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Cross-app floating capsule built on TYPE_APPLICATION_OVERLAY.
 *
 * The UX follows Mika's capsule (a small draggable bubble that opens a task card), but the
 * implementation deliberately stays inside this app's own facts: the state comes from
 * [PetManagedRuntime] / [PetRuntime], the preview comes from [DisplayPreviewHub], and the stop
 * button calls the real [PetAutomationService] stop intent. No Accessibility overlay host and no
 * second automation state machine are introduced.
 *
 * Overlay permission is optional: when it is missing the capsule simply never appears and the
 * managed session keeps running unchanged.
 */
object PetCapsuleOverlay {
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var attached = false
    private var appContext: Context? = null
    private var windowManager: WindowManager? = null
    private var bubbleView: ComposeView? = null
    private var cardView: ComposeView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var cardParams: WindowManager.LayoutParams? = null
    private var owner: OverlayLifecycleOwner? = null
    private var scope: CoroutineScope? = null
    private var ticker: Job? = null
    private var retainToken: AutoCloseable? = null
    private var terminalSinceElapsedMs = 0L

    private var model by mutableStateOf(
        CapsuleUiModel(
            visible = false,
            visual = CapsuleVisualState.STOPPED,
            title = "QQ宠物托管",
            statusText = "未开始",
            durationText = "-",
            flowText = "-",
            stepText = "-",
            roundText = "-",
            nextStepText = "-",
            canStop = false,
        ),
    )
    private var expanded by mutableStateOf(false)
    private var dockLeft by mutableStateOf(false)

    /** Safe to call from any thread: every window operation is hopped onto the main thread. */
    fun show(context: Context) {
        val app = context.applicationContext
        if (!OverlayPermission.canDrawOverlays(app)) return
        mainHandler.post { showOnMain(app) }
    }

    fun hide() {
        mainHandler.post { hideOnMain() }
    }

    private fun showOnMain(app: Context) {
        if (attached) {
            refreshModel()
            return
        }
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val lifecycleOwner = OverlayLifecycleOwner().also { it.onCreate() }
        val bubble = ComposeView(app)
        val card = ComposeView(app)
        installOwners(bubble, lifecycleOwner)
        installOwners(card, lifecycleOwner)

        val density = app.resources.displayMetrics
        fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, density).toInt()

        val bParams = baseParams().apply {
            width = dp(40f)
            height = dp(40f)
            gravity = Gravity.TOP or Gravity.START
            x = density.widthPixels - dp(52f)
            y = density.heightPixels / 3
        }
        val cParams = baseParams().apply {
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.TOP or Gravity.START
            x = dp(16f)
            y = density.heightPixels / 5
            flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        }

        bubble.setContent {
            if (model.visible) {
                CapsuleBubble(model = model, onClick = ::openCard)
            }
        }
        card.setContent {
            if (model.visible && expanded) {
                CapsuleExpandedCard(
                    model = model,
                    onCollapse = ::closeCard,
                    onOpenApp = {
                        closeCard()
                        app.startActivity(
                            Intent(app, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                        )
                    },
                    onStop = {
                        app.startService(PetAutomationService.stopIntent(app))
                        closeCard()
                    },
                )
            }
        }

        enableDrag(bubble, bParams, wm, onTap = ::openCard) {
            dockLeft = bParams.x + bParams.width / 2 < density.widthPixels / 2
            bParams.x = if (dockLeft) dp(8f) else density.widthPixels - bParams.width - dp(8f)
            runCatching { wm.updateViewLayout(bubble, bParams) }
            if (expanded) layoutCard()
        }
        card.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                closeCard()
                true
            } else {
                false
            }
        }

        // The card stays attached for the whole session; the bubble is attached only while the
        // user is in another app, because the in-app PiP already covers the QQPet foreground.
        wm.addView(card, cParams)
        lifecycleOwner.onResume()

        appContext = app
        windowManager = wm
        bubbleView = bubble
        cardView = card
        bubbleParams = bParams
        cardParams = cParams
        owner = lifecycleOwner
        retainToken = DisplayPreviewHub.retain()
        attached = true

        val jobScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = jobScope
        ticker = jobScope.launch {
            var startedAt = SystemClock.elapsedRealtime()
            var sawSession = false
            while (isActive) {
                refreshModel()
                if (model.visible) syncBubbleVisibility()
                if (model.visible) {
                    sawSession = true
                    if (model.visual in TERMINAL_STATES) {
                        if (terminalSinceElapsedMs == 0L) terminalSinceElapsedMs = SystemClock.elapsedRealtime()
                        if (SystemClock.elapsedRealtime() - terminalSinceElapsedMs >= TERMINAL_VISIBLE_MS) {
                            hideOnMain()
                            break
                        }
                    } else {
                        terminalSinceElapsedMs = 0L
                    }
                } else if (sawSession || SystemClock.elapsedRealtime() - startedAt >= STARTUP_GRACE_MS) {
                    // The session is over (or never started): close the capsule.
                    hideOnMain()
                    break
                }
                delay(500L)
            }
        }
        refreshModel()
    }

    private fun hideOnMain() {
        if (!attached) return
        attached = false
        ticker?.cancel(); ticker = null
        scope?.cancel(); scope = null
        retainToken?.close(); retainToken = null
        val wm = windowManager
        runCatching { bubbleView?.let { wm?.removeViewImmediate(it) } }
        runCatching { cardView?.let { wm?.removeViewImmediate(it) } }
        runCatching { wm?.updateViewLayout(cardView, cardParams) }
        owner?.onDestroy()
        bubbleView = null
        cardView = null
        bubbleParams = null
        cardParams = null
        windowManager = null
        owner = null
        appContext = null
        expanded = false
        terminalSinceElapsedMs = 0L
    }

    /**
     * Opening the card removes the bubble window instead of hiding it: a transparent 40dp window
     * would keep swallowing touches that belong to the app underneath.
     */
    private fun openCard() {
        if (expanded) return
        expanded = true
        detachBubble()
        layoutCard()
    }

    private fun closeCard() {
        if (!expanded) return
        expanded = false
        syncBubbleVisibility()
    }

    /**
     * The bubble floats above other apps only: it is attached while another app is in front and
     * taken off screen again the moment QQPet returns, because the in-app PiP covers that case.
     * One place owns both directions, so an already-attached window cannot survive the switch.
     */
    private fun syncBubbleVisibility() {
        if (CapsuleBubblePolicy.shouldAttach(AppForegroundState.isForeground, expanded)) {
            attachBubbleIfNeeded()
        } else {
            detachBubble()
        }
    }

    private fun attachBubbleIfNeeded() {
        val wm = windowManager ?: return
        val bubble = bubbleView ?: return
        val params = bubbleParams ?: return
        if (bubble.parent == null) {
            runCatching { wm.addView(bubble, params) }
        }
    }

    private fun detachBubble() {
        val wm = windowManager ?: return
        val bubble = bubbleView ?: return
        if (bubble.parent != null) {
            runCatching { wm.removeViewImmediate(bubble) }
        }
    }

    private fun refreshModel() {
        model = CapsuleUiMapper.from(
            PetManagedRuntime.state.value,
            PetRuntime.state.value,
            System.currentTimeMillis(),
            SystemClock.elapsedRealtime(),
        )
        if (expanded) layoutCard()
    }

    private fun layoutCard() {
        val app = appContext ?: return
        val wm = windowManager ?: return
        val card = cardView ?: return
        val cParams = cardParams ?: return
        val bParams = bubbleParams ?: return
        val density = app.resources.displayMetrics
        fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, density).toInt()
        val cardWidth = dp(300f)
        cParams.x = if (dockLeft) {
            (bParams.x + bParams.width + dp(8f)).coerceAtMost(density.widthPixels - cardWidth - dp(8f))
        } else {
            (bParams.x - cardWidth - dp(8f)).coerceAtLeast(dp(8f))
        }
        cParams.y = bParams.y.coerceIn(dp(24f), (density.heightPixels - dp(360f)).coerceAtLeast(dp(24f)))
        runCatching { wm.updateViewLayout(card, cParams) }
    }

    private fun baseParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        )
    }

    /**
     * A ComposeView needs a lifecycle owner, a ViewModelStore owner AND a SavedStateRegistry owner
     * on its view tree; AndroidComposeView refuses to attach without the last one.
     */
    private fun installOwners(view: ComposeView, owner: OverlayLifecycleOwner) {
        view.setViewTreeLifecycleOwner(owner)
        view.setViewTreeViewModelStoreOwner(owner)
        view.setViewTreeSavedStateRegistryOwner(owner)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun enableDrag(
        view: ComposeView,
        params: WindowManager.LayoutParams,
        wm: WindowManager,
        onTap: () -> Unit,
        onSettle: () -> Unit,
    ) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (abs(dx) > 6 || abs(dy) > 6) moved = true
                    if (moved) {
                        params.x = startX + dx
                        params.y = startY + dy
                        runCatching { wm.updateViewLayout(view, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (moved) onSettle() else onTap()
                    true
                }
                else -> false
            }
        }
    }

    private val TERMINAL_STATES = setOf(
        CapsuleVisualState.COMPLETED, CapsuleVisualState.FAILED, CapsuleVisualState.STOPPED,
    )

    /** How long a finished / failed / stopped result stays on screen before the capsule closes. */
    private const val TERMINAL_VISIBLE_MS = 12_000L

    /**
     * Grace period before the capsule gives up waiting for the first runtime snapshot: the service
     * publishes it a moment after the session starts.
     */
    private const val STARTUP_GRACE_MS = 20_000L
}

private class OverlayLifecycleOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    fun onCreate() {
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
    }

    fun onResume() {
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
    }
}
