package app.relens.ui

import android.content.Context
import android.media.MediaActionSound
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * The body's feel: a light tick on each detent of a ring, a firmer click on a
 * stop, a thud for the shutter (Haptics.swift's analogue, on Android's
 * composition primitives where the phone has them), and the shutter's sound.
 */
class Haptics(context: Context) {
    private val vib: Vibrator = if (Build.VERSION.SDK_INT >= 31)
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    else @Suppress("DEPRECATION") context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    private val sound = MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) }

    private fun primitive(p: Int, scale: Float, fallback: Int) {
        if (vib.areAllPrimitivesSupported(p))
            vib.vibrate(VibrationEffect.startComposition().addPrimitive(p, scale).compose())
        else vib.vibrate(VibrationEffect.createPredefined(fallback))
    }

    /** A detent passing under the finger (focus scale marks). */
    fun tick() = primitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.5f, VibrationEffect.EFFECT_TICK)
    /** A click stop (aperture). */
    fun click() = primitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.7f, VibrationEffect.EFFECT_CLICK)
    /** Mounting a lens, loading a roll. */
    fun clunk() = primitive(if (Build.VERSION.SDK_INT >= 31) VibrationEffect.Composition.PRIMITIVE_THUD
        else VibrationEffect.Composition.PRIMITIVE_CLICK, 1f, VibrationEffect.EFFECT_HEAVY_CLICK)

    fun shutter() {
        sound.play(MediaActionSound.SHUTTER_CLICK)
        if (vib.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK)) {
            vib.vibrate(VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f)          // mirror up
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.8f, 60)    // shutter
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.6f, 40)   // mirror down
                .compose())
        } else vib.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
    }
}
