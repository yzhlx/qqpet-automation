package com.ynk.virtualdisplay.ui.feedback

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ynk.virtualdisplay.ui.theme.PetTokens
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Only channels with a configured destination are offered; a dead button is worse than none. */
@Composable
fun FeedbackDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val channels = remember { FeedbackChannels(context) }
    var description by remember { mutableStateOf("") }
    var includeScreenshots by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    fun send(deliver: (File) -> String?) {
        busy = true
        scope.launch {
            val result = runCatching {
                val zip = withContext(Dispatchers.IO) { channels.buildZip(description, includeScreenshots) }
                deliver(zip)
            }
            busy = false
            val message = result.fold({ it }, { "反馈包发送失败：${it.message ?: "未知错误"}" })
            if (message != null) Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            if (result.isSuccess) onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PetTokens.Surface,
        title = { Text("问题反馈") },
        text = {
            Column {
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("哪里出错了？当时在做什么？") },
                    minLines = 3,
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.fillMaxWidth().clickable { includeScreenshots = !includeScreenshots },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = includeScreenshots, onCheckedChange = { includeScreenshots = it })
                    Text("附上最近一次截图（可能露出 QQ 昵称和头像）", fontSize = 13.sp)
                }
                Text(
                    "反馈包含运行日志、运行结果、设备型号和版本号，不含账号密码。",
                    fontSize = 12.sp,
                    color = PetTokens.Ink2,
                )
                if (channels.qqGroup.isNotBlank()) {
                    Text(
                        "内测群 ${channels.qqGroup}，群号会自动复制。",
                        fontSize = 12.sp,
                        color = PetTokens.Ink2,
                    )
                }
                if (channels.qqGroup.isBlank() && channels.formUrl.isBlank()) {
                    Text("这个版本还没配置反馈渠道。", fontSize = 13.sp, color = PetTokens.RedInk)
                }
            }
        },
        confirmButton = {
            if (channels.qqGroup.isNotBlank()) {
                TextButton(enabled = !busy, onClick = {
                    send { zip -> channels.shareToQq(zip, description); null }
                }) { Text("发到 QQ 群") }
            }
            if (channels.formUrl.isNotBlank()) {
                TextButton(enabled = !busy, onClick = {
                    send { zip -> "已存到“下载”：${channels.openWebForm(zip)}，请在网页里上传" }
                }) { Text("网页提交") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
