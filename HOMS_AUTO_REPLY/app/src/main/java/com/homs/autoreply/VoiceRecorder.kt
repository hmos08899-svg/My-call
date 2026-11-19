package com.homs.autoreply

import android.content.Context
import android.media.MediaRecorder
import java.io.File

class VoiceRecorder(private val ctx: Context) {
    val tempFile: File get() = File(ctx.filesDir, "temp.m4a")
    val savedFile: File get() = AppState.greetingFile(ctx)
    private var recorder: MediaRecorder? = null

    fun start(): Boolean {
        stop()
        val r = MediaRecorder(ctx)
        recorder = r
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(128_000)
            r.setAudioSamplingRate(44_100)
            r.setOutputFile(tempFile.absolutePath)
            r.prepare()
            r.start()
            true
        } catch (e: Exception) {
            runCatching { r.release() }
            recorder = null
            false
        }
    }

    /** يعيد true إذا حُفظ تسجيل صالح في الملف المؤقت. */
    fun stop(): Boolean {
        val r = recorder ?: return false
        recorder = null
        return try {
            r.stop()
            true
        } catch (e: Exception) {
            tempFile.delete()
            false
        } finally {
            runCatching { r.release() }
        }
    }

    fun save(): Boolean = try {
        tempFile.copyTo(savedFile, overwrite = true)
        tempFile.delete()
        true
    } catch (e: Exception) {
        false
    }
}
