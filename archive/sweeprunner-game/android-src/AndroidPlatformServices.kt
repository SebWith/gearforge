package com.sweeprunner.game

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

class AndroidPlatformServices(context: Context) : PlatformServices {
    override val haptics: Haptics = AndroidHaptics(context)
    override val leaderboard: LeaderboardService = LocalLeaderboard()
}

class AndroidHaptics(context: Context) : Haptics {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        val mgr = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        mgr.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    override fun vibrate(ms: Long) {
        if (Build.VERSION.SDK_INT >= 26) {
            vibrator?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(ms)
        }
    }

    override fun heavy() = vibrate(45)
    override fun light() = vibrate(15)
}
