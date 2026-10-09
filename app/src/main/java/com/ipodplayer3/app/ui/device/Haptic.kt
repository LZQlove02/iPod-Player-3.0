package com.ipodplayer3.app.ui.device

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import java.lang.ref.WeakReference

/**
 * Click-wheel / settings haptics.
 *
 * Fires **every** known path — OEM ROMs (MIUI / ColorOS / …) randomly no-op
 * one of them:
 * 1. [View.performHapticFeedback] with FLAG_IGNORE_GLOBAL_SETTING
 * 2. [Vibrator] one-shot full amplitude
 * 3. [VibrationEffect.createPredefined]
 * 4. [Vibrator.vibrate] waveform
 *
 * A [View] is remembered when the wheel binds so settings toggles can also
 * use path 1 (those used to call confirm() with no view).
 */
object Haptic {
    @Volatile
    private var enabled = true
    private var appContext: Context? = null
    private var viewRef: WeakReference<View>? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** Called from ClickWheel so settings confirm() can use the same view. */
    fun attach(view: View) {
        viewRef = WeakReference(view)
    }

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    /** 缓存解析结果（含「没有振动器」这个结果），别每次 tick 都 getSystemService。 */
    @Volatile
    private var cachedVibrator: Vibrator? = null

    @Volatile
    private var vibratorResolved = false

    fun tick(view: View? = null) = pulse(40L, view, strong = false, force = false)

    /** Settings ON confirmation — always fires, even if the gate just flipped. */
    fun confirm() = pulse(120L, null, strong = true, force = true)

    private fun vibratorOrNull(): Vibrator? {
        cachedVibrator?.let { return it }
        if (vibratorResolved) return null
        val app = appContext ?: return null
        val managed = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                    ?.defaultVibrator
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
        val resolved = managed ?: try {
            @Suppress("DEPRECATION")
            app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } catch (_: Exception) {
            null
        }
        cachedVibrator = resolved
        vibratorResolved = true
        return resolved
    }

    private fun pulse(ms: Long, view: View?, strong: Boolean, force: Boolean) {
        if (!enabled && !force) return

        // 路径 1：framework 触感（系统「触感反馈」关掉时仍然有效）—— 只打一条。
        val v0 = view ?: viewRef?.get()
        if (v0 != null) {
            val type = if (strong) {
                HapticFeedbackConstants.CONTEXT_CLICK
            } else {
                HapticFeedbackConstants.CLOCK_TICK
            }
            try {
                @Suppress("DEPRECATION")
                v0.performHapticFeedback(type, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
            } catch (_: Exception) {
            }
        }

        // 路径 2~4：马达。**失败才降级**。
        // 以前是四条路径全打一遍（弱档一次 tick = 2 次 performHapticFeedback + 3 次 vibrate），
        // 正常机型上全都成功 → 震动叠加变闷变长，转轮一秒十格就是 50 次/秒的 binder 调用。
        val v = vibratorOrNull() ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val kind = if (strong) VibrationEffect.EFFECT_CLICK else VibrationEffect.EFFECT_TICK
            if (runCatching { v.vibrate(VibrationEffect.createPredefined(kind)) }.isSuccess) return
        }
        // minSdk 26 = O，createOneShot 恒可用：别再写恒真的版本判断（ObsoleteSdkInt）。
        if (runCatching {
                // DEFAULT_AMPLITUDE 而不是写死 255：别绕过系统的「振动强度」设置。
                v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            }.isSuccess
        ) {
            return
        }
        runCatching {
            @Suppress("DEPRECATION")
            v.vibrate(ms)
        }
    }
}
