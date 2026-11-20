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

    private val pool = SoundPool.Builder().setMaxStreams(6).setAudioAttributes(
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
    private val takes = HashMap<Event, MutableList<Int>>()
    private val last = HashMap<Event, Int>()

    init {
        val files = context.assets.list("sounds")?.sorted() ?: emptyList()
        for (e in Event.entries) {
            // "shutter-30.m4a" is a take of SHUTTER; "shutter-0.125.m4a" (slow speeds) and
            // "shutter-change" are not.
            val mine = files.filter { f -> e.names.any { n -> Regex("^${Regex.escape(n)}-\\d+\\.m4a$").matches(f) || f == "$n.m4a" } }
            takes[e] = mine.map { f -> context.assets.openFd("sounds/$f").use { pool.load(it, 1) } }.toMutableList()
        }
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
