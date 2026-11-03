package com.ynk.virtualdisplay.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import com.ynk.virtualdisplay.R
import com.ynk.virtualdisplay.data.model.ShizukuState
import com.ynk.virtualdisplay.floating.OverlayPermission
import com.ynk.virtualdisplay.ui.theme.PetTokens
import kotlinx.coroutines.delay

private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
private const val SHIZUKU_RELEASES = "https://github.com/RikkaApps/Shizuku/releases/latest"

private fun readFacts(context: Context, state: ShizukuState): OnboardingFacts {
    val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val resolver = context.contentResolver
    return OnboardingFacts(
        shizukuInstalled = runCatching { context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0) }.isSuccess,
        shizukuRunning = state is ShizukuState.Ready || state is ShizukuState.PermissionDenied,
        shizukuGranted = state is ShizukuState.Ready,
        overlayGranted = OverlayPermission.canDrawOverlays(context),
        notificationGranted = NotificationManagerCompat.from(context).areNotificationsEnabled(),
        batteryExempt = power.isIgnoringBatteryOptimizations(context.packageName),
        developerEnabled = runCatching {
            Settings.Global.getInt(resolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
        }.getOrDefault(false),
        wirelessDebugEnabled = runCatching {
            Settings.Global.getInt(resolver, "adb_wifi_enabled", 0) == 1
        }.getOrDefault(false),
    )
}

/**
 * Where the developer options live differs by brand. Shown for this phone's brand first, with the
 * other common brands listed underneath, so the user is never guessing from a generic path.
 */
private data class DevPath(val brand: String, val about: String, val dev: String)

private val DEV_PATHS = listOf(
    DevPath("华为 / 荣耀", "设置 → 关于手机，连续点「版本号」7 次", "设置 → 系统和更新 → 开发人员选项（旧版在「设置 → 更多设置」）"),
    DevPath("小米 / Redmi", "设置 → 我的设备 → 全部参数，连续点「MIUI / OS 版本」7 次", "设置 → 更多设置 → 开发者选项"),
    DevPath("OPPO / realme / 一加", "设置 → 关于本机 → 版本信息，连续点「版本号」7 次", "设置 → 其他设置 → 开发者选项"),
    DevPath("vivo / iQOO", "设置 → 关于手机，连续点「软件版本号」7 次", "设置 → 更多设置 → 开发人员选项"),
    DevPath("三星", "设置 → 关于手机 → 软件信息，连续点「版本号」7 次", "设置 → 开发人员选项"),
    DevPath("原生安卓 / 其他", "设置 → 关于手机，连续点「版本号」7 次", "设置 → 系统 → 开发者选项"),
)

private fun devPathFor(manufacturer: String): DevPath {
    val m = manufacturer.lowercase()
    return when {
        m.contains("huawei") || m.contains("honor") -> DEV_PATHS[0]
        m.contains("xiaomi") || m.contains("redmi") -> DEV_PATHS[1]
        m.contains("oppo") || m.contains("realme") || m.contains("oneplus") -> DEV_PATHS[2]
        m.contains("vivo") || m.contains("iqoo") -> DEV_PATHS[3]
        m.contains("samsung") -> DEV_PATHS[4]
        else -> DEV_PATHS[5]
    }
}

/** System screens differ by brand, so a missing one falls back to the Settings home. */
private fun Context.open(intent: Intent) {
    try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: SecurityException) {
        startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private data class GuidePage(
    val title: String,
    val intro: String,
    val steps: List<String> = emptyList(),
    val note: String? = null,
    val action: String? = null,
    val secondary: String? = null,
    val waiting: String? = null,
    val crab: Int? = null,
)

/** Start-Shizuku guide: the phone's current state decides which sub-step is shown, never a counter. */
private fun startShizukuPage(facts: OnboardingFacts, completedBefore: Boolean): GuidePage {
    if (completedBefore) return GuidePage(
        title = "Shizuku 需要重新启动",
        intro = "手机重启后 Shizuku 会自动停止，这是系统的限制。打开 Shizuku 再点一次「启动」就好，不用重新配对。",
        steps = listOf("打开手机的「无线调试」", "打开 Shizuku，点「启动」", "回到这里"),
        note = "不同手机的菜单名字可能略有差别，意思一样即可。",
        action = "打开 Shizuku",
        secondary = "打开开发者选项",
        waiting = "正在等待 Shizuku 启动…",
    )
    val path = devPathFor(Build.MANUFACTURER)
    if (!facts.developerEnabled) return GuidePage(
        title = "先开启开发者选项",
        intro = "手机默认隐藏了「开发者选项」。打开它不会改变你平时的使用方式，只是多出一个菜单。",
        steps = listOf(
            "点下面的「打开关于手机」",
            "${path.about}，直到提示「已进入开发者模式」",
            "回到这里，页面会自动继续",
        ),
        note = "${path.brand} 手机通常是这样走。找不到时，在设置顶部搜索「版本号」。下面列出了其他品牌的路径。",
        action = "打开关于手机",
        secondary = "直接打开开发者选项",
        waiting = "正在等待开发者选项开启…",
    )
    if (!facts.wirelessDebugEnabled) return GuidePage(
        title = "打开无线调试",
        intro = "开发者选项已经开启。接下来打开「无线调试」，Shizuku 才能完成配对。请先连上 Wi-Fi。",
        steps = listOf(
            "点下面的「打开开发者选项」",
            "找到「无线调试」，打开开关（部分手机要先点进去再打开）",
            "回到这里，页面会自动继续",
        ),
        note = "${path.brand} 的入口：${path.dev}。找不到时，在开发者选项顶部搜索「无线调试」。",
        action = "打开开发者选项",
        waiting = "正在等待无线调试开启…",
    )
    return GuidePage(
        title = "启动 Shizuku",
        intro = "第一次需要配对。无线调试已经打开，请保持 Wi-Fi 连接。",
        steps = listOf(
            "点下面的「打开 Shizuku」",
            "在「通过无线调试启动」里点「配对」，按提示在通知栏输入配对码",
            "配对成功后，回到 Shizuku 点「启动」",
            "回到这里",
        ),
        note = "配对码只显示一次。输错或超时，就重新点「配对」。",
        action = "打开 Shizuku",
        waiting = "正在等待 Shizuku 启动…",
    )
}

private fun pageFor(step: OnboardingStep, state: ShizukuState, completedBefore: Boolean, facts: OnboardingFacts) = when (step) {
    OnboardingStep.WELCOME -> GuidePage(
        title = "你好，我是蟹啦",
        intro = "我会替你照看 QQ 宠物：打工、上课、喂食。开始前需要在手机上打开几个开关，全程约 5 分钟，不用连电脑，也不需要 root。",
        steps = listOf("装一个叫 Shizuku 的小工具", "启动它，并允许本应用使用", "打开悬浮窗、通知和后台运行"),
        note = "每一步都会告诉你点哪里，做完这里会自动进入下一步。",
        action = "开始",
        crab = R.drawable.crab_wave,
    )
    OnboardingStep.INSTALL_SHIZUKU -> GuidePage(
        title = "安装 Shizuku",
        intro = "宠物托管要在后台的一块“虚拟屏幕”上操作 QQ，普通应用没有这个权限，Shizuku 就是用来开这个权限的免费工具。",
        steps = listOf("点下面的按钮，打开 Shizuku 的下载页", "下载列表里以 .apk 结尾的文件", "打开下载好的文件并安装；手机提示风险时，选择继续安装", "装好后回到这里"),
        note = "下载页是 GitHub，打不开时换个网络再试。",
        action = "打开下载页",
        waiting = "正在等待 Shizuku 安装完成…",
    )
    OnboardingStep.START_SHIZUKU -> startShizukuPage(facts, completedBefore)
    OnboardingStep.GRANT_SHIZUKU -> GuidePage(
        title = "允许本应用使用 Shizuku",
        intro = "Shizuku 已经在运行了。点下面的按钮，在弹出的窗口里选择「始终允许」。",
        note = if (state is ShizukuState.PermissionDenied) "如果之前点了拒绝，也可以打开 Shizuku，在“已授权的应用”里把本应用打开。" else null,
        action = "请求授权",
        secondary = "打开 Shizuku",
        waiting = "授权后会自动继续…",
    )
    OnboardingStep.OVERLAY -> GuidePage(
        title = "打开悬浮窗",
        intro = "托管时屏幕边上会有一个小胶囊，让你离开本应用也能看到进度、随时停止。",
        steps = listOf("点下面的按钮", "在列表里找到本应用，打开开关", "返回这里"),
        action = "去打开",
        waiting = "打开后会自动继续…",
    )
    OnboardingStep.NOTIFICATION -> GuidePage(
        title = "打开通知",
        intro = "任务完成或中途停下时，通知会告诉你结果。",
        steps = listOf("点下面的按钮", "打开「允许通知」", "返回这里"),
        action = "去打开",
        waiting = "打开后会自动继续…",
    )
    OnboardingStep.BACKGROUND -> GuidePage(
        title = "允许后台运行",
        intro = "手机为了省电，可能会中途关掉托管。允许后，任务能完整跑完。",
        steps = listOf("点下面的按钮", "在弹窗里选择「允许」"),
        note = "华为手机还可以到「设置 → 应用启动管理」，把本应用改成手动管理并全部打开。",
        action = "去允许",
        waiting = "允许后会自动继续…",
    )
    OnboardingStep.FINISH -> GuidePage(
        title = "准备好了",
        intro = "开始托管前，再确认这三件事：",
        steps = listOf("手机上已安装 QQ，并登录了要托管的账号", "托管时不要锁屏，否则任务会安全停止", "时间较长的任务，建议插上充电器并把自动息屏调长"),
        action = "开始使用",
        crab = R.drawable.crab_hug,
    )
}

/** Full-page guide; the phone's real state moves it forward, so nobody has to report a step as done. */
@Composable
fun OnboardingScreen(
    state: ShizukuState,
    completedBefore: Boolean,
    onCheck: () -> Unit,
    onRequestPermission: () -> Unit,
    onFinish: () -> Unit,
) {
    val context = LocalContext.current
    var facts by remember { mutableStateOf(readFacts(context, state)) }
    var welcomed by remember { mutableStateOf(completedBefore) }
    var setAside by remember { mutableStateOf(OnboardingFlow.setAsideFor(completedBefore)) }
    val latestState by rememberUpdatedState(state)
    val latestCheck by rememberUpdatedState(onCheck)

    LaunchedEffect(Unit) {
        while (true) {
            if (latestState !is ShizukuState.Ready) latestCheck()
            facts = readFacts(context, latestState)
            delay(1500)
        }
    }
    LaunchedEffect(state) { facts = readFacts(context, state) }

    // 「上一步」只改看的页面；进度仍由手机状态决定，点「回到当前进度」即回到原位。
    var backTo by remember { mutableStateOf<OnboardingStep?>(null) }
    val current = OnboardingFlow.current(facts, welcomed, setAside)
    val step = backTo ?: current
    LaunchedEffect(current) { if (current == OnboardingStep.FINISH && completedBefore) onFinish() }
    val page = pageFor(step, state, completedBefore, facts)

    val primary: () -> Unit = when (step) {
        OnboardingStep.WELCOME -> ({ welcomed = true; backTo = null })
        OnboardingStep.INSTALL_SHIZUKU -> ({ context.open(Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_RELEASES))) })
        OnboardingStep.START_SHIZUKU -> when {
            completedBefore -> ({ context.openShizuku() })
            !facts.developerEnabled -> ({ context.open(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)) })
            !facts.wirelessDebugEnabled -> ({ context.open(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) })
            else -> ({ context.openShizuku() })
        }
        OnboardingStep.GRANT_SHIZUKU -> onRequestPermission
        OnboardingStep.OVERLAY -> ({ context.open(OverlayPermission.settingsIntent(context)) })
        OnboardingStep.NOTIFICATION -> ({
            context.open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        })
        OnboardingStep.BACKGROUND -> ({
            @Suppress("BatteryLife")
            context.open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + context.packageName)))
        })
        OnboardingStep.FINISH -> onFinish
    }
    val secondary: () -> Unit = when (step) {
        OnboardingStep.START_SHIZUKU -> ({ context.open(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) })
        else -> ({ context.openShizuku() })
    }

    Column(Modifier.fillMaxSize().background(PetTokens.Bg).padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(20.dp))
        val index = OnboardingFlow.COUNTED.indexOf(step)
        if (index >= 0 && !completedBefore) StepProgress(index, OnboardingFlow.COUNTED.size)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(24.dp))
            page.crab?.let {
                Image(painterResource(it), contentDescription = null, modifier = Modifier.size(96.dp), contentScale = ContentScale.Fit)
                Spacer(Modifier.height(8.dp))
            }
            Text(page.title, fontSize = 27.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, color = PetTokens.Ink)
            Spacer(Modifier.height(10.dp))
            Text(page.intro, fontSize = 16.sp, lineHeight = 24.sp, color = PetTokens.Ink2)
            if (page.steps.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(PetTokens.CardRadius)).background(PetTokens.Surface).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) { page.steps.forEachIndexed { i, text -> NumberedLine(i + 1, text) } }
            }
            page.note?.let {
                Spacer(Modifier.height(14.dp))
                Text(it, fontSize = 13.sp, lineHeight = 19.sp, color = PetTokens.Ink2)
            }
            if (step == OnboardingStep.START_SHIZUKU && !completedBefore && !facts.developerEnabled) OtherBrands()
            Spacer(Modifier.height(16.dp))
        }
        page.waiting?.let {
            Text(it, Modifier.fillMaxWidth().padding(bottom = 10.dp), fontSize = 13.sp,
                color = PetTokens.Ink3, textAlign = TextAlign.Center)
        }
        page.action?.let { GuideButton(it, primary) }
        page.secondary?.let { TextAction(it, secondary) }
        OnboardingFlow.previous(step)?.let { previous -> TextAction("上一步") { backTo = previous } }
        if (backTo != null) TextAction("回到当前进度") { backTo = null }
        if (step in OnboardingFlow.OPTIONAL) TextAction("暂时跳过") { setAside = setAside + step }
        Spacer(Modifier.height(16.dp))
    }
}

private fun Context.openShizuku() {
    val launch = packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
    open(launch ?: Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_RELEASES)))
}

@Composable
private fun StepProgress(index: Int, total: Int) {
    Text("第 ${index + 1} 步，共 $total 步", fontSize = 13.sp, color = PetTokens.Ink2)
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(total) { i ->
            Box(Modifier.weight(1f).height(3.dp).clip(CircleShape)
                .background(if (i <= index) PetTokens.Blue else PetTokens.Line))
        }
    }
}

@Composable
private fun NumberedLine(number: Int, text: String) {
    Row {
        Box(Modifier.size(22.dp).clip(CircleShape).background(PetTokens.BlueSoft), contentAlignment = Alignment.Center) {
            Text("$number", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = PetTokens.BlueInk)
        }
        Spacer(Modifier.width(10.dp))
        Text(text, Modifier.weight(1f), fontSize = 15.sp, lineHeight = 22.sp, color = PetTokens.Ink)
    }
}

@Composable
private fun GuideButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(PetTokens.BtnHeight).clip(RoundedCornerShape(PetTokens.BtnRadius))
            .background(PetTokens.Blue).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = PetTokens.Surface) }
}

@Composable
private fun OtherBrands() {
    Spacer(Modifier.height(18.dp))
    Text("各品牌的开发者选项位置", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = PetTokens.Ink)
    Spacer(Modifier.height(8.dp))
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(PetTokens.CardRadius)).background(PetTokens.Surface).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        DEV_PATHS.forEach { p ->
            Text("${p.brand}：${p.about}。开发者选项：${p.dev}", fontSize = 13.sp, lineHeight = 19.sp, color = PetTokens.Ink2)
        }
    }
}

@Composable
private fun TextAction(label: String, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(44.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, fontSize = 15.sp, color = PetTokens.Blue)
    }
}
