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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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

private fun readFacts(
    context: Context,
    state: ShizukuState,
    confirmedKeys: Set<String> = emptySet(),
): OnboardingFacts {
    val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val resolver = context.contentResolver
    val pm = context.packageManager

    val devState = runCatching {
        if (Settings.Global.getInt(resolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1) {
            CheckState.SATISFIED
        } else {
            CheckState.UNSATISFIED
        }
    }.getOrElse { CheckState.UNKNOWN }

    val wifiState = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        runCatching {
            if (Settings.Global.getInt(resolver, "adb_wifi_enabled", 0) == 1) {
                CheckState.SATISFIED
            } else {
                CheckState.UNSATISFIED
            }
        }.getOrElse { CheckState.UNKNOWN }
    } else {
        CheckState.UNSATISFIED
    }

    val qqInstalled = runCatching { pm.getPackageInfo("com.tencent.mobileqq", 0) }.isSuccess

    return OnboardingFacts(
        shizukuInstalled = runCatching { pm.getPackageInfo(SHIZUKU_PACKAGE, 0) }.isSuccess,
        shizukuRunning = state is ShizukuState.Ready || state is ShizukuState.PermissionDenied,
        shizukuGranted = state is ShizukuState.Ready,
        overlayGranted = OverlayPermission.canDrawOverlays(context),
        notificationGranted = NotificationManagerCompat.from(context).areNotificationsEnabled(),
        batteryExempt = power.isIgnoringBatteryOptimizations(context.packageName),
        developerState = devState,
        wirelessDebugState = wifiState,
        androidSdkInt = Build.VERSION.SDK_INT,
        qqInstalled = qqInstalled,
        userConfirmedKeys = confirmedKeys,
    )
}

/**
 * 各手机品牌开发者选项的常规进入路径。
 */
private data class DevPath(val brand: String, val about: String, val dev: String)

private val DEV_PATHS = listOf(
    DevPath("华为 / 荣耀", "设置 → 关于手机，连续点「版本号」7 次", "设置 → 系统和更新 → 开发人员选项（旧版在「设置 → 更多设置」）"),
    DevPath("小米 / Redmi", "设置 → 我的设备 → 全部参数，连续点「MIUI / OS 版本」7 次", "设置 → 更多设置 → 开发者选项"),
    DevPath("OPPO / realme / 一加", "设置 → 关于本机 → 版本信息，连续点「版本号」7 次", "设置 → 其他设置 → 开发者选项"),
    DevPath("vivo / iQOO", "设置 → 关于手机，连续点「软件版本号」7 次", "设置 → 更多设置 → 开发人员选项"),
    DevPath("三星", "设置 → 关于手机 → 软件信息，连续点「版本号」7 次", "设置 → 开发者选项"),
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
    val appExitTarget: String? = null,
    val appExitAction: String? = null,
    val confirmKey: String? = null,
    val confirmLabel: String? = null,
)

/**
 * 启动 Shizuku 引导：根据 Android 版本分流。
 * Android 10 (API <= 29) 没有无线调试，走电脑 USB (ADB) 或 Root 路线；
 * Android 11+ (API >= 30) 走无线调试配对与启动路线。
 */
private fun startShizukuPage(facts: OnboardingFacts, completedBefore: Boolean): GuidePage {
    if (completedBefore) {
        return GuidePage(
            title = "Shizuku 需要重新启动",
            intro = "手机重启后 Shizuku 会自动停止，这是系统的正常限制。打开 Shizuku 再点一次「启动」即可，无需重新配对。",
            steps = listOf(
                "打开手机的「无线调试」（Android 10 需连电脑运行启动脚本）",
                "打开 Shizuku，点击「启动」",
                "完成后切回蟹啦继续",
            ),
            note = "如果未重启但提示断连，请先确认后台没有被系统杀死。",
            action = "打开 Shizuku",
            secondary = "打开开发者选项",
            waiting = "正在等待 Shizuku 启动…",
            appExitTarget = "Shizuku",
            appExitAction = "点击「启动」按钮",
        )
    }

    // Android 10 及更早系统分流：无无线调试
    if (!facts.supportsWirelessDebugging) {
        val path = devPathFor(Build.MANUFACTURER)
        if (!facts.developerEnabled) {
            return GuidePage(
                title = "开启开发者选项与 USB 调试",
                intro = "检测到你的系统版本为 Android " + facts.androidSdkInt + "（Android 10 或更低版本）。该系统未内置无线调试，需要通过数据线连接电脑激活 Shizuku（已 Root 手机可直接启动）。请先开启开发者选项。",
                steps = listOf(
                    "点下面的「打开关于手机」",
                    path.about + "，直到提示「已进入开发者模式」",
                    "进入「开发者选项」，找到并打开「USB 调试」",
                    "完成后切回蟹啦继续",
                ),
                note = path.brand + " 手机通常按此路径操作。若身边暂无电脑且未 Root，可点击下方「先看看功能」浏览界面与使用攻略。",
                action = "打开关于手机",
                secondary = "直接打开开发者选项",
                waiting = "正在等待开发者选项开启…",
                appExitTarget = "系统设置",
                appExitAction = "连续点击版本号 7 次并开启 USB 调试",
                confirmKey = if (facts.developerState.isUnknown) "developer_options" else null,
                confirmLabel = if (facts.developerState.isUnknown) "我已开启，继续下一步" else null,
            )
        }
        return GuidePage(
            title = "通过电脑 USB 启动 Shizuku",
            intro = "Android 10 系统需通过数据线连接电脑激活 Shizuku 服务（手机若已 Root，可直接在 Shizuku 应用中点「启动」）。",
            steps = listOf(
                "使用 USB 数据线将手机连接电脑；手机弹出「允许 USB 调试」时勾选始终允许",
"电脑下载 Shizuku 官方启动脚本，或在终端执行：\nadb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh",
                "在 Shizuku 中看到「已启动」后，切回蟹啦继续",
            ),
            note = "如身边暂时没有电脑，可点击下方「先看看功能」浏览主界面，稍后在设置中查看完整攻略与连接说明。",
            action = "打开 Shizuku 查看状态",
            secondary = "打开开发者选项确认 USB 调试",
            waiting = "正在等待 Shizuku 启动…",
            appExitTarget = "Shizuku",
            appExitAction = "确认 Shizuku 服务启动状态",
        )
    }

    // Android 11+ 原生无线调试路线
    val path = devPathFor(Build.MANUFACTURER)
    if (!facts.developerEnabled) {
        return GuidePage(
            title = "先开启开发者选项",
            intro = "手机默认隐藏了「开发者选项」。打开它不会改变日常使用方式，只是多出一个调试开关菜单。",
            steps = listOf(
                "点下面的「打开关于手机」",
                path.about + "，直到提示「已进入开发者模式」",
                "返回蟹啦，页面会自动识别并继续",
            ),
            note = path.brand + " 手机通常按此路径操作。若系统限制自动检测，开启后可点击「我已打开」继续。",
            action = "打开关于手机",
            secondary = "直接打开开发者选项",
            waiting = "正在等待开发者选项开启…",
            appExitTarget = "系统设置",
            appExitAction = "连续点击版本号 7 次开启开发者选项",
            confirmKey = if (facts.developerState.isUnknown) "developer_options" else null,
            confirmLabel = if (facts.developerState.isUnknown) "我已打开，继续下一步" else null,
        )
    }

    if (!facts.wirelessDebugEnabled) {
        return GuidePage(
            title = "打开无线调试",
            intro = "开发者选项已开启。接下来打开「无线调试」，Shizuku 才能完成无线配对。请先连上 Wi-Fi。",
            steps = listOf(
                "点下面的「打开开发者选项」",
                "找到「无线调试」，打开开关（部分手机需点进无线调试子页面再打开）",
                "返回蟹啦，页面会自动识别并继续",
            ),
            note = path.brand + " 的入口：" + path.dev + "。找不到时，在开发者选项顶部搜索「无线调试」。",
            action = "打开开发者选项",
            waiting = "正在等待无线调试开启…",
            appExitTarget = "开发者选项",
            appExitAction = "开启「无线调试」开关",
            confirmKey = if (facts.wirelessDebugState.isUnknown) "wireless_debug" else null,
            confirmLabel = if (facts.wirelessDebugState.isUnknown) "我已打开，继续下一步" else null,
        )
    }

    return GuidePage(
        title = "配对并启动 Shizuku",
        intro = "配对成功只是让 Shizuku 拿到无线调试的钥匙，服务还没启动；还要回到 Shizuku 点「启动」，" +
            "本页才会自动继续。请保持 Wi-Fi 连接；之前已经配对过的，直接点「启动」即可，配对码只有首次需要。",
        steps = listOf(
            "点下面的「打开 Shizuku」，进入「通过无线调试启动」区域",
            "点「配对」。6 位配对码显示在系统的「使用配对码配对设备」弹窗里；下拉通知栏，" +
                "在配对通知的输入框填入这 6 位数字",
            "看到「配对成功」后返回 Shizuku 主界面，点「启动」",
            "看到 Shizuku 显示「已启动」再切回蟹啦；只有服务真的运行，这里才会继续",
        ),
        note = "配对状态本身无法自动读取。蟹啦只认「Shizuku 服务已启动」：只配对成功但没点「启动」，" +
            "这里会一直等待，这是正常的，不是配对失败。\n\n" +
            "找不到输入配对码的通知：下拉通知栏找「使用配对码配对设备」；还是没有，就回无线调试页面点" +
            "「使用配对码配对设备」，再在 Shizuku 点一次「配对」。不要把它和蟹啦的「托管运行中」通知弄混。\n\n" +
            "配对码过期或输错，只重做这一步：重新点「配对」拿新码即可，不用重装 Shizuku，也不用重开引导。" +
            "蟹啦不读取、不保存，也不会要求你发送配对码。\n\n" +
            "无线调试一直失败时可用电脑：手机开启 USB 调试并用数据线连电脑，电脑装好 platform-tools（Android 开发者网站可下载）后执行\n" +
            "adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh\n" +
            "看到 Shizuku「已启动」再回到这里。没有电脑且未 Root 时无法启动 Shizuku；" +
            "可以先点「先去主页看看」，但托管要等激活后才能用，步骤随时可在设置的使用攻略里查。",
        action = "打开 Shizuku",
        waiting = "正在等待 Shizuku 启动…",
        appExitTarget = "Shizuku",
        appExitAction = "完成配对并点击启动",
        confirmKey = "shizuku_started_by_pairing",
        confirmLabel = "Shizuku 已显示「已启动」，仍在这里等待",
    )
}

private fun pageFor(
    step: OnboardingStep,
    state: ShizukuState,
    completedBefore: Boolean,
    facts: OnboardingFacts,
) = when (step) {
    OnboardingStep.WELCOME -> GuidePage(
        title = "你好，我是蟹啦",
        intro = "我会替你照看 QQ 宠物：打工、上课、喂食。开始前需要在手机上打开几个开关，全程约 5 分钟，不用连电脑，也不需要 root。",
        steps = listOf(
            "装一个叫 Shizuku 的配套小工具",
            "启动它，并允许本应用使用",
            "打开悬浮窗、通知和后台运行",
        ),
        note = "每一步都会说明点哪里，操作完成后会自动进入下一步。你也可以先看看功能，稍后再来配置。",
        action = "开始设置",
        crab = R.drawable.crab_wave,
    )
    OnboardingStep.INSTALL_SHIZUKU -> GuidePage(
        title = "安装 Shizuku",
        intro = "宠物托管要在后台的虚拟屏幕上操作 QQ，普通应用没有该系统能力，Shizuku 就是用来安全开放该权限的配套工具。",
        steps = listOf(
            "点下面的按钮，打开 Shizuku 下载页面",
            "下载以 .apk 结尾的最新安装包",
            "安装应用；若系统提示风险，选择「继续安装」或信任安装",
            "安装完成后切回蟹啦",
        ),
        note = "官方下载页为 GitHub，如遇网络打不开，可更换网络或稍后在设置使用攻略中查看国内镜像。",
        action = "打开下载页",
        waiting = "正在等待 Shizuku 安装完成…",
        appExitTarget = "浏览器下载页",
        appExitAction = "下载并安装 Shizuku APK",
    )
    OnboardingStep.START_SHIZUKU -> startShizukuPage(facts, completedBefore)
    OnboardingStep.GRANT_SHIZUKU -> GuidePage(
        title = "允许本应用使用 Shizuku",
        intro = "Shizuku 服务已经在运行了。点击下方按钮，在弹出的系统授权窗口中选择「始终允许」。",
        note = if (state is ShizukuState.PermissionDenied) {
            "如果之前误点了拒绝，可以打开 Shizuku，在「已授权应用」列表中直接勾选开启「蟹啦」。"
        } else {
            "授权仅用于后台创建虚拟屏幕及发送操作指令，不会读取隐私文件。"
        },
        action = "请求授权",
        secondary = "打开 Shizuku 手动勾选",
        waiting = "授权后会自动继续…",
        appExitTarget = "Shizuku 授权弹窗",
        appExitAction = "选择「始终允许」",
    )
    OnboardingStep.OVERLAY -> GuidePage(
        title = "打开悬浮窗",
        intro = "托管时屏幕边缘会显示一个小胶囊，让你离开本应用也能看清当前进度并随时暂停或停止。",
        steps = listOf(
            "点下面的按钮",
            "在悬浮窗权限列表里找到蟹啦，打开开关",
            "返回蟹啦",
        ),
        note = "此权限为推荐项，跳过不影响托管主流程，但离开 App 后看不到进度胶囊。" +
            "这个开关就是系统设置里的「悬浮窗」。",
        action = "去打开",
        waiting = "打开后会自动继续…",
        appExitTarget = "系统悬浮窗设置",
        appExitAction = "允许蟹啦显示悬浮窗",
    )
    OnboardingStep.NOTIFICATION -> GuidePage(
        title = "打开通知",
        intro = "当托管完成、金币不足、遇到异常中途停下时，通知栏会立即告诉你结果与当前状态。",
        steps = listOf(
            "点下面的按钮",
            "打开「允许通知」开关",
            "返回蟹啦",
        ),
        note = "此权限为推荐项，跳过不影响托管主流程。这个开关就是系统设置里的「通知」。",
        action = "去打开",
        waiting = "打开后会自动继续…",
        appExitTarget = "通知管理设置",
        appExitAction = "开启通知权限",
    )
    OnboardingStep.BACKGROUND -> GuidePage(
        title = "允许后台运行",
        intro = "手机为省电可能会在息屏或切后台时杀掉进程。允许后可降低任务被系统强制中断的概率，" +
            "但不能保证任务一定完整跑完。这个开关就是系统弹窗里的「电池优化」。",
        steps = listOf(
            "点下面的按钮",
            "在电池优化弹窗中选择「允许」或「无限制」",
            "华为 / 小米等机型遇到托管中途被系统终止时，再到「应用启动管理」中设为允许自启动和后台活动；正常使用时不用改",
        ),
        note = "",
        waiting = "允许后会自动继续…",
        appExitTarget = "电池优化弹窗",
        appExitAction = "选择无限制或忽略电池优化",
    )
    OnboardingStep.FINISH -> GuidePage(
        title = "准备好了",
        intro = "基础运行环境已就绪！开始托管前，请再确认这三件事：",
        steps = listOf(
            "手机已安装 QQ 并登录了养宠账号（登录由你在 QQ 内完成，蟹啦不会索要密码）",
            "托管时保持屏幕常亮不锁屏，锁屏可能导致安全停止",
            "长时间托管建议插上充电器，并将系统自动休眠时长调大",
        ),
        note = "你可随时在设置中查看完整使用攻略或进行运行环境排故。",
        action = "开始使用",
        crab = R.drawable.crab_hug,
    )
}

/**
 * 新手引导与授权排故主界面。
 * 支持 Android 10 分流、三态判断手动确认、授权异常可见、连接超时恢复、离开应用操作说明及先看产品预览模式。
 */
@Composable
fun OnboardingScreen(
    state: ShizukuState,
    completedBefore: Boolean,
    permissionError: String? = null,
    onCheck: () -> Unit,
    onRequestPermission: () -> Unit,
    onFinish: () -> Unit,
    onPreview: (() -> Unit)? = null,
    onOpenTutorial: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val store = remember { OnboardingStore(context) }
    var progress by remember { mutableStateOf(store.load()) }

    var facts by remember {
        mutableStateOf(readFacts(context, state, progress.userConfirmedKeys))
    }
    var welcomed by remember {
        mutableStateOf(completedBefore || progress.welcomed)
    }
    var setAside by remember {
        mutableStateOf(if (completedBefore) OnboardingFlow.OPTIONAL else progress.setAsideSteps)
    }

    val latestState by rememberUpdatedState(state)
    val latestCheck by rememberUpdatedState(onCheck)

    // 轮询检测状态
    LaunchedEffect(Unit) {
        while (true) {
            if (latestState !is ShizukuState.Ready) latestCheck()
            facts = readFacts(context, latestState, progress.userConfirmedKeys)
            delay(1500)
        }
    }
    LaunchedEffect(state) {
        facts = readFacts(context, state, progress.userConfirmedKeys)
    }

    // 等待计时，用于超时排故提示
    var waitingSeconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(facts) {
        waitingSeconds = 0
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            waitingSeconds++
        }
    }

    var backTo by remember { mutableStateOf<OnboardingStep?>(null) }
    val current = OnboardingFlow.current(facts, welcomed, setAside)
    val step = backTo ?: current

    LaunchedEffect(current) {
        if (current == OnboardingStep.FINISH && completedBefore) {
            store.saveCompleted(true)
            onFinish()
        }
    }

    val page = pageFor(step, state, completedBefore, facts)

    val primary: () -> Unit = when (step) {
        OnboardingStep.WELCOME -> ({
            welcomed = true
            store.saveWelcomed(true)
            backTo = null
        })
        OnboardingStep.INSTALL_SHIZUKU -> ({
            context.open(Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_RELEASES)))
        })
        OnboardingStep.START_SHIZUKU -> when {
            completedBefore -> ({ context.openShizuku() })
            !facts.developerEnabled -> ({
                context.open(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))
            })
            !facts.wirelessDebugEnabled && facts.supportsWirelessDebugging -> ({
                context.open(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            })
            else -> ({ context.openShizuku() })
        }
        OnboardingStep.GRANT_SHIZUKU -> onRequestPermission
        OnboardingStep.OVERLAY -> ({
            context.open(OverlayPermission.settingsIntent(context))
        })
        OnboardingStep.NOTIFICATION -> ({
            context.open(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            )
        })
        OnboardingStep.BACKGROUND -> ({
            @Suppress("BatteryLife")
            context.open(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + context.packageName)
                )
            )
        })
        OnboardingStep.FINISH -> ({
            store.saveCompleted(true)
            onFinish()
        })
    }

    val secondary: () -> Unit = when (step) {
        OnboardingStep.START_SHIZUKU -> ({
            context.open(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        })
        else -> ({ context.openShizuku() })
    }

    Column(Modifier.fillMaxSize().background(PetTokens.Bg).padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(20.dp))
        val index = OnboardingFlow.COUNTED.indexOf(step)
        if (index >= 0 && !completedBefore) {
            StepProgress(index, OnboardingFlow.COUNTED.size)
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(24.dp))
            page.crab?.let {
                Image(
                    painterResource(it),
                    contentDescription = null,
                    modifier = Modifier.size(96.dp),
                    contentScale = ContentScale.Fit
                )
                Spacer(Modifier.height(8.dp))
            }

            Text(page.title, fontSize = 27.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, color = PetTokens.Ink)
            Spacer(Modifier.height(10.dp))
            Text(page.intro, fontSize = 16.sp, lineHeight = 24.sp, color = PetTokens.Ink2)

            if (page.steps.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(PetTokens.CardRadius))
                        .background(PetTokens.Surface).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    page.steps.forEachIndexed { i, text -> NumberedLine(i + 1, text) }
                }
            }

            // 离开蟹啦前的操作说明与返回路径
            if (page.appExitTarget != null && page.appExitAction != null) {
                Spacer(Modifier.height(14.dp))
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(PetTokens.CardRadius))
                        .background(PetTokens.BlueSoft).padding(14.dp)
                ) {
                    Column {
                        Text("点击后将跳转至：" + page.appExitTarget, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = PetTokens.BlueInk)
                        Spacer(Modifier.height(3.dp))
Text("本次动作：" + page.appExitAction + "。\n完成后请通过多任务切回蟹啦，或点击桌面图标返回。",
                            fontSize = 12.sp, lineHeight = 18.sp, color = PetTokens.BlueInk)
                    }
                }
            }

            // 授权异常排故卡片
            if (step == OnboardingStep.GRANT_SHIZUKU && permissionError != null) {
                Spacer(Modifier.height(14.dp))
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(PetTokens.CardRadius))
                        .background(PetTokens.RedSoft).padding(14.dp)
                ) {
                    Column {
                        Text("授权请求异常", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = PetTokens.RedInk)
                        Spacer(Modifier.height(4.dp))
                        Text(
"错误原因：" + permissionError + "\n解决方式：请打开 Shizuku 应用，进入「已授权应用」，找到「蟹啦」并手动开启授权，然后返回这里。",
                            fontSize = 13.sp, lineHeight = 19.sp, color = PetTokens.RedInk
                        )
                    }
                }
            }

            // 超时与等待提示
            if (waitingSeconds >= 12 && (step == OnboardingStep.START_SHIZUKU || step == OnboardingStep.GRANT_SHIZUKU)) {
                Spacer(Modifier.height(14.dp))
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(PetTokens.CardRadius))
                        .background(PetTokens.AmberSoft).padding(12.dp)
                ) {
                    Column {
                        Text("尚未检测到更新？", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = PetTokens.AmberInk)
                        Spacer(Modifier.height(4.dp))
                        Text("如果你已经在外部完成了操作，可以点击「立即重新检测」刷新状态；或打开 Shizuku 确认服务是否处于运行状态。",
                            fontSize = 12.sp, lineHeight = 17.sp, color = PetTokens.AmberInk)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(
                                Modifier.clip(RoundedCornerShape(6.dp)).background(PetTokens.Surface)
                                    .clickable { onCheck(); waitingSeconds = 0 }.padding(horizontal = 10.dp, vertical = 6.dp)
                            ) { Text("立即重新检测", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = PetTokens.Blue) }
                            Box(
                                Modifier.clip(RoundedCornerShape(6.dp)).background(PetTokens.Surface)
                                    .clickable { context.openShizuku() }.padding(horizontal = 10.dp, vertical = 6.dp)
                            ) { Text("打开 Shizuku", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = PetTokens.Ink) }
                        }
                    }
                }
            }

            page.note?.let {
                Spacer(Modifier.height(14.dp))
                Text(it, fontSize = 13.sp, lineHeight = 19.sp, color = PetTokens.Ink2)
            }

            if (step == OnboardingStep.START_SHIZUKU && !completedBefore && !facts.developerEnabled) {
                OtherBrands()
            }
            Spacer(Modifier.height(16.dp))
        }

        page.waiting?.let {
            Text(
                it, Modifier.fillMaxWidth().padding(bottom = 10.dp),
                fontSize = 13.sp, color = PetTokens.Ink3, textAlign = TextAlign.Center
            )
        }

        // 主操作按钮
        page.action?.let { GuideButton(it, primary) }

        // 手动确认无法自动识别项（针对 UNKNOWN 状态）
        if (page.confirmKey != null && page.confirmLabel != null) {
            TextAction(page.confirmLabel) {
                store.confirmKey(page.confirmKey)
                progress = store.load()
                facts = readFacts(context, state, progress.userConfirmedKeys)
            }
        }

        // 欢迎页的次要按钮：先看看功能
        if (step == OnboardingStep.WELCOME && onPreview != null) {
            TextAction("先看看功能（暂不配置）") {
                store.savePreviewMode(true)
                onPreview()
            }
        }

        page.secondary?.let { TextAction(it, secondary) }

        OnboardingFlow.previous(step)?.let { previous ->
            TextAction("上一步") { backTo = previous }
        }

        if (backTo != null) {
            TextAction("回到当前进度") { backTo = null }
        }

        if (step in OnboardingFlow.OPTIONAL) {
            TextAction("暂时跳过") {
                store.setAside(step)
                setAside = setAside + step
            }
        }

        // 未完成设置时允许查看产品
        if (step != OnboardingStep.WELCOME && step != OnboardingStep.FINISH && onPreview != null) {
            TextAction("先去主页看看") {
                store.savePreviewMode(true)
                onPreview()
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

fun Context.openShizuku() {
    val launch = packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
    open(launch ?: Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_RELEASES)))
}

@Composable
private fun StepProgress(index: Int, total: Int) {
    Text("第 " + (index + 1) + " 步，共 " + total + " 步", fontSize = 13.sp, color = PetTokens.Ink2)
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(total) { i ->
            Box(
                Modifier.weight(1f).height(3.dp).clip(CircleShape)
                    .background(if (i <= index) PetTokens.Blue else PetTokens.Line)
            )
        }
    }
}

@Composable
private fun NumberedLine(number: Int, text: String) {
    Row {
        Box(
            Modifier.size(22.dp).clip(CircleShape).background(PetTokens.BlueSoft),
            contentAlignment = Alignment.Center
        ) {
            Text("" + number, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = PetTokens.BlueInk)
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
            Text(p.brand + "：" + p.about + "。开发者选项：" + p.dev, fontSize = 13.sp, lineHeight = 19.sp, color = PetTokens.Ink2)
        }
    }
}

@Composable
private fun TextAction(label: String, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(44.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, fontSize = 15.sp, color = PetTokens.Blue)
    }
}
