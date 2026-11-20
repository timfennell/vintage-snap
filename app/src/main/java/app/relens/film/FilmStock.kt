package app.relens.film

import kotlin.math.sqrt

/**
 * A film stock as a development look, ported from Vintage Click's FilmStock.swift:
 * how it sees colour (crosstalk and balance), its characteristic curve
 * (contrast, toe, shoulder), grain and halation. Applied to scene-linear exposure.
 * Names are made up; [inspiredBy] says what each one plays.
 */
data class FilmStock(
    val name: String,
    val inspiredBy: String,
    val iso: Float,
    val exposures: Int,
    val blackAndWhite: Boolean = false,
    val bwMix: FloatArray = floatArrayOf(0.30f, 0.59f, 0.11f),
    /** Rows: how much of scene R, G, B each dye layer sees. */
    val crosstalk: Array<FloatArray> = IDENTITY,
    val balance: FloatArray = floatArrayOf(1f, 1f, 1f),
    val saturation: Float = 1f,
    val contrast: Float = 1f,
    val toe: Float = 1f,
    val shoulder: Float = 1f,
    val blackLift: Float = 0.02f,
    val grain: Float = 0.3f,
    val grainSize: Float = 1.2f,
    val halation: Float = 0f,
    val halationTint: FloatArray = floatArrayOf(1.0f, 0.25f, 0.05f),
    /** Colour infrared: layers see infrared, red, green; printed red, green, blue. */
    val falseColourIR: Boolean = false,
) {
    /** The stock as the lab develops it after rating it [p] stops faster (+) or slower (-). */
    fun developed(p: Int): FilmStock {
        if (p == 0) return this
        val x = p.toFloat()
        return if (p > 0) copy(
            contrast = contrast * (1 + 0.12f * x),
            toe = toe * (1 + 0.06f * x),
            shoulder = shoulder * (1 + 0.08f * x),
            grain = grain * (1 + 0.3f * x),
            blackLift = blackLift + 0.006f * x,
            saturation = saturation * (1 + 0.04f * x),
        ) else copy(
            contrast = contrast * (1 + 0.10f * x),
            shoulder = shoulder * (1 + 0.08f * x),
            grain = grain * (1 + 0.15f * x),
        )
    }

    /** Packed for the shaders as 8 vec4s, the layout of Shaders.swift's `F[]`. */
    fun packed(): FloatArray = floatArrayOf(
        crosstalk[0][0], crosstalk[0][1], crosstalk[0][2], 0f,
        crosstalk[1][0], crosstalk[1][1], crosstalk[1][2], 0f,
        crosstalk[2][0], crosstalk[2][1], crosstalk[2][2], 0f,
        balance[0], balance[1], balance[2], if (blackAndWhite) 1f else 0f,
        bwMix[0], bwMix[1], bwMix[2], saturation,
        contrast, blackLift, shoulder, grain * sqrt(iso / 100f),
        halation, halationTint[0], halationTint[1], halationTint[2],
        iso, grainSize, toe, if (falseColourIR) 1f else 0f,
    )

    companion object {
        val IDENTITY = arrayOf(floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, 0f, 1f))
    }
}

private fun mix(r: FloatArray, g: FloatArray, b: FloatArray) = arrayOf(r, g, b)
private fun v(a: Float, b: Float, c: Float) = floatArrayOf(a, b, c)

val filmStocks: List<FilmStock> = listOf(
    FilmStock("Rochester K-Chrome 64", "the classic K-14 slide film", 64f, 36,
        crosstalk = mix(v(1.08f, -0.05f, -0.03f), v(-0.04f, 1.06f, -0.02f), v(-0.02f, -0.08f, 1.10f)),
        balance = v(1.04f, 1.0f, 0.94f), saturation = 1.2f, contrast = 1.45f, toe = 1.25f,
        shoulder = 1.1f, blackLift = 0.01f, grain = 0.10f, grainSize = 0.9f),
    FilmStock("Rochester Portrait 400", "fine-grained portrait colour negative", 400f, 36,
        crosstalk = mix(v(1.0f, 0.02f, -0.02f), v(0.0f, 0.98f, 0.02f), v(-0.01f, 0.04f, 0.97f)),
        balance = v(1.03f, 1.0f, 0.96f), saturation = 0.9f, contrast = 0.85f, toe = 0.95f,
        shoulder = 0.6f, blackLift = 0.035f, grain = 0.14f, grainSize = 1.1f),
    FilmStock("Ashigara Velvet 50", "saturated landscape slide film", 50f, 36,
        crosstalk = mix(v(1.12f, -0.08f, -0.04f), v(-0.06f, 1.14f, -0.08f), v(-0.02f, -0.10f, 1.12f)),
        balance = v(1.0f, 1.02f, 1.0f), saturation = 1.45f, contrast = 1.6f, toe = 1.35f,
        shoulder = 1.15f, blackLift = 0.008f, grain = 0.08f, grainSize = 0.8f),
    FilmStock("Rochester Golden 200", "warm consumer colour negative", 200f, 24,
        crosstalk = mix(v(1.02f, 0.0f, -0.02f), v(0.02f, 1.0f, -0.02f), v(-0.02f, 0.02f, 0.96f)),
        balance = v(1.06f, 1.02f, 0.88f), saturation = 1.1f, contrast = 1.0f, toe = 1.0f,
        shoulder = 0.75f, blackLift = 0.03f, grain = 0.17f, grainSize = 1.2f),
    FilmStock("Backlot 800T", "cinema film without the anti-halation layer", 800f, 36,
        crosstalk = mix(v(1.0f, 0.03f, -0.03f), v(0.0f, 1.0f, 0.0f), v(-0.02f, 0.02f, 1.0f)),
        balance = v(0.80f, 1.0f, 1.40f), saturation = 1.0f, contrast = 0.95f, toe = 1.05f,
        shoulder = 0.7f, blackLift = 0.03f, grain = 0.17f, grainSize = 1.3f,
        halation = 0.9f, halationTint = v(1.0f, 0.18f, 0.04f)),
    FilmStock("Rochester Tri-Pan 400", "the gritty reportage black-and-white", 400f, 36,
        blackAndWhite = true, bwMix = v(0.32f, 0.56f, 0.12f),
        contrast = 1.2f, toe = 1.1f, shoulder = 0.8f, blackLift = 0.025f, grain = 0.30f, grainSize = 1.4f),
    FilmStock("Mobberley Hyper-Pan 400", "the British reportage 400: softer than its American rival, pushes beautifully", 400f, 36,
        blackAndWhite = true, bwMix = v(0.30f, 0.58f, 0.12f),
        contrast = 1.05f, toe = 1.0f, shoulder = 0.75f, blackLift = 0.03f, grain = 0.26f, grainSize = 1.35f),
    FilmStock("Rochester T-Grain 100", "smooth tabular-grain black-and-white", 100f, 36,
        blackAndWhite = true, bwMix = v(0.28f, 0.60f, 0.12f),
        contrast = 1.05f, toe = 1.0f, shoulder = 0.85f, blackLift = 0.02f, grain = 0.14f, grainSize = 1.0f),
    FilmStock("Mobberley Night-Pan 3200", "push-to-the-limit low-light black-and-white", 3200f, 36,
        blackAndWhite = true, bwMix = v(0.36f, 0.52f, 0.12f),
        contrast = 1.3f, toe = 1.2f, shoulder = 0.85f, blackLift = 0.04f, grain = 0.18f, grainSize = 1.9f),
    FilmStock("Rochester Fine Colour 100", "the world's finest-grain colour negative: punchy, clean", 100f, 36,
        crosstalk = mix(v(1.08f, -0.04f, -0.04f), v(-0.03f, 1.08f, -0.05f), v(-0.02f, -0.06f, 1.08f)),
        balance = v(1.02f, 1.0f, 0.98f), saturation = 1.3f, contrast = 1.1f, toe = 1.05f,
        shoulder = 0.65f, blackLift = 0.02f, grain = 0.07f, grainSize = 0.8f),
    FilmStock("Rochester E-Chrome 100", "neutral, accurate professional slide film", 100f, 36,
        crosstalk = mix(v(1.04f, -0.02f, -0.02f), v(-0.02f, 1.04f, -0.02f), v(-0.01f, -0.03f, 1.04f)),
        balance = v(0.99f, 1.0f, 1.02f), saturation = 1.08f, contrast = 1.35f, toe = 1.2f,
        shoulder = 1.05f, blackLift = 0.01f, grain = 0.08f, grainSize = 0.85f),
    FilmStock("Rochester Plus-Pan 125", "the medium-speed black-and-white of the 50s to 2000s", 125f, 36,
        blackAndWhite = true, bwMix = v(0.30f, 0.59f, 0.11f),
        contrast = 1.1f, toe = 1.05f, shoulder = 0.85f, blackLift = 0.02f, grain = 0.18f, grainSize = 1.1f),
    FilmStock("Rochester Motion 50D", "fine daylight cinema negative: enormous latitude, gentle colour", 50f, 36,
        crosstalk = mix(v(1.0f, 0.01f, -0.01f), v(0.0f, 1.0f, 0.0f), v(-0.01f, 0.02f, 0.99f)),
        balance = v(1.0f, 1.0f, 1.0f), saturation = 0.95f, contrast = 0.8f, toe = 0.9f,
        shoulder = 0.45f, blackLift = 0.03f, grain = 0.06f, grainSize = 0.8f),
    FilmStock("Ashigara Nocturna 1600", "the high-speed colour negative for city nights without flash", 1600f, 36,
        crosstalk = mix(v(1.0f, 0.02f, -0.02f), v(0.01f, 1.0f, -0.01f), v(-0.02f, 0.03f, 0.98f)),
        balance = v(1.02f, 1.0f, 0.96f), saturation = 0.95f, contrast = 0.95f, toe = 1.0f,
        shoulder = 0.7f, blackLift = 0.04f, grain = 0.17f, grainSize = 1.5f),
    FilmStock("Rochester HS Infrared 820", "black-and-white infrared: leaves glow white, skies go black, no anti-halation", 50f, 36,
        blackAndWhite = true, bwMix = v(0.65f, 0.95f, -0.6f),
        contrast = 1.25f, toe = 1.15f, shoulder = 0.8f, blackLift = 0.03f, grain = 0.25f, grainSize = 1.4f,
        halation = 0.9f, halationTint = v(1.0f, 1.0f, 1.0f)),
    FilmStock("Rochester Golden 200 (expired)", "a consumer roll ten years past its date: faded, shifted, fogged", 200f, 24,
        crosstalk = mix(v(0.95f, 0.08f, -0.03f), v(0.04f, 0.9f, 0.06f), v(0.02f, 0.1f, 0.88f)),
        balance = v(1.06f, 0.93f, 1.02f), saturation = 0.75f, contrast = 0.72f, toe = 0.85f,
        shoulder = 0.7f, blackLift = 0.09f, grain = 0.26f, grainSize = 1.4f),
    FilmStock("Rochester Aero-Colour IR 400", "colour infrared reconnaissance slide film: leaves turn magenta-red, skin yellow, skies deep blue", 400f, 36,
        // The layer swap and an estimate of infrared happen in the shader (film_look).
        balance = v(1.0f, 0.97f, 1.0f), saturation = 1.35f, contrast = 1.3f, toe = 1.15f,
        shoulder = 1.0f, blackLift = 0.015f, grain = 0.12f, grainSize = 1.0f, falseColourIR = true),
)
