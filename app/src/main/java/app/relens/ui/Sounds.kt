package app.relens.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import kotlin.random.Random

/**
 * The camera's own sounds: Vintage Click's recordings of a real Nikon
 * (assets/sounds, converted to mono AAC). As in the game's Sounds.swift, each
 * event plays a random take, never the same one twice running, a touch faster
 * or slower and louder or softer, so a burst of frames never sounds like a loop.
 */
class Sounds(context: Context) {
    enum class Event(vararg val names: String) {
        SHUTTER("shutter"),
        ADVANCE("film-advance", "advance"),
        APERTURE("iris-click", "iris", "aperture"),
        LENS("lens-change"),
        FILM("film-change"),
        DIAL("shutter-change"),
    }

    // Played as game audio (the media volume, which the volume keys turn): as "sonification"
    // they followed a quiet system channel and were barely audible at full volume.
    private val pool = SoundPool.Builder().setMaxStreams(6).setAudioAttributes(
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
    private val takes = HashMap<Event, MutableList<Int>>()
    private val last = HashMap<Event, Int>()

    /**
     * The whole shutter recorded at each speed, as Vintage Click has it: "shutter-125" is 1/125,
     * "shutter-1" a second, "shutter-0.5" half a second (Sounds.swift `speed`).
     */
    private val shutterAt = HashMap<Float, MutableList<Int>>()
    private var lastShutter: Int? = null

    private fun speedOf(token: String): Float? {
        val d = token.toFloatOrNull()?.takeIf { it > 0f } ?: return null
        return if ("." in token || d <= 1f) d else 1f / d
    }

    init {
        val files = context.assets.list("sounds")?.sorted() ?: emptyList()
        for (f in files) {
            val m = Regex("^shutter-([0-9.]+)(?:-\\d+)?\\.m4a$").matchEntire(f) ?: continue
            val sp = speedOf(m.groupValues[1]) ?: continue
            shutterAt.getOrPut(sp) { mutableListOf() } += context.assets.openFd("sounds/$f").use { pool.load(it, 1) }
        }
        app.relens.Trace.i("shutter recordings at " + shutterAt.keys.sorted().joinToString { if (it >= 1f) "%.0fs".format(it) else if (it >= 0.1f) "%.3gs".format(it) else "1/%.0f".format(1f / it) })
        for (e in Event.entries) {
            // "shutter-30.m4a" is a take of SHUTTER; "shutter-0.125.m4a" (slow speeds) and
            // "shutter-change" are not.
            val mine = files.filter { f -> e.names.any { n -> Regex("^${Regex.escape(n)}-\\d+\\.m4a$").matches(f) || f == "$n.m4a" } &&
                !(e == Event.SHUTTER) }                     // the shutter's takes are chosen by speed (fire)
            takes[e] = mine.map { f -> context.assets.openFd("sounds/$f").use { pool.load(it, 1) } }.toMutableList()
        }
        app.relens.Trace.i("sound takes: " + Event.entries.joinToString { "${it.name.lowercase()} x${takes[it]?.size ?: 0}" })
    }

    /**
     * The shutter at [seconds]: the recording made nearest that speed (by stops), a take chosen
     * at random, played at its own rate (its length is the speed); the plain take if none.
     */
    fun fire(seconds: Float) {
        val sp = shutterAt.keys.minByOrNull { kotlin.math.abs(kotlin.math.ln(it / seconds)) }
        val list = sp?.let { shutterAt[it] }
        if (list.isNullOrEmpty()) { play(Event.SHUTTER); return }
        var id = list.random()
        if (list.size > 1 && id == lastShutter) id = list[(list.indexOf(id) + 1) % list.size]
        lastShutter = id
        val v = 0.9f + 0.1f * Random.nextFloat()
        pool.play(id, v, v, 1, 0, 1f)
    }

    fun play(e: Event, volume: Float = 1f) {
        val list = takes[e] ?: return
        if (list.isEmpty()) return
        var i = Random.nextInt(list.size)
        if (list.size > 1 && i == last[e]) i = (i + 1) % list.size
        last[e] = i
        val v = volume * (0.85f + 0.15f * Random.nextFloat())
        val rate = 0.96f + 0.08f * Random.nextFloat()
        pool.play(list[i], v, v, 1, 0, rate)
    }
}
