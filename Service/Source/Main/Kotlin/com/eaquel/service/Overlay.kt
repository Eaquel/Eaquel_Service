@file:Suppress("DEPRECATION", "OPT_IN_USAGE")
package com.eaquel.service

import android.app.Activity
import android.content.Context
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import androidx.core.view.WindowCompat
import androidx.datastore.preferences.core.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private fun svgIcon(vw: Float = 24f, vh: Float = 24f, pathData: String): ImageVector =
    ImageVector.Builder(defaultWidth = vw.dp, defaultHeight = vh.dp, viewportWidth = vw, viewportHeight = vh)
        .path { parseSvgPath(pathData) }.build()

private fun androidx.compose.ui.graphics.vector.PathBuilder.parseSvgPath(d: String) {
    var i = 0; val s = d.trim()
    var cx = 0f; var cy = 0f
    while (i < s.length) {
        while (i < s.length && s[i] == ' ') i++
        if (i >= s.length) break
        val cmd = s[i++]
        fun num(): Float {
            while (i < s.length && (s[i] == ' ' || s[i] == ',')) i++
            val start = i
            if (i < s.length && s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            return if (i > start) s.substring(start, i).toFloatOrNull() ?: 0f else 0f
        }
        when (cmd) {
            'M' -> { val x=num(); val y=num(); moveTo(x,y); cx=x; cy=y }
            'm' -> { val x=num(); val y=num(); moveToRelative(x,y); cx+=x; cy+=y }
            'L' -> { val x=num(); val y=num(); lineTo(x,y); cx=x; cy=y }
            'l' -> { val x=num(); val y=num(); lineToRelative(x,y); cx+=x; cy+=y }
            'H' -> { val x=num(); lineTo(x,cy); cx=x }
            'h' -> { val dx=num(); lineToRelative(dx,0f); cx+=dx }
            'V' -> { val y=num(); lineTo(cx,y); cy=y }
            'v' -> { val dy=num(); lineToRelative(0f,dy); cy+=dy }
            'C' -> { val x1=num();val y1=num();val x2=num();val y2=num();val x=num();val y=num(); curveTo(x1,y1,x2,y2,x,y); cx=x; cy=y }
            'c' -> { val dx1=num();val dy1=num();val dx2=num();val dy2=num();val dx=num();val dy=num(); curveToRelative(dx1,dy1,dx2,dy2,dx,dy); cx+=dx; cy+=dy }
            'S' -> { val x2=num();val y2=num();val x=num();val y=num(); reflectiveCurveTo(x2,y2,x,y); cx=x; cy=y }
            's' -> { val dx2=num();val dy2=num();val dx=num();val dy=num(); reflectiveCurveToRelative(dx2,dy2,dx,dy); cx+=dx; cy+=dy }
            'Q' -> { val x1=num();val y1=num();val x=num();val y=num(); quadTo(x1,y1,x,y); cx=x; cy=y }
            'q' -> { val dx1=num();val dy1=num();val dx=num();val dy=num(); quadToRelative(dx1,dy1,dx,dy); cx+=dx; cy+=dy }
            'A','a' -> { num();num();num();num();num(); val tx=num();val ty=num()
                if(cmd=='A') { lineTo(tx,ty); cx=tx; cy=ty } else { lineToRelative(tx,ty); cx+=tx; cy+=ty } }
            'Z','z' -> close()
        }
    }
}

object EIcons {
    val Home: ImageVector          get() = svgIcon(pathData = "M10 20v-6h4v6h5v-8h3L12 3 2 12h3v8z")
    val Wifi: ImageVector          get() = svgIcon(pathData = "M1 9l2 2c4.97-4.97 13.03-4.97 18 0l2-2C16.93 2.93 7.08 2.93 1 9zm8 8l3 3 3-3c-1.65-1.66-4.34-1.66-6 0zm-4-4l2 2c2.76-2.76 7.24-2.76 10 0l2-2C15.14 9.14 8.87 9.14 5 13z")
    val WifiOff: ImageVector       get() = svgIcon(pathData = "M21 1L1 21l1.41 1.41L6.56 18H6c-1.1 0-2-.9-2-2V8h1.17l-3.6-3.6A11.98 11.98 0 0 0 0 12c0 3.87 1.84 7.3 4.7 9.53l1.42-1.42C3.68 18.12 2 15.24 2 12c0-2.18.61-4.21 1.67-5.94l1.37 1.37C4.37 8.87 4 10.38 4 12c0 2.2.89 4.21 2.34 5.66l1.41-1.41A5.95 5.95 0 0 1 6 12c0-1.31.41-2.52 1.1-3.52L8.54 9.9c-.33.61-.54 1.3-.54 2.1 0 1.1.45 2.1 1.17 2.83l1.41-1.41A2 2 0 0 1 10 12c0-.41.13-.79.35-1.11L12 12.59V11c0-1.1.9-2 2-2h.59L23 17.59 21.59 19 4.41 1.83z")
    val Settings: ImageVector      get() = svgIcon(pathData = "M19.14,12.94c0.04-0.3,0.06-0.61,0.06-0.94c0-0.32-0.02-0.64-0.07-0.94l2.03-1.58c0.18-0.14,0.23-0.41,0.12-0.61l-1.92-3.32c-0.12-0.22-0.37-0.29-0.59-0.22l-2.39,0.96c-0.5-0.38-1.03-0.7-1.62-0.94L14.4,2.81c-0.04-0.24-0.24-0.41-0.48-0.41h-3.84c-0.24,0-0.43,0.17-0.47,0.41L9.25,5.35C8.66,5.59,8.12,5.92,7.63,6.29L5.24,5.33c-0.22-0.08-0.47,0-0.59,0.22L2.74,8.87C2.62,9.08,2.66,9.34,2.86,9.48l2.03,1.58C4.84,11.36,4.8,11.69,4.8,12s0.02,0.64,0.07,0.94l-2.03,1.58c-0.18,0.14-0.23,0.41-0.12,0.61l1.92,3.32c0.12,0.22,0.37,0.29,0.59,0.22l2.39-0.96c0.5,0.38,1.03,0.7,1.62,0.94l0.36,2.54c0.05,0.24,0.24,0.41,0.48,0.41h3.84c0.24,0,0.44-0.17,0.47-0.41l0.36-2.54c0.59-0.24,1.13-0.56,1.62-0.94l2.39,0.96c0.22,0.08,0.47,0,0.59-0.22l1.92-3.32c0.12-0.22,0.07-0.47-0.12-0.61L19.14,12.94z M12,15.6c-1.98,0-3.6-1.62-3.6-3.6s1.62-3.6,3.6-3.6s3.6,1.62,3.6,3.6S13.98,15.6,12,15.6z")
    val Shield: ImageVector        get() = svgIcon(pathData = "M12 1L3 5v6c0 5.55 3.84 10.74 9 12 5.16-1.26 9-6.45 9-12V5l-9-4z")
    val Security: ImageVector      get() = svgIcon(pathData = "M12 1L3 5v6c0 5.55 3.84 10.74 9 12 5.16-1.26 9-6.45 9-12V5l-9-4zm0 4l5 2.18V11c0 3.5-2.33 6.79-5 7.93-2.67-1.14-5-4.43-5-7.93V7.18L12 5z")
    val Warning: ImageVector       get() = svgIcon(pathData = "M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2v-4h2v4z")
    val Error: ImageVector         get() = svgIcon(pathData = "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-2h2v2zm0-4h-2V7h2v6z")
    val Info: ImageVector          get() = svgIcon(pathData = "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z")
    val Check: ImageVector         get() = svgIcon(pathData = "M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z")
    val CheckCircle: ImageVector   get() = svgIcon(pathData = "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 15l-5-5 1.41-1.41L10 14.17l7.59-7.59L19 8l-9 9z")
    val Search: ImageVector        get() = svgIcon(pathData = "M15.5 14h-.79l-.28-.27C15.41 12.59 16 11.11 16 9.5 16 5.91 13.09 3 9.5 3S3 5.91 3 9.5 5.91 16 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z")
    val Refresh: ImageVector       get() = svgIcon(pathData = "M17.65 6.35C16.2 4.9 14.21 4 12 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08c-.82 2.33-3.04 4-5.65 4-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z")
    val Delete: ImageVector        get() = svgIcon(pathData = "M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z")
    val Close: ImageVector         get() = svgIcon(pathData = "M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z")
    val Block: ImageVector         get() = svgIcon(pathData = "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zM4 12c0-4.42 3.58-8 8-8 1.85 0 3.55.63 4.9 1.68L5.68 16.9C4.63 15.55 4 13.85 4 12zm8 8c-1.85 0-3.55-.63-4.9-1.68L18.32 7.1C19.37 8.45 20 10.15 20 12c0 4.42-3.58 8-8 8z")
    val PlayArrow: ImageVector     get() = svgIcon(pathData = "M8 5v14l11-7z")
    val Stop: ImageVector          get() = svgIcon(pathData = "M6 6h12v12H6z")
    val Memory: ImageVector        get() = svgIcon(pathData = "M15 9H9v6h6V9zm-2 4h-2v-2h2v2zm8-2V9h-2V7c0-1.1-.9-2-2-2h-2V3h-2v2h-2V3H9v2H7c-1.1 0-2 .9-2 2v2H3v2h2v2H3v2h2v2c0 1.1.9 2 2 2h2v2h2v-2h2v2h2v-2h2c1.1 0 2-.9 2-2v-2h2v-2h-2v-2h2zm-4 6H7V7h10v10z")
    val Storage: ImageVector       get() = svgIcon(pathData = "M2 20h20v-4H2v4zm2-3h2v2H4v-2zM2 4v4h20V4H2zm4 3H4V5h2v2zm-4 7h20v-4H2v4zm2-3h2v2H4v-2z")
    val Code: ImageVector          get() = svgIcon(pathData = "M9.4 16.6L4.8 12l4.6-4.6L8 6l-6 6 6 6 1.4-1.4zm5.2 0l4.6-4.6-4.6-4.6L16 6l6 6-6 6-1.4-1.4z")
    val Terminal: ImageVector      get() = svgIcon(pathData = "M20 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zm0 14H4V6h16v12zm-2-1H6v-2h12v2zm-8.5-5.5l1.41 1.41L8.33 15 6.92 13.59l2.58-1.09zm5 0l-1.41 1.41L16.67 15l1.41-1.41-2.58-1.09zM6 9.41L7.41 8 10 10.59 8.59 12 6 9.41zm12 0L16.59 8 14 10.59 15.41 12 18 9.41z")
    val Android: ImageVector       get() = svgIcon(pathData = "M6 18c0 .55.45 1 1 1h1v3.5c0 .83.67 1.5 1.5 1.5s1.5-.67 1.5-1.5V19h2v3.5c0 .83.67 1.5 1.5 1.5s1.5-.67 1.5-1.5V19h1c.55 0 1-.45 1-1V8H6v10zM3.5 8C2.67 8 2 8.67 2 9.5v7c0 .83.67 1.5 1.5 1.5S5 17.33 5 16.5v-7C5 8.67 4.33 8 3.5 8zm17 0c-.83 0-1.5.67-1.5 1.5v7c0 .83.67 1.5 1.5 1.5s1.5-.67 1.5-1.5v-7c0-.83-.67-1.5-1.5-1.5zm-4.97-5.84l1.3-1.3c.2-.2.2-.51 0-.71-.2-.2-.51-.2-.71 0l-1.48 1.48C13.85 1.23 12.95 1 12 1c-.96 0-1.86.23-2.66.63L7.85.15c-.2-.2-.51-.2-.71 0-.2.2-.2.51 0 .71l1.31 1.31C7.08 3.04 6 4.67 6 6.5h12c0-1.83-1.08-3.46-2.47-4.34zM10 5H9V4h1v1zm5 0h-1V4h1v1z")
    val Lock: ImageVector          get() = svgIcon(pathData = "M18 8h-1V6c0-2.76-2.24-5-5-5S7 3.24 7 6v2H6c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V10c0-1.1-.9-2-2-2zm-6 9c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2zm3.1-9H8.9V6c0-1.71 1.39-3.1 3.1-3.1 1.71 0 3.1 1.39 3.1 3.1v2z")
    val Language: ImageVector      get() = svgIcon(pathData = "M11.99 2C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2zm6.93 6h-2.95c-.32-1.25-.78-2.45-1.38-3.56 1.84.63 3.37 1.91 4.33 3.56zM12 4.04c.83 1.2 1.48 2.53 1.91 3.96h-3.82c.43-1.43 1.08-2.76 1.91-3.96zM4.26 14C4.1 13.36 4 12.69 4 12s.1-1.36.26-2h3.38c-.08.66-.14 1.32-.14 2 0 .68.06 1.34.14 2H4.26zm.82 2h2.95c.32 1.25.78 2.45 1.38 3.56-1.84-.63-3.37-1.9-4.33-3.56zm2.95-8H5.08c.96-1.66 2.49-2.93 4.33-3.56C8.81 5.55 8.35 6.75 8.03 8zM12 19.96c-.83-1.2-1.48-2.53-1.91-3.96h3.82c-.43 1.43-1.08 2.76-1.91 3.96zM14.34 14H9.66c-.09-.66-.16-1.32-.16-2 0-.68.07-1.35.16-2h4.68c.09.65.16 1.32.16 2 0 .68-.07 1.34-.16 2zm.25 5.56c.6-1.11 1.06-2.31 1.38-3.56h2.95c-.96 1.65-2.49 2.93-4.33 3.56zM16.36 14c.08-.66.14-1.32.14-2 0-.68-.06-1.34-.14-2h3.38c.16.64.26 1.31.26 2s-.1 1.36-.26 2h-3.38z")
    val Speed: ImageVector         get() = svgIcon(pathData = "M20.38 8.57l-1.23 1.85a8 8 0 0 1-.22 7.58H5.07A8 8 0 0 1 15.58 6.85l1.85-1.23A10 10 0 0 0 3.35 19a2 2 0 0 0 1.72 1h13.85a2 2 0 0 0 1.74-1 10 10 0 0 0-.27-10.44zm-9.79 6.84a2 2 0 0 0 2.83 0l5.66-8.49-8.49 5.66a2 2 0 0 0 0 2.83z")
    val NetworkCheck: ImageVector  get() = svgIcon(pathData = "M15.9 5.1C14.5 3.7 12.6 3 10.6 3 8.5 3 6.5 3.8 5.1 5.1L3 3v6h6L6.8 6.8c1-1 2.3-1.5 3.8-1.5 1.5 0 2.8.5 3.8 1.5L16 5.1l-.1.0zm-5.3 4.9L7.5 13h3v8l4-7h-3l2.1-3.9 1.9 1.9 1.5-1.5-4.3-4.3L15.9 5l-5.3 5z")
    val BugReport: ImageVector     get() = svgIcon(pathData = "M20 8h-2.81c-.45-.78-1.07-1.45-1.82-1.96L17 4.41 15.59 3l-2.17 2.17C12.96 5.06 12.49 5 12 5c-.49 0-.96.06-1.41.17L8.41 3 7 4.41l1.62 1.63C7.88 6.55 7.26 7.22 6.81 8H4v2h2.09c-.05.33-.09.66-.09 1v1H4v2h2v1c0 .34.04.67.09 1H4v2h2.81c1.04 1.79 2.97 3 5.19 3s4.15-1.21 5.19-3H20v-2h-2.09c.05-.33.09-.66.09-1v-1h2v-2h-2v-1c0-.34-.04-.67-.09-1H20V8zm-6 8h-4v-2h4v2zm0-4h-4v-2h4v2z")
    val VerifiedUser: ImageVector  get() = svgIcon(pathData = "M12 1L3 5v6c0 5.55 3.84 10.74 9 12 5.16-1.26 9-6.45 9-12V5l-9-4zm-2 16l-4-4 1.41-1.41L10 14.17l6.59-6.59L18 9l-8 8z")
    val DeviceUnknown: ImageVector get() = svgIcon(pathData = "M17 1.01L7 1c-1.1 0-2 .9-2 2v18c0 1.1.9 2 2 2h10c1.1 0 2-.9 2-2V3c0-1.1-.9-1.99-2-1.99zM17 19H7V5h10v14zm-5.5-4h1v1h-1zm.93-4.01c.49-.49 1.07-1.5.57-2.77C12.57 7.11 11.54 6.5 10.5 6.5c-1.04 0-2.1.73-2.5 1.72l1.07.45C9.37 8.07 9.9 7.5 10.5 7.5c.55 0 1.1.33 1.27.78.3.8-.21 1.39-.64 1.83-.86.87-1.13 1.38-1.13 2.39h1c0-.68.2-.99.93-1.51z")
    val KeyboardArrowDown: ImageVector get() = svgIcon(pathData = "M7.41 8.59L12 13.17l4.59-4.58L18 10l-6 6-6-6 1.41-1.41z")
    val KeyboardArrowUp: ImageVector   get() = svgIcon(pathData = "M7.41 15.41L12 10.83l4.59 4.58L18 14l-6-6-6 6z")
}

typealias Icons = EIcons

private val DarkScheme = darkColorScheme(
    primary              = Color(0xFF89B4FA), onPrimary            = Color(0xFF1E1E2E),
    primaryContainer     = Color(0xFF313244), onPrimaryContainer   = Color(0xFFCDD6F4),
    secondary            = Color(0xFFCBA6F7), onSecondary          = Color(0xFF1E1E2E),
    secondaryContainer   = Color(0xFF45475A), onSecondaryContainer = Color(0xFFCDD6F4),
    tertiary             = Color(0xFFA6E3A1), onTertiary           = Color(0xFF1E1E2E),
    tertiaryContainer    = Color(0xFF313244), onTertiaryContainer  = Color(0xFFCDD6F4),
    background           = Color(0xFF1E1E2E), onBackground         = Color(0xFFCDD6F4),
    surface              = Color(0xFF181825), onSurface            = Color(0xFFCDD6F4),
    surfaceVariant       = Color(0xFF313244), onSurfaceVariant     = Color(0xFFBAC2DE),
    outline              = Color(0xFF6C7086), outlineVariant       = Color(0xFF45475A),
    error                = Color(0xFFF38BA8), onError              = Color(0xFF1E1E2E),
    errorContainer       = Color(0xFFEBA0AC), onErrorContainer     = Color(0xFF1E1E2E),
    scrim                = Color(0xFF11111B), inverseSurface       = Color(0xFFCDD6F4),
    inverseOnSurface     = Color(0xFF1E1E2E), inversePrimary       = Color(0xFF74C7EC)
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF0061A4), onPrimary = Color.White,
    primaryContainer = Color(0xFFD1E4FF),
    background = Color(0xFFFAFCFF), onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFF8F9FF), onSurface = Color(0xFF1A1C1E),
    error = Color(0xFFBA1A1A), onError = Color.White
)

@Composable
fun GameTheme(dark: Boolean, dynamic: Boolean, content: @Composable () -> Unit) {
    val raw = when {
        dynamic && Build.VERSION.SDK_INT >= 31 -> {
            val c = LocalContext.current
            if (dark) dynamicDarkColorScheme(c) else dynamicLightColorScheme(c)
        }
        dark -> DarkScheme
        else -> LightScheme
    }
    @Composable fun anim(t: Color) = animateColorAsState(t, spring(stiffness = Spring.StiffnessLow), label = "c").value
    val cs = raw.copy(
        primary=anim(raw.primary), onPrimary=anim(raw.onPrimary),
        background=anim(raw.background), onBackground=anim(raw.onBackground),
        surface=anim(raw.surface), onSurface=anim(raw.onSurface),
        error=anim(raw.error), surfaceVariant=anim(raw.surfaceVariant),
        onSurfaceVariant=anim(raw.onSurfaceVariant)
    )
    val view = LocalView.current
    if (!view.isInEditMode) {
        val bg = cs.background.toArgb()
        SideEffect {
            val w = (view.context as Activity).window
            w.statusBarColor = bg; w.navigationBarColor = bg
            WindowCompat.getInsetsController(w, view).apply {
                isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(colorScheme = cs, typography = Typography(
        displayLarge = TextStyle(fontWeight = FontWeight.W300,   fontSize = 57.sp, lineHeight = 64.sp, letterSpacing = (-0.25).sp),
        titleLarge   = TextStyle(fontWeight = FontWeight.Bold,   fontSize = 22.sp, lineHeight = 28.sp),
        titleMedium  = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.15.sp),
        titleSmall   = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
        bodyLarge    = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.5.sp),
        bodyMedium   = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.25.sp),
        bodySmall    = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),
        labelLarge   = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
        labelSmall   = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp, fontFamily = FontFamily.Monospace)
    ), content = content)
}

fun adbColor(s: AdbState.Status): Color = when (s) {
    AdbState.Status.CONNECTED    -> Color(0xFFA6E3A1)
    AdbState.Status.ERROR        -> Color(0xFFF38BA8)
    AdbState.Status.SCANNING, AdbState.Status.CONNECTING, AdbState.Status.RECONNECTING -> Color(0xFFF9E2AF)
    AdbState.Status.PAIRED       -> Color(0xFF89B4FA)
    else                         -> Color(0xFF6C7086)
}

fun adbLabel(s: AdbState.Status) = when (s) {
    AdbState.Status.IDLE         -> "Idle";        AdbState.Status.SCANNING     -> "Scanning"
    AdbState.Status.FOUND        -> "Found";       AdbState.Status.SCAN_PAIRING -> "Searching Port"
    AdbState.Status.PAIRING      -> "Pairing";     AdbState.Status.PAIRED       -> "Paired"
    AdbState.Status.CONNECTING   -> "Connecting";  AdbState.Status.CONNECTED    -> "Connected"
    AdbState.Status.ERROR        -> "Error";       AdbState.Status.RECONNECTING -> "Reconnecting"
}

@Composable
fun GameRoot(ui: UiState, snack: SnackbarHostState, vm: GameViewModel, activity: ComponentActivity) {
    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        bottomBar    = { GameNav(ui.tab, ui.service.running, ui.anticheat, vm::tab) },
        containerColor = MaterialTheme.colorScheme.background
    ) { p ->
        AnimatedContent(
            targetState = ui.tab,
            transitionSpec = { fadeIn(tween(280)) togetherWith fadeOut(tween(180)) },
            modifier = Modifier.padding(p), label = "tabAnim"
        ) { tab ->
            when (tab) {
                AppTab.HOME     -> HomeScreen(ui, vm)
                AppTab.PAIR     -> PairScreen(ui, vm)
                AppTab.SHELL    -> ShellScreen(ui, vm)
                AppTab.PKGS     -> PackagesScreen(ui, vm)
                AppTab.LOGS     -> LogsScreen(ui, vm)
                AppTab.SETTINGS -> SettingsScreen(ui, vm)
                else            -> HomeScreen(ui, vm)
            }
        }
    }
}

@Composable
fun GameNav(sel: AppTab, running: Boolean, ac: AnticheatState, onTab: (AppTab) -> Unit) {
    data class N(val tab: AppTab, val icon: ImageVector, val label: String, val badge: Boolean = false)
    val items = listOf(
        N(AppTab.HOME,     Icons.Home,     sComp("tab_home"), running),
        N(AppTab.PAIR,     Icons.Wifi,     sComp("tab_pair")),
        N(AppTab.SHELL,    Icons.Terminal, sComp("tab_shell")),
        N(AppTab.PKGS,     Icons.Android,  sComp("tab_packages")),
        N(AppTab.LOGS,     Icons.Code,     sComp("tab_logs")),
        N(AppTab.SETTINGS, Icons.Settings, sComp("tab_settings"))
    )
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        items.forEach { n ->
            NavigationBarItem(
                selected = sel == n.tab,
                onClick  = { onTab(n.tab) },
                icon = {
                    val tint = if (sel == n.tab) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    BadgedBox(badge = { if (n.badge) Badge() }) { Icon(n.icon, null, Modifier.size(22.dp), tint = tint) }
                },
                label = { Text(n.label, style = MaterialTheme.typography.labelSmall) },
                alwaysShowLabel = true
            )
        }
    }
}

@Composable
fun PulsingDot(color: Color, size: Int = 10) {
    val inf = rememberInfiniteTransition("dot")
    val alpha by inf.animateFloat(.3f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), "a")
    Box(Modifier.size(size.dp).clip(CircleShape).background(color.copy(alpha)))
}

@Composable
fun StatPill(value: String, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, color = color, fontWeight = FontWeight.ExtraBold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            content()
        }
    }
}

@Composable
fun ShimmerEffect(modifier: Modifier = Modifier) {
    val inf = rememberInfiniteTransition("shimmer")
    val x by inf.animateFloat(-1f, 2f, infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing)), "x")
    Box(modifier.background(Brush.horizontalGradient(listOf(
        MaterialTheme.colorScheme.surfaceVariant,
        MaterialTheme.colorScheme.surfaceVariant.copy(.5f),
        MaterialTheme.colorScheme.surfaceVariant
    ), startX = x * 300f, endX = x * 300f + 300f), RoundedCornerShape(8.dp)))
}

@Composable
fun HomeScreen(ui: UiState, vm: GameViewModel) {
    LaunchedEffect(ui.adb.connected) {
        if (ui.adb.connected) vm.loadSysStats()
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        item { EaquelHeader() }
        item { ServiceCard(ui.service, ui.adb) }
        item { SystemStatsCard(ui.sdk, ui.sysStats) }
    }
}

@Composable
fun PairScreen(ui: UiState, vm: GameViewModel) {
    val adb   = ui.adb
    val ctx   = LocalContext.current
    var pairCode  by remember { mutableStateOf("") }
    var scanning  by remember { mutableStateOf(false) }
    var portFound by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf("") }

    LaunchedEffect(adb.pairingPort) {
        if (adb.pairingPort > 0 && scanning) { portFound = true; scanning = false; statusMsg = "port_found" }
    }
    LaunchedEffect(adb.connected) {
        if (adb.connected) { scanning = false; portFound = false; statusMsg = "" }
    }

    Column(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = adb.connected || scanning || portFound || adb.busy || adb.error.isNotBlank(),
            enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()
        ) {
            val (bannerColor, bannerText) = when {
                adb.connected          -> Color(0xFFA6E3A1) to "✓ ${sComp("status_connected")} — ${adb.host}:${adb.port}"
                adb.error.isNotBlank() -> MaterialTheme.colorScheme.error to "✗ ${adb.error}"
                portFound              -> Color(0xFFF9E2AF) to statusMsg
                scanning               -> Color(0xFF89B4FA) to sComp("msg_pairing_port_scanning")
                adb.busy               -> Color(0xFFCBA6F7) to (adb.pairingStep.ifBlank { sComp("msg_pairing_port_scanning") })
                else                   -> Color.Transparent to ""
            }
            Surface(color = bannerColor.copy(.15f), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (scanning || adb.busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = bannerColor)
                    Text(bannerText, style = MaterialTheme.typography.bodySmall, color = bannerColor, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (adb.connected) TextButton({ vm.disconnect() }) { Text(sComp("btn_disconnect_short"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
                    if (scanning || adb.busy) TextButton({ vm.stopScan(); scanning = false; statusMsg = "" }) { Text(sComp("btn_cancel"), style = MaterialTheme.typography.labelSmall, color = bannerColor) }
                }
            }
        }

        LazyColumn(modifier = Modifier.fillMaxSize().weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!adb.connected) {
                item {
                    Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Wifi, null, Modifier.size(20.dp), tint = Color(0xFFCBA6F7))
                                Text(sComp("pair_wireless_title"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf("1" to sComp("pair_step1"), "2" to sComp("pair_step2"), "3" to sComp("pair_step3"), "4" to sComp("pair_step4")).forEach { (n, step) ->
                                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Surface(shape = CircleShape, color = Color(0xFFCBA6F7).copy(.2f), modifier = Modifier.size(24.dp)) {
                                                Box(contentAlignment = Alignment.Center) { Text(n, style = MaterialTheme.typography.labelSmall, color = Color(0xFFCBA6F7), fontWeight = FontWeight.Bold) }
                                            }
                                            Text(step, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                            OutlinedTextField(
                                value = pairCode,
                                onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() }) pairCode = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(sComp("pair_code")) },
                                placeholder = { Text("123456") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                                leadingIcon = { Icon(Icons.Lock, null) },
                                trailingIcon = { if (pairCode.isNotBlank()) IconButton({ pairCode = "" }) { Icon(Icons.Delete, null) } },
                                isError = pairCode.isNotEmpty() && pairCode.length < 6
                            )
                            if (pairCode.isNotEmpty() && pairCode.length < 6) {
                                Text("${pairCode.length}/6", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                            }
                            Button(
                                onClick = {
                                    if (pairCode.length == 6) { scanning = true; statusMsg = "scanning"; vm.pairWithAutoScan(pairCode) }
                                    else {
                                        vm.showScanningNotification()
                                        for (intent in listOf<android.content.Intent>(
                                            android.content.Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS").apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) },
                                            android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
                                        )) { try { ctx.startActivity(intent); break } catch (_: Exception) {} }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().height(56.dp),
                                enabled = !adb.busy,
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = if (pairCode.length == 6) Color(0xFFA6E3A1) else Color(0xFFCBA6F7))
                            ) {
                                if (adb.busy) {
                                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                                    Spacer(Modifier.width(8.dp))
                                    Text(sComp("pair_connecting"), fontWeight = FontWeight.ExtraBold, color = Color.White)
                                } else {
                                    Icon(Icons.Wifi, null, Modifier.size(20.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(if (pairCode.length == 6) sComp("btn_pair") else sComp("btn_open_dev_settings"), fontWeight = FontWeight.ExtraBold)
                                }
                            }
                        }
                    }
                }
            }

            if (adb.connected) {
                item {
                    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFA6E3A1).copy(.1f)), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.CheckCircle, null, Modifier.size(40.dp), tint = Color(0xFFA6E3A1))
                            Text(sComp("status_connected"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color(0xFFA6E3A1))
                            Text("${adb.host}:${adb.port}", style = MaterialTheme.typography.bodySmall, color = Color(0xFFA6E3A1).copy(.7f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ShellScreen(ui: UiState, vm: GameViewModel) {
    val ctx       = LocalContext.current
    var input     by remember { mutableStateOf("") }
    val clipboard = remember { ctx.getSystemService(android.content.ClipboardManager::class.java) }
    val quickCmds = listOf("getprop ro.product.model","getprop ro.build.version.sdk","dumpsys battery | grep level","pm list packages -3","top -n 1 | head -15","cat /proc/meminfo | head -5","ip addr show wlan0","cls")

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(sComp("tab_shell"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                Text(if (ui.adb.connected) "${sComp("status_connected")} — ${ui.adb.host}:${ui.adb.port}" else sComp("msg_no_connection"), style = MaterialTheme.typography.bodySmall, color = if (ui.adb.connected) Color(0xFFA6E3A1) else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton({ vm.clearLogs() }) { Icon(Icons.Delete, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
            items(quickCmds) { cmd ->
                Surface(shape = RoundedCornerShape(8.dp), color = if (cmd == "cls") MaterialTheme.colorScheme.errorContainer.copy(.5f) else MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.clickable { if (cmd == "cls") vm.clearLogs() else { vm.shell(cmd); input = "" } }) {
                    Text(cmd, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace), color = if (cmd == "cls") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f), singleLine = true, placeholder = { Text(sComp("shell_hint"), fontFamily = FontFamily.Monospace) }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { val cmd = input.trim(); if (cmd.isNotBlank()) { if (cmd == "cls" || cmd == "clear") vm.clearLogs() else vm.shell(cmd); input = "" } }), leadingIcon = { Text("$", Modifier.padding(start = 12.dp), fontFamily = FontFamily.Monospace, color = Color(0xFFA6E3A1), fontWeight = FontWeight.Bold) })
            FilledTonalIconButton(onClick = { val cmd = input.trim(); if (cmd.isNotBlank()) { if (cmd == "cls" || cmd == "clear") vm.clearLogs() else vm.shell(cmd); input = "" } }, enabled = input.isNotBlank()) { Icon(Icons.PlayArrow, null) }
        }
        Spacer(Modifier.height(6.dp))
        val shellLogs = remember(ui.logs) { ui.logs.filter { it.tag == "Shell" } }
        Box(Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(shellLogs, key = { it.ts }) { l ->
                    Text(l.msg, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = when (l.level) {
                        LogEntry.Level.ERROR   -> Color(0xFFF38BA8)
                        LogEntry.Level.SUCCESS -> Color(0xFFA6E3A1)
                        LogEntry.Level.WARN    -> Color(0xFFF9E2AF)
                        else -> MaterialTheme.colorScheme.onSurface
                    })
                }
            }
            if (shellLogs.isNotEmpty()) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).clickable {
                    val text = shellLogs.joinToString("\n") { it.msg }
                    clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("shell", text))
                }) { Icon(Icons.Check, null, Modifier.padding(8.dp).size(16.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) }
            }
        }
    }
}

@Composable
fun PackagesScreen(ui: UiState, vm: GameViewModel) {
    val ctx   = LocalContext.current
    var packages   by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading    by remember { mutableStateOf(false) }
    var query      by remember { mutableStateOf("") }
    var selected   by remember { mutableStateOf<String?>(null) }
    var filter     by remember { mutableStateOf("all") }

    LaunchedEffect(ui.adb.connected) {
        if (ui.adb.connected) {
            loading = true
            val result = vm.shellRaw("pm list packages -3")
            val sys    = vm.shellRaw("pm list packages -s")
            packages = (result + sys).lines().filter { it.startsWith("package:") }.map { it.removePrefix("package:").trim() }.distinct().sorted()
            loading = false
        }
    }

    val shown = packages.filter {
        (query.isBlank() || it.contains(query, ignoreCase = true)) &&
        when (filter) { "user" -> !it.startsWith("com.android") && !it.startsWith("android"); "system" -> it.startsWith("com.android") || it.startsWith("android"); else -> true }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(sComp("tab_packages"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text(sComp("search_packages")) }, singleLine = true, leadingIcon = { Icon(Icons.Search, null) }, trailingIcon = { if (query.isNotBlank()) IconButton({ query = "" }) { Icon(Icons.Delete, null) } })
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("all" to sComp("all"), "user" to sComp("user"), "system" to sComp("tab_system")).forEach { (k, v) ->
                Surface(shape = RoundedCornerShape(8.dp), color = if (filter == k) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.clickable { filter = k }) {
                    Text(v, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall, color = if (filter == k) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = if (filter == k) FontWeight.Bold else FontWeight.Normal)
                }
            }
            Spacer(Modifier.weight(1f))
            Text("${shown.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterVertically))
        }
        Spacer(Modifier.height(8.dp))
        if (loading) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        else if (!ui.adb.connected) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.WifiOff, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(8.dp)); Text(sComp("msg_no_connection"), color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
        else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(shown) { pkg ->
                    val isSelected = selected == pkg
                    Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().clickable { selected = if (isSelected) null else pkg }, colors = CardDefaults.cardColors(containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(.2f) else MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Android, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp))
                                Text(pkg.substringAfterLast("."), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                Icon(if (isSelected) Icons.KeyboardArrowUp else Icons.KeyboardArrowDown, null, Modifier.size(18.dp))
                            }
                            Text(pkg, style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            AnimatedVisibility(isSelected) {
                                Column {
                                    Spacer(Modifier.height(8.dp)); HorizontalDivider(); Spacer(Modifier.height(8.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        FilledTonalButton({ vm.shell("am force-stop $pkg") }, Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)) { Text(sComp("btn_stop"), style = MaterialTheme.typography.labelSmall) }
                                        FilledTonalButton({ vm.shell("pm clear $pkg") }, Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)) { Text(sComp("btn_clear_data"), style = MaterialTheme.typography.labelSmall) }
                                        FilledTonalButton({ vm.grantAllPermissions(pkg) }, Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)) { Text(sComp("grant"), style = MaterialTheme.typography.labelSmall) }
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Button({ vm.shell("pm uninstall $pkg") }, Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)) { Text(sComp("btn_uninstall"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onError) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LogsScreen(ui: UiState, vm: GameViewModel) {
    val fmt = SimpleDateFormat("HH:mm:ss", Locale("tr"))
    var filter by remember { mutableStateOf(LogEntry.Level.VERBOSE) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("${sComp("tab_logs")} (${ui.logs.size})", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
            IconButton(vm::clearLogs) { Icon(Icons.Delete, null) }
        }
        Spacer(Modifier.height(6.dp))
        val logFilters = listOf(
            LogEntry.Level.VERBOSE to sComp("all"),
            LogEntry.Level.INFO    to sComp("log_filter_info"),
            LogEntry.Level.SUCCESS to sComp("log_filter_success"),
            LogEntry.Level.WARN    to sComp("log_filter_warn"),
            LogEntry.Level.ERROR   to sComp("log_filter_error"),
            LogEntry.Level.CRITICAL to sComp("log_filter_critical")
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
            items(logFilters) { (level, label) ->
                val selected = filter == level
                Surface(shape = RoundedCornerShape(8.dp), color = if (selected) levelColor(level).copy(.2f) else MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.clickable { filter = level }) {
                    Text(label, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall, color = if (selected) levelColor(level) else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
        val filtered = if (filter == LogEntry.Level.VERBOSE) ui.logs else ui.logs.filter { it.level >= filter }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(filtered, key = { it.ts }) { l ->
                Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                    Text(fmt.format(Date(l.ts)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(60.dp))
                    if (l.tag.isNotBlank()) Surface(shape = RoundedCornerShape(4.dp), color = Color(0xFF313244)) { Text(l.tag.take(8), Modifier.padding(horizontal = 4.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace), color = Color(0xFF89B4FA), fontSize = 9.sp) }
                    Text(l.msg, style = MaterialTheme.typography.bodySmall, color = levelColor(l.level), maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
fun AdbStatusCard(adb: AdbState, vm: GameViewModel) {
    val ctx = LocalContext.current
    val connected = adb.connected
    Card(
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (connected) Color(0xFFA6E3A1).copy(.08f) else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(if (connected) Color(0xFFA6E3A1).copy(.2f) else MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center
            ) {
                Icon(if (connected) Icons.Wifi else Icons.WifiOff, null, Modifier.size(22.dp),
                    tint = if (connected) Color(0xFFA6E3A1) else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    if (connected) sComp("status_connected") else sComp("msg_no_connection"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (connected) Color(0xFFA6E3A1) else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    if (connected) "${adb.host}:${adb.port}" else sComp("msg_adb_port_not_found"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!connected) {
                FilledTonalButton(onClick = {
                    for (intent in listOf<android.content.Intent>(
                        android.content.Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS").apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) },
                        android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
                    )) { try { ctx.startActivity(intent); break } catch (_: Exception) {} }
                }) { Text(sComp("btn_connect")) }
            } else {
                FilledTonalButton(onClick = { vm.disconnect() },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) { Text(sComp("btn_disconnect_short")) }
            }
        }
    }
}

@Composable
fun SettingsScreen(ui: UiState, vm: GameViewModel) {
    val ctx = LocalContext.current

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Text(sComp("tab_settings"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.5.sp) }

        item {
            AdbSetupCard(ui.adb, vm, ctx)
        }

        item {
            SettingsGroup(sComp("settings_lang")) {
                var showLangPicker by remember { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(sComp("language_label"), style = MaterialTheme.typography.bodyMedium)
                    FilledTonalButton({ showLangPicker = !showLangPicker }) { Text(if (showLangPicker) sComp("btn_close") else sComp("btn_change")) }
                }
                if (showLangPicker) {
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(modifier = Modifier.height(220.dp)) {
                        item {
                            Surface(shape = RoundedCornerShape(8.dp), color = if (ui.language == "system") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).clickable { vm.setLanguage("system"); showLangPicker = false }) {
                                Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("🌐 ${sComp("system_lang")}", style = MaterialTheme.typography.bodySmall, fontWeight = if (ui.language == "system") FontWeight.Bold else FontWeight.Normal)
                                    if (ui.language == "system") { Spacer(Modifier.weight(1f)); Icon(Icons.CheckCircle, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary) }
                                }
                            }
                        }
                        items(LangManager.getLanguagesMap(ctx).entries.toList()) { (code, name) ->
                            Surface(shape = RoundedCornerShape(8.dp), color = if (ui.language == code) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).clickable { vm.setLanguage(code); showLangPicker = false }) {
                                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("$name ($code)", style = MaterialTheme.typography.bodySmall)
                                    if (ui.language == code) Icon(Icons.CheckCircle, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AdbSetupCard(adb: AdbState, vm: GameViewModel, ctx: android.content.Context) {
    val connected = adb.connected
    val busy      = adb.busy

    val cardColor = when {
        connected -> Color(0xFFA6E3A1).copy(.08f)
        adb.errored -> MaterialTheme.colorScheme.errorContainer.copy(.15f)
        busy -> Color(0xFF89B4FA).copy(.08f)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val accentColor = when {
        connected -> Color(0xFFA6E3A1)
        adb.errored -> MaterialTheme.colorScheme.error
        busy -> Color(0xFF89B4FA)
        else -> Color(0xFFCBA6F7)
    }

    Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cardColor)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(accentColor.copy(.18f)), contentAlignment = Alignment.Center) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = accentColor)
                    } else {
                        Icon(if (connected) Icons.Wifi else Icons.WifiOff, null, Modifier.size(20.dp), tint = accentColor)
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            connected -> sComp("status_connected")
                            busy -> adb.pairingStep.ifBlank { sComp("msg_pairing_port_scanning") }
                            adb.errored -> sComp("status_error")
                            else -> sComp("adb_setup_title")
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = accentColor
                    )
                    Text(
                        when {
                            connected -> "${adb.host}:${adb.port}"
                            adb.errored -> adb.error.take(50)
                            else -> sComp("adb_setup_subtitle")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (connected) {
                    PulsingDot(Color(0xFFA6E3A1))
                }
            }

            if (!connected) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        Triple("1", sComp("pair_step1"), false),
                        Triple("2", sComp("pair_step2"), false),
                        Triple("3", sComp("pair_step3"), false),
                        Triple("4", sComp("pair_step4"), false)
                    ).forEach { (num, text, done) ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Surface(shape = CircleShape, color = accentColor.copy(.18f), modifier = Modifier.size(22.dp)) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(num, style = MaterialTheme.typography.labelSmall, color = accentColor, fontWeight = FontWeight.Bold)
                                }
                            }
                            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = {
                            val devIntent = android.content.Intent("android.settings.APPLICATION_DEVELOPMENT_SETTINGS").apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
                            val altIntent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
                            try { ctx.startActivity(devIntent) } catch (_: Exception) { try { ctx.startActivity(altIntent) } catch (_: Exception) {} }
                            vm.showAdbPermissionNotification()
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !busy
                    ) {
                        Icon(Icons.Settings, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(sComp("btn_open_dev_settings"), style = MaterialTheme.typography.labelSmall)
                    }
                    FilledTonalButton(
                        onClick = {
                            val pairIntent = android.content.Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS").apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
                            try { ctx.startActivity(pairIntent) } catch (_: Exception) {}
                            vm.showPairingStepNotification()
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !busy
                    ) {
                        Icon(Icons.Wifi, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(sComp("btn_pair"), style = MaterialTheme.typography.labelSmall)
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = { vm.disconnect(); vm.dismissAdbNotifications() },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    ) {
                        Text(sComp("btn_disconnect_short"), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
fun EaquelHeader() {
    val ctx  = LocalContext.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val inf = rememberInfiniteTransition("hdr")
        val ang by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(8000, easing = LinearEasing), RepeatMode.Restart), "rot")
        Box(
            Modifier.size(52.dp).clip(CircleShape)
                .background(Brush.sweepGradient(listOf(Color(0xFF89B4FA), Color(0xFFCBA6F7), Color(0xFFA6E3A1), Color(0xFF89B4FA))))
                .rotate(ang),
            contentAlignment = Alignment.Center
        ) {
            val ctx = LocalContext.current
            val bmp = remember {
                runCatching {
                    android.graphics.BitmapFactory.decodeStream(ctx.assets.open("Profile.png"))
                }.getOrNull()
            }
            Box(Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
                if (bmp != null) {
                    androidx.compose.foundation.Image(
                        bitmap = androidx.compose.ui.graphics.asImageBitmap(bmp),
                        contentDescription = null,
                        modifier = Modifier.size(44.dp).clip(CircleShape),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                    )
                } else {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(Color(0xFF313244)))
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
        }
        Spacer(Modifier.weight(1f))
        Surface(shape = RoundedCornerShape(8.dp), color = if (sdkOk()) Color(0xFFA6E3A1).copy(.15f) else MaterialTheme.colorScheme.errorContainer) {
            Text(
                "API ${Build.VERSION.SDK_INT}",
                Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = if (sdkOk()) Color(0xFFA6E3A1) else MaterialTheme.colorScheme.onErrorContainer,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun ServiceCard(svc: ServiceState, adb: AdbState) {
    val ctx  = LocalContext.current
    val pulse by animateFloatAsState(if (svc.running) 1.02f else 1f, spring(stiffness = Spring.StiffnessLow), label = "pulse")
    ElevatedCard(Modifier.fillMaxWidth().scale(pulse), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val inf = rememberInfiniteTransition("dot")
                val alpha by inf.animateFloat(.3f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), "a")
                Box(Modifier.size(10.dp).clip(CircleShape).background(
                    if (svc.running) Color(0xFFA6E3A1).copy(alpha) else Color(0xFF6C7086)
                ))
                Spacer(Modifier.width(8.dp))
                Text(if (svc.running) stringResource(R.string.service_running) else stringResource(R.string.service_stopped),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (svc.running) Color(0xFFA6E3A1) else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                if (svc.running) {
                    Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFFA6E3A1).copy(.1f)) {
                        Text(formatUptime(svc.uptime), Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall, color = Color(0xFFA6E3A1))
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val adbColor = adbColor(adb.status)
                Icon(if (adb.connected) Icons.Wifi else Icons.WifiOff,
                    null, tint = adbColor, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (adb.connected) "ADB: ${adb.host}:${adb.port}"
                    else "ADB: ${adbLabel(adb.status)}",
                    style = MaterialTheme.typography.bodySmall, color = adbColor
                )
            }
            if (svc.running) {
                Spacer(Modifier.height(12.dp)); HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant); Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    StatPill("${svc.clients}", stringResource(R.string.label_user_count), Color(0xFF89B4FA))
                    StatPill("${Build.VERSION.SDK_INT}", "SDK", Color(0xFFCBA6F7))
                    StatPill(primaryAbi().split("-").first().uppercase(), "ABI", Color(0xFFA6E3A1))
                    StatPill(if (is64()) "64" else "32", "Bit", Color(0xFFF9E2AF))
                    StatPill(if (adb.connected) sComp("status_connected") else sComp("status_none"), "ADB", if (adb.connected) Color(0xFF89B4FA) else Color(0xFF6C7086))
                }
            }
        }
    }
}

@Composable
fun SystemStatsCard(sdk: Int, stats: GameSysStats?) {
    val ctx  = LocalContext.current
    var batteryLevel by remember { mutableStateOf("–") }
    var batteryStatus by remember { mutableStateOf("–") }
    var batteryTemp  by remember { mutableStateOf("–") }
    var storageFree  by remember { mutableStateOf("–") }

    LaunchedEffect(Unit) {
        try {
            val bm = ctx.getSystemService(android.content.Context.BATTERY_SERVICE) as? android.os.BatteryManager
            val cap = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            batteryLevel = if (cap >= 0) "$cap%" else "–"
            batteryStatus = when (bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_STATUS)) {
                android.os.BatteryManager.BATTERY_STATUS_CHARGING    -> ctx.getString(R.string.battery_charging)
                android.os.BatteryManager.BATTERY_STATUS_DISCHARGING -> ctx.getString(R.string.battery_discharging)
                android.os.BatteryManager.BATTERY_STATUS_FULL        -> ctx.getString(R.string.battery_full)
                android.os.BatteryManager.BATTERY_STATUS_NOT_CHARGING-> ctx.getString(R.string.battery_not_charging)
                else -> "–"
            }
        } catch (_: Exception) {}
        try {
            val stat = android.os.StatFs(android.os.Environment.getDataDirectory().path)
            val free  = stat.availableBlocksLong * stat.blockSizeLong
            val total = stat.blockCountLong      * stat.blockSizeLong
            storageFree = "${GameUtils.bytesToHuman(free)} / ${GameUtils.bytesToHuman(total)}"
        } catch (_: Exception) {}
        try {
            val tempFile = java.io.File("/sys/class/power_supply/battery/temp")
            if (tempFile.exists()) {
                val raw = tempFile.readText().trim().toIntOrNull() ?: 0
                batteryTemp = "${raw / 10.0}°C"
            }
        } catch (_: Exception) {}
    }

    Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Memory, null, modifier = Modifier.size(18.dp), tint = Color(0xFF89B4FA))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.label_system_info), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            val rows = buildList {
                add(stringResource(R.string.info_android_api) to "API $sdk (Android ${sdkToName(sdk)})")
                add(stringResource(R.string.info_device_model) to "${Build.MANUFACTURER} ${Build.MODEL}")
                add(stringResource(R.string.info_device_code) to Build.DEVICE)
                add(stringResource(R.string.info_architecture) to Build.SUPPORTED_ABIS.take(2).joinToString(", "))
                add(stringResource(R.string.info_32bit) to if (has32()) "✓" else "–")
                add(stringResource(R.string.info_64bit) to if (is64()) "✓" else "–")
                add(stringResource(R.string.info_security_patch) to Build.VERSION.SECURITY_PATCH)
                add(stringResource(R.string.info_kernel) to System.getProperty("os.version", "–")!!)
                add(stringResource(R.string.info_build) to Build.DISPLAY)
                add(stringResource(R.string.info_battery_level) to batteryLevel)
                add(stringResource(R.string.info_battery_status) to batteryStatus)
                if (batteryTemp != "–") add(stringResource(R.string.info_battery_temp) to batteryTemp)
                add(stringResource(R.string.info_storage) to storageFree)
                stats?.let {
                    add(stringResource(R.string.info_ram_total) to GameUtils.bytesToHuman(it.totalMem))
                    add(stringResource(R.string.info_ram_free) to GameUtils.bytesToHuman(it.availMem))
                    add(stringResource(R.string.info_selinux) to it.selinux)
                    add("PID" to it.pid.toString())
                    add("UID" to it.uid.toString())
                    add(stringResource(R.string.label_info_cpu) to it.cpuInfo.take(30))
                }
                add(stringResource(R.string.info_package) to PKG)
                add(stringResource(R.string.info_status) to if (sdkOk()) "✓" else "✗")
            }
            rows.forEach { (k, v) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(k, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(0.45f))
                    Text(v, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(0.55f),
                        textAlign = TextAlign.End)
                }
            }
        }
    }
}
