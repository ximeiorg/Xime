package com.kingzcheung.xime.ui.settings

import com.kingzcheung.xime.R

import com.kingzcheung.xime.i18n.tr

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.UploadResult
import com.kingzcheung.xime.settings.WirelessImportHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WirelessImportSheet(
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    lexiconSchemeIds: List<String> = emptyList(),
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val helper = remember(lexiconSchemeIds) {
        WirelessImportHelper(context, lexiconSchemeIds)
    }

    var serverUrl by remember { mutableStateOf<String?>(null) }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var uploadResults by remember { mutableStateOf<List<UploadResult>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.Default) {
            val port = helper.findAvailablePort()
            val url = helper.start(port)
            if (url != null) {
                serverUrl = url
                qrBitmap = helper.generateQrCode(url)
            } else {
                error = tr(R.string.s_wufahuoqu_ip_dizhi_qingquebaoy_1a483)
            }
        }
    }

    LaunchedEffect(Unit) {
        helper.uploadResults.collectLatest { result ->
            uploadResults = uploadResults + result
            if (result.success) onRefresh()
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            helper.stop()
            onDismiss()
        },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Wifi,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(tr(R.string.s_liulanqidaoru), style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = tr(R.string.s_quebaoshebeiheshoujizaitongyig_1690a),
                style = MaterialTheme.typography.bodySmall,
                color = settingsSecondaryColor()
            )

            Spacer(modifier = Modifier.height(20.dp))

            if (error != null) {
                Text(
                    text = error!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            } else if (serverUrl != null) {
                if (qrBitmap != null) {
                    Image(
                        bitmap = qrBitmap!!.asImageBitmap(),
                        contentDescription = tr(R.string.s_erweima),
                        modifier = Modifier
                            .size(200.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                Text(
                    text = serverUrl!!,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    ),
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = tr(R.string.s_diannaoliulanqidakaishangfangw_0b91b),
                    style = MaterialTheme.typography.bodySmall,
                    color = settingsSecondaryColor(),
                    textAlign = TextAlign.Center
                )
                Text(
                    text = if (lexiconSchemeIds.isNotEmpty()) {
                    tr(R.string.s_tuozhuaishurucikuwenjian)
                } else {
                    tr(R.string.s_tuozhuaishurufanganhuochajianw_5164d)
                },
                    style = MaterialTheme.typography.bodySmall,
                    color = settingsSecondaryColor(),
                    textAlign = TextAlign.Center
                )
            }

            if (uploadResults.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = tr(R.string.s_yidaoru_2, uploadResults.size),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(4.dp))
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                ) {
                    items(uploadResults.takeLast(10), key = { it.fileName + System.identityHashCode(it) }) { result ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 2.dp)
                        ) {
                            Icon(
                                imageVector = if (result.success) Icons.Default.CheckCircle else Icons.Default.Error,
                                contentDescription = null,
                                tint = if (result.success) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Column {
                                Text(
                                    text = result.fileName,
                                    style = MaterialTheme.typography.bodySmall
                                )
                                val hint = result.message ?: result.error
                                if (!hint.isNullOrBlank()) {
                                    Text(
                                        text = hint,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = settingsSecondaryColor(),
                                        maxLines = 2,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
