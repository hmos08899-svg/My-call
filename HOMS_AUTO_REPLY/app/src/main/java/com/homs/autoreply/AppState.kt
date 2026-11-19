package com.homs.autoreply

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class LogEntry(val time: Long, val result: String)

object LogCodec {
    fun encode(list: List<LogEntry>): String {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("t", it.time).put("r", it.result)) }
        return arr.toString()
    }

    fun decode(s: String?): List<LogEntry> {
        if (s.isNullOrEmpty()) return emptyList()
        return try {
            val arr = JSONArray(s)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                LogEntry(o.getLong("t"), o.getString("r"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}

/** حالة التطبيق المشتركة بين الواجهة والخدمة (نفس العملية). */
object AppState {
    private var prefs: SharedPreferences? = null

    val log = MutableStateFlow<List<LogEntry>>(emptyList())
    val status = MutableStateFlow("لا توجد مكالمة حاليًا")
    val serviceRunning = MutableStateFlow(false)

    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.applicationContext.getSharedPreferences("homs", Context.MODE_PRIVATE)
        prefs = p
        log.value = LogCodec.decode(p.getString("log", null))
    }

    fun greetingFile(ctx: Context) = File(ctx.filesDir, "greeting.m4a")

    fun isEnabled() = prefs?.getBoolean("enabled", false) ?: false
    fun setEnabled(v: Boolean) { prefs?.edit()?.putBoolean("enabled", v)?.apply() }

    /** -1 = الشريحة الافتراضية (أي مكالمة) */
    fun simSubId() = prefs?.getInt("sim", -1) ?: -1
    fun setSim(id: Int) { prefs?.edit()?.putInt("sim", id)?.apply() }

    fun addLog(result: String) {
        val l = (listOf(LogEntry(System.currentTimeMillis(), result)) + log.value).take(100)
        log.value = l
        prefs?.edit()?.putString("log", LogCodec.encode(l))?.apply()
    }

    fun clearLog() {
        log.value = emptyList()
        prefs?.edit()?.remove("log")?.apply()
    }
}
