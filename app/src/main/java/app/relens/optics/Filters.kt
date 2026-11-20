package app.relens.optics

import kotlin.math.log2

/**
 * A filter in front of the lens, as in Vintage Click's Filters.swift: a
 * colour (transmission), and effects: a star / cross screen or diffraction
 * grating, a glow round highlights, an overall soften, a veil of scattered
 * light. Makers are made up.
 */
data class LensFilter(
    val name: String,
    val about: String,
    val group: String,
    val transmission: FloatArray = floatArrayOf(1f, 1f, 1f),
    val starPoints: Int = 0,          // 0: none; 2: a single line; 4, 6, 8: a star
    val starStrength: Float = 0f,
    val spectral: Boolean = false,    // a diffraction grating: the spikes split into rainbows
    val glow: Float = 0f,
    val soften: Float = 0f,
    val veil: Float = 0f,
) {
    /** Luminance of the transmission: what a TTL meter makes up for. */
    val transmittance: Float get() = 0.2126f * transmission[0] + 0.7152f * transmission[1] + 0.0722f * transmission[2]
    /** The light it loses, in stops. */
    val stops: Float get() = -log2(maxOf(transmittance, 1e-4f))
    val hasEffect: Boolean get() = starPoints > 0 || glow > 0 || soften > 0 || veil > 0
    val isNone: Boolean get() = group == "none"
}

private fun t(r: Float, g: Float, b: Float) = floatArrayOf(r, g, b)
private fun grey(v: Float) = floatArrayOf(v, v, v)

/** Whether [lens] takes [filter]: a rear-filter lens (fisheye turret, mirror lens drop-in) only colour and ND. */
fun Lens.fits(filter: LensFilter): Boolean = !(rearFilter && filter.hasEffect)

val lensFilters: List<LensFilter> = listOf(
    LensFilter("No filter", "just the lens", "none"),
    // Neutral density: faster film, or a wider aperture, in bright light.
    LensFilter("Hoshiya ND2", "neutral density, 1 stop: open up a stop in bright light", "neutral density", grey(0.5f)),
    LensFilter("Hoshiya ND4", "neutral density, 2 stops", "neutral density", grey(0.25f)),
    LensFilter("Hoshiya ND8", "neutral density, 3 stops: fast film wide open in daylight", "neutral density", grey(0.125f)),
    LensFilter("Hoshiya ND64", "neutral density, 6 stops: long exposures by day", "neutral density", grey(1f / 64)),
    // Contrast filters for black-and-white film.
    LensFilter("Hoshiya Y2 Yellow", "b&w: the everyday filter; skies a touch deeper, clouds stand out", "black & white", t(0.98f, 0.90f, 0.30f)),
    LensFilter("Hoshiya G Orange", "b&w: dark skies, smooth skin, haze cut through", "black & white", t(0.95f, 0.42f, 0.04f)),
    LensFilter("Hoshiya R1 Red", "b&w: dramatic black skies, foliage darkens", "black & white", t(0.90f, 0.07f, 0.02f)),
    LensFilter("Rochester Wrattle No. 29 Deep Red", "b&w: near night-for-day; skies all but black", "black & white", t(0.80f, 0.015f, 0.01f)),
    LensFilter("Hoshiya X1 Green", "b&w: foliage lightens, skin deepens, skies a little darker", "black & white", t(0.30f, 0.78f, 0.25f)),
    LensFilter("Rochester Wrattle No. 47 Blue", "b&w: haze and fog deepen, reds go dark", "black & white", t(0.04f, 0.14f, 0.86f)),
    // Colour correction and warming for colour film.
    LensFilter("Hoshiya Skylight 1A", "colour: takes the blue off shade and sky, a faint warmth", "colour correction", t(1.0f, 0.97f, 0.97f)),
    LensFilter("Tiffield 81A Warming", "colour: a gentle warm-up, for overcast and shade", "colour correction", t(1.0f, 0.92f, 0.80f)),
    LensFilter("Tiffield 85B Amber", "colour: tungsten film in daylight (or a golden look)", "colour correction", t(0.95f, 0.66f, 0.36f)),
    LensFilter("Tiffield 80A Blue", "colour: daylight film under tungsten lights", "colour correction", t(0.36f, 0.56f, 0.98f)),
    // Effects.
    LensFilter("Hoshiya Cross Screen 4X", "a cross screen: four spikes off every bright point", "effects", starPoints = 4, starStrength = 1f),
    LensFilter("Hoshiya Cross Screen 6X", "six spikes off every bright point", "effects", starPoints = 6, starStrength = 0.85f),
    LensFilter("Hoshiya Cross Screen 8X", "eight spikes: very 70s", "effects", starPoints = 8, starStrength = 0.75f),
    LensFilter("Coquin Diffractor 040", "bright points throw rainbow streaks to either side", "effects", starPoints = 2, starStrength = 1.2f, spectral = true),
    LensFilter("Coquin Spectral Cross 041", "rainbow streaks in four directions", "effects", starPoints = 4, starStrength = 1f, spectral = true),
    LensFilter("Coquin Spectral Star 042", "an eight-ray rainbow starburst", "effects", starPoints = 8, starStrength = 0.8f, spectral = true),
    LensFilter("Tiffield Soft/Glow 1", "a gentle glow round highlights, still sharp beneath", "effects", glow = 0.35f, soften = 0.12f),
    LensFilter("Tiffield Soft/Glow 3", "a dreamy glow: highlights bloom, edges melt", "effects", glow = 0.9f, soften = 0.35f),
    LensFilter("Tiffield Pro-Haze 2", "a pale veil over everything, low contrast and soft light", "effects", glow = 0.3f, soften = 0.15f, veil = 0.35f),
    LensFilter("Tiffield Black Haze 1", "halos on the lights, shadows kept deep: night city film", "effects", glow = 0.6f, soften = 0.06f),
    LensFilter("Tiffield Fog 2", "a heavy fog: the scene recedes into light", "effects", glow = 0.4f, soften = 0.25f, veil = 0.8f),
)
