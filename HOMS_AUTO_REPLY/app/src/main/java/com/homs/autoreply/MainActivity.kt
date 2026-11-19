package com.homs.autoreply

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Bg = Color(0xFF0E1116)
private val Surface1 = Color(0xFF171C24)
private val Green = Color(0xFF2ECC71)
private val Red = Color(0xFFE5534B)
private val Amber = Color(0xFFF1C40F)
private val Muted = Color(0xFF9AA4B2)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppState.init(this)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Green, onPrimary = Bg, background = Bg, surface = Surface1,
                    onSurface = Color.White, onBackground = Color.White
                )
            ) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    HomeScreen()
                }
            }
        }
    }
}

private fun requiredPerms(): Array<String> = buildList {
    add(Manifest.permission.RECORD_AUDIO)
    add(Manifest.permission.READ_PHONE_STATE)
    add(Manifest.permission.ANSWER_PHONE_CALLS)
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

private data class SimInfo(val subId: Int, val label: String)

private fun loadSims(ctx: android.content.Context): List<SimInfo> {
    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE)
        != PackageManager.PERMISSION_GRANTED
    ) return emptyList()
    return try {
        val sm = ctx.getSystemService(SubscriptionManager::class.java)
        sm.activeSubscriptionInfoList.orEmpty().map {
            SimInfo(it.subscriptionId, "SIM ${it.simSlotIndex + 1} – ${it.displayName}")
        }
    } catch (e: SecurityException) {
        emptyList()
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Surface1).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Green)
        content()
    }
}

@Composable
fun HomeScreen() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }

    val lifecycle = (ctx as ComponentActivity).lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    // إذا كانت الخدمة مفعّلة سابقًا ولم تعد تعمل (قتلتها العملية) أعد تشغيلها
    val running by AppState.serviceRunning.collectAsState()
    val status by AppState.status.collectAsState()
    val log by AppState.log.collectAsState()
    LaunchedEffect(Unit) {
        if (AppState.isEnabled() && !AppState.serviceRunning.value) ServiceController.start(ctx)
    }

    val perms = remember { requiredPerms() }
    val missing = remember(tick) {
        perms.filter {
            ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED
        }
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { tick++ }
    var showRationale by remember { mutableStateOf(false) }

    val recorder = remember { VoiceRecorder(ctx) }
    var recording by remember { mutableStateOf(false) }
    var previewing by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<MediaPlayer?>(null) }
    var info by remember { mutableStateOf("") }
    val hasTemp = remember(tick) { recorder.tempFile.exists() }
    val hasSaved = remember(tick) { recorder.savedFile.exists() }

    fun stopPreview() {
        preview?.let { runCatching { it.release() } }
        preview = null
        previewing = false
    }

    DisposableEffect(Unit) {
        onDispose {
            stopPreview()
            recorder.stop()
        }
    }

    val sims = remember(tick) { loadSims(ctx) }
    var selSim by remember(tick) { mutableIntStateOf(AppState.simSubId()) }

    if (showRationale) {
        AlertDialog(
            onDismissRequest = { showRationale = false },
            title = { Text("الأذونات المطلوبة") },
            text = {
                Text(
                    "يحتاج التطبيق إلى الأذونات التالية فقط:\n\n" +
                        "• الميكروفون: لتسجيل رسالتك الصوتية.\n" +
                        "• حالة الهاتف: لاكتشاف المكالمة الواردة وقراءة الشرائح.\n" +
                        "• الرد على المكالمات: للرد على المكالمة وإنهائها تلقائيًا.\n" +
                        "• الإشعارات: لإظهار إشعار الخدمة أثناء عملها في الخلفية.\n\n" +
                        "لا يطلب التطبيق أي إذن آخر، ولا يحتاج أن يكون تطبيق الهاتف الافتراضي."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRationale = false
                    launcher.launch(missing.toTypedArray())
                }) { Text("متابعة") }
            },
            dismissButton = { TextButton(onClick = { showRationale = false }) { Text("إلغاء") } }
        )
    }

    Column(
        Modifier.fillMaxSize().background(Bg).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // العنوان + الحالة
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("HOMS AUTO REPLY", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Box(
                Modifier.clip(RoundedCornerShape(50)).background(if (running) Green else Red)
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) { Text(if (running) "نشط" else "متوقف", color = Bg, fontWeight = FontWeight.Bold) }
        }

        // التنبيه التقني (صريح)
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0xFF2A2410)).padding(14.dp)
        ) {
            Text("تنبيه تقني", color = Amber, fontWeight = FontWeight.Bold)
            Text(
                "لا يسمح Android لتطبيق عادي بإدخال ملف صوتي في مسار المكالمة نفسه. " +
                    "يشغّل التطبيق التسجيل عبر مكبر الهاتف بأعلى مستوى ليلتقطه ميكروفون الجهاز " +
                    "ويسمعه المتصل. جودة الوصول تختلف من جهاز لآخر وتتأثر بالضوضاء. " +
                    "تحقق منها بمكالمة حقيقية.",
                color = Color.White, fontSize = 13.sp
            )
        }

        // التفعيل
        Button(
            onClick = {
                if (running) {
                    ServiceController.stop(ctx)
                } else when {
                    missing.isNotEmpty() -> showRationale = true
                    !hasSaved -> Toast.makeText(ctx, "سجّل رسالة واحفظها أولًا", Toast.LENGTH_LONG).show()
                    else -> ServiceController.start(ctx)
                }
            },
            modifier = Modifier.fillMaxWidth().height(72.dp),
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (running) Red else Green, contentColor = Bg
            )
        ) { Text(if (running) "إيقاف الرد التلقائي" else "تفعيل الرد التلقائي", fontSize = 20.sp, fontWeight = FontWeight.Bold) }

        if (missing.isNotEmpty()) {
            Section("الأذونات") {
                Text("أذونات ناقصة: ${missing.size}", color = Muted)
                Button(onClick = { showRationale = true }, modifier = Modifier.fillMaxWidth()) { Text("منح الأذونات") }
                OutlinedButton(
                    onClick = {
                        ctx.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("فتح إعدادات التطبيق") }
            }
        }

        // التسجيل
        Section("الرسالة الصوتية") {
            Text(
                when {
                    recording -> "جارٍ التسجيل… تحدّث الآن"
                    hasTemp -> "تسجيل جديد غير محفوظ"
                    hasSaved -> "يوجد تسجيل محفوظ"
                    else -> "لا يوجد تسجيل"
                },
                color = if (recording) Red else Muted
            )
            Button(
                onClick = {
                    if (recording) {
                        val ok = recorder.stop()
                        recording = false
                        info = if (ok) "تم التسجيل. استمع إليه ثم احفظه." else "فشل التسجيل (قد يكون قصيرًا جدًا)"
                        tick++
                    } else {
                        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO)
                            != PackageManager.PERMISSION_GRANTED
                        ) {
                            showRationale = true
                        } else {
                            stopPreview()
                            if (recorder.start()) { recording = true; info = "" }
                            else info = "تعذر بدء التسجيل"
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = if (recording) Red else Green, contentColor = Bg)
            ) {
                Text(if (recording) "إيقاف التسجيل" else if (hasTemp || hasSaved) "إعادة التسجيل" else "تسجيل")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = !recording && (hasTemp || hasSaved),
                    onClick = {
                        if (previewing) {
                            stopPreview()
                        } else {
                            val f = if (hasTemp) recorder.tempFile else recorder.savedFile
                            try {
                                val mp = MediaPlayer()
                                mp.setAudioAttributes(
                                    AudioAttributes.Builder()
                                        .setUsage(AudioAttributes.USAGE_MEDIA)
                                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                                )
                                mp.setDataSource(f.absolutePath)
                                mp.setOnCompletionListener { stopPreview() }
                                mp.prepare()
                                mp.start()
                                preview = mp
                                previewing = true
                            } catch (e: Exception) {
                                info = "تعذر تشغيل التسجيل"
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text(if (previewing) "إيقاف الاستماع" else "استماع") }
                OutlinedButton(
                    enabled = !recording && hasTemp,
                    onClick = {
                        stopPreview()
                        info = if (recorder.save()) "تم حفظ التسجيل" else "فشل الحفظ"
                        tick++
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("حفظ") }
            }
            if (info.isNotEmpty()) Text(info, color = Muted, fontSize = 13.sp)
        }

        // الشريحة
        Section("شريحة الاتصال") {
            if (sims.isEmpty()) {
                Text("لا يمكن قراءة الشرائح (امنح إذن حالة الهاتف). سيعمل التطبيق على أي شريحة.", color = Muted, fontSize = 13.sp)
            } else {
                val options = listOf(SimInfo(-1, "كل الشرائح")) + sims
                options.forEach { s ->
                    Row(
                        Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = selSim == s.subId, onClick = {
                            selSim = s.subId
                            AppState.setSim(s.subId)
                            if (running) ServiceController.restart(ctx)
                        })
                        Text(s.label)
                    }
                }
            }
        }

        // الحالة الحالية
        Section("حالة المكالمة الحالية") { Text(status) }

        // البطارية
        OutlinedButton(
            onClick = { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("إعدادات البطارية (للسماح بالعمل في الخلفية)") }

        // السجل
        Section("سجل المكالمات") {
            if (log.isEmpty()) {
                Text("لا توجد مكالمات تعامل معها التطبيق بعد", color = Muted)
            } else {
                val fmt = remember { SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault()) }
                log.take(50).forEach {
                    Column {
                        Text(fmt.format(Date(it.time)), color = Muted, fontSize = 12.sp)
                        Text(it.result, fontSize = 14.sp)
                    }
                }
                TextButton(onClick = { AppState.clearLog() }) { Text("مسح السجل") }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}
