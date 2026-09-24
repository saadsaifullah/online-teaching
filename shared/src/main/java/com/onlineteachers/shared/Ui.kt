package com.onlineteachers.shared

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

@Composable fun SchoolTheme(content: @Composable () -> Unit) {
    val navy = Color(0xFF12203B); val blue = Color(0xFF215AC8)
    val colors = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFFA9C7FF), secondary = Color(0xFFD6AF55))
    else lightColorScheme(primary = blue, secondary = navy, tertiary = Color(0xFF795A0C), background = Color(0xFFF3F6FB))
    MaterialTheme(colorScheme = colors, content = content)
}
@Composable fun SchoolPage(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("ONLINE TEACHERS", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.headlineMedium)
            content()
        }
    }
}
@Composable fun StatusCard(status: String) {
    Card(Modifier.fillMaxWidth()) { Text(status, Modifier.padding(18.dp), style = MaterialTheme.typography.titleMedium) }
}
@Composable fun SettingsFields(address: String, onAddress: (String) -> Unit, key: String, onKey: (String) -> Unit, enabled: Boolean) {
    OutlinedTextField(address, onAddress, label = { Text("Server: wss://your-service/ws") }, singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(key, onKey, label = { Text("This device's setup key") }, singleLine = true, enabled = enabled, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
}
@Composable fun VideoSurface(modifier: Modifier, onSurface: (Surface?) -> Unit) {
    val latest by rememberUpdatedState(onSurface)
    AndroidView(modifier = modifier, factory = { context ->
        SurfaceView(context).apply {
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) { latest(holder.surface) }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { latest(holder.surface) }
                override fun surfaceDestroyed(holder: SurfaceHolder) { latest(null) }
            })
        }
    })
}
