package com.personal.fittrack.ui

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator

@Suppress("DEPRECATION")
fun vibrate(ctx: Context) {
    try {
        val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 250, 120, 250), -1))
    } catch (_: Exception) { }
}

fun mmss(sec: Int): String = "%d:%02d".format(sec / 60, sec % 60)
