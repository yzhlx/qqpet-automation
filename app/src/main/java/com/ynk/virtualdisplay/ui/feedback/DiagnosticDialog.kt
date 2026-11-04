package com.ynk.virtualdisplay.ui.feedback

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ynk.virtualdisplay.automation.DiagnosticLevel
import com.ynk.virtualdisplay.automation.DiagnosticReport
import com.ynk.virtualdisplay.automation.EnvironmentDiagnostic
import com.ynk.virtualdisplay.data.model.ShizukuState
import com.ynk.virtualdisplay.data.repository.IDisplayRepository
import com.ynk.virtualdisplay.ui.kit.PetChip
import com.ynk.virtualdisplay.ui.theme.PetTokens
import kotlinx.coroutines.launch

@Composable
fun DiagnosticDialog(
    repository: IDisplayRepository,
    shizukuState: ShizukuState,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<DiagnosticReport?>(null) }
    var checking by remember { mutableStateOf(true) }

    fun runDiag(forceCapabilities: Boolean = false) {
        checking = true
        scope.launch {
            val diag = EnvironmentDiagnostic(context, repository)
            report = diag.runCheck(shizukuState, forceCapabilities = forceCapabilities)
            checking = false
        }
    }

    LaunchedEffect(Unit) {
        runDiag()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("运行环境分层诊断", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = PetTokens.Ink)
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (checking) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(160.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = PetTokens.Blue)
                            Spacer(Modifier.height(12.dp))
                            Text("正在分层检测基础状态与虚拟屏能力…", fontSize = 13.sp, color = PetTokens.Ink2)
                        }
                    }
                } else {
                    val rep = report
                    if (rep != null) {
                        Text(
                            rep.manufacturer + " · " + rep.androidVersion,
                            fontSize = 12.sp,
                            color = PetTokens.Ink3
                        )
                        Spacer(Modifier.height(10.dp))
                        if (rep.capabilityChecksReused) {
                            Text(
                                "本次沿用上次已通过的能力检测，没有重复创建测试屏幕。重启、升级或 Shizuku 断连后会自动重查；也可以点「重新检测」。",
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                                color = PetTokens.Ink3,
                            )
                            Spacer(Modifier.height(10.dp))
                        }

                        // 分层展示
                        val groups = listOf(
                            DiagnosticLevel.BASE to "一、基础状态",
                            DiagnosticLevel.CAPABILITY to "二、实际能力（自检不留残留）",
                            DiagnosticLevel.TARGET_APP to "三、QQ 状态（房间在开始任务时核对）",
                        )

                        for ((lvl, label) in groups) {
                            val items = rep.items.filter { it.level == lvl }
                            if (items.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = PetTokens.Ink)
                                Spacer(Modifier.height(6.dp))
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    for (item in items) {
                                        val chipText = when {
                                            item.unknown -> "待核对"
                                            item.passed -> "正常"
                                            else -> "异常"
                                        }
                                        val chipBg = when {
                                            item.unknown -> PetTokens.AmberSoft
                                            item.passed -> PetTokens.GreenSoft
                                            else -> PetTokens.RedSoft
                                        }
                                        val chipFg = when {
                                            item.unknown -> PetTokens.AmberInk
                                            item.passed -> PetTokens.GreenInk
                                            else -> PetTokens.RedInk
                                        }
                                        Box(
                                            Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(chipBg)
                                                .padding(horizontal = 10.dp, vertical = 8.dp)
                                        ) {
                                            Column {
                                                Row(
                                                    Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        item.title,
                                                        fontSize = 13.sp,
                                                        fontWeight = FontWeight.Medium,
                                                        color = chipFg
                                                    )
                                                    PetChip(chipText, chipBg, chipFg)
                                                }
                                                Spacer(Modifier.height(2.dp))
                                                Text(
                                                    item.message,
                                                    fontSize = 12.sp,
                                                    color = chipFg
                                                )
                                                if ((!item.passed || item.unknown) && item.remedy != null) {
                                                    Spacer(Modifier.height(4.dp))
                                                    Text(
                                                        "下一步：" + item.remedy,
                                                        fontSize = 11.sp,
                                                        lineHeight = 16.sp,
                                                        color = chipFg
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭", color = PetTokens.Blue)
            }
        },
        dismissButton = {
            if (!checking) {
                TextButton(onClick = { runDiag(forceCapabilities = true) }) {
                    Text("重新检测", color = PetTokens.Ink2)
                }
            }
        }
    )
}
