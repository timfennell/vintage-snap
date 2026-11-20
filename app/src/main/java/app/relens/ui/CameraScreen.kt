package app.relens.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.text.drawText
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.graphics.graphicsLayer
import app.relens.engine.Movements
import app.relens.optics.Lens
import app.relens.optics.Movement
import kotlin.math.cos
import kotlin.math.sin

/**
 * The camera body: the finder (the phone's live picture, with a microprism
 * spot where you focus), the LED readout under it, the lens's focus and
 * aperture rings, and the deck: the bag, the shutter release, the darkroom.
 * The rings set how the frame will be developed, as on film: you see the
 * result when the print comes out of the darkroom.
 */
@Composable
fun CameraScreen(body: Body, haptics: Haptics, openBag: () -> Unit, openFilm: () -> Unit, openDarkroom: () -> Unit) {
    val liveD by body.rig.liveFocusD.collectAsState()
    val sceneEV by body.rig.sceneEV.collectAsState()
    val framing by body.rig.framing.collectAsState()
    val rigError by body.rig.error.collectAsState()
    val raw = framing?.camera?.raw == true
    val lens = body.lens
    // The mirror: up for the exposure, the finder black; down again, the picture back.
    var mirrorUp by remember { mutableStateOf(false) }
    // The mirror stays up while the frame is taken and the rangefinder sweep runs (hold steady),
    // and drops when both are done, as a slow exposure keeps an SLR's finder dark.
    LaunchedEffect(mirrorUp) {
        if (mirrorUp) {
            kotlinx.coroutines.delay(150)
            while (body.busy || body.sweeping) kotlinx.coroutines.delay(30)
            mirrorUp = false
        }
    }
    val blackout by animateFloatAsState(if (mirrorUp) 1f else 0f,
        androidx.compose.animation.core.tween(if (mirrorUp) 0 else 110), label = "mirror")

    Column(Modifier.fillMaxSize().background(Palette.leather).statusBarsPadding().navigationBarsPadding()) {
        // ---- top plate: what's mounted and loaded, the frame counter ----
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable { openBag() }) {
                Text(lens.name, color = Palette.cream, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(lens.inspiredBy, color = Palette.dim, fontSize = 10.sp, maxLines = 1)
            }
            Column(Modifier.clickable { openFilm() }, horizontalAlignment = Alignment.End) {
                Text(body.stock.name, color = Palette.amber, fontSize = 12.sp)
                Text("EI ${body.ratedEI.toInt()}${if (body.push != 0) " (%+d)".format(body.push) else ""} · ${if (raw) "RAW" else "JPEG"} · %.1f MP".format(framing?.megapixels ?: 0f),
                    color = Palette.dim, fontSize = 10.sp)
            }
            Spacer(Modifier.width(10.dp))
            FrameCounter(body.framesShot, body.stock.exposures, Modifier.clickable { openFilm() })
        }

        // ---- the finder: the lens's own field of view, 3:2 like 35 mm film (2:3 held upright) ----
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp)).background(Color.Black)) {
                val shiftView = Movements.keystone(lens, floatArrayOf(body.shiftX, body.shiftY))
                Finder(body, framing?.crop ?: 1f, Modifier.fillMaxSize()
                    .graphicsLayer {
                        // The shift lens's perspective, live (the print gets the same turn in the shader).
                        rotationX = -Math.toDegrees(shiftView[0].toDouble()).toFloat()
                        rotationY = Math.toDegrees(shiftView[1].toDouble()).toFloat()
                        cameraDistance = lens.focalMM / 36f * maxOf(size.width, size.height) / density / 72f * 8f
                        val z = 1f + 0.9f * (kotlin.math.abs(shiftView[0]) + kotlin.math.abs(shiftView[1]))
                        scaleX = z; scaleY = z
                    })
                // Taps and drags over the finder (a separate layer, so the view's own transform doesn't move them).
                Box(Modifier.fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { tap ->
                            body.tapFocus(tap.x / size.width, tap.y / size.height)
                            haptics.tick()
                        }
                    }
                    .pointerInput(lens) {
                        if (lens.movement == Movement.None) return@pointerInput
                        var travelled = 0f
                        detectDragGestures(onDragEnd = { travelled = 0f }) { change, d ->
                            change.consume()
                            body.moveLens(d.x / size.width, d.y / size.height)
                            travelled += d.getDistance()
                            if (travelled > 40f) { travelled = 0f; haptics.tick() }
                        }
                    })
                // Through the filter: the finder sees its colour (normalised: the meter makes up the light).
                val fl = body.filter
                if (!fl.isNone && fl.stops < 5f) {
                    val tl = maxOf(fl.transmittance, 1e-4f)
                    val tint = Color((fl.transmission[0] / tl).coerceIn(0f, 1f), (fl.transmission[1] / tl).coerceIn(0f, 1f),
                        (fl.transmission[2] / tl).coerceIn(0f, 1f))
                    if (tint != Color.White) Canvas(Modifier.fillMaxSize()) {
                        drawRect(tint, blendMode = androidx.compose.ui.graphics.BlendMode.Multiply)
                    }
                }
                FinderOverlay(body.focusX, body.focusY, Modifier.fillMaxSize())
                when (lens.movement) {
                    is Movement.Tilt -> SharpBand(body, Modifier.fillMaxSize())
                    is Movement.Shift -> ShiftGuides(Modifier.fillMaxSize())
                    Movement.None -> {}
                }
                sceneEV?.let { ev -> MeterCard(body.shutterPos, body.meteredShutterPos(ev), body.exposureStops(ev), Modifier.align(Alignment.CenterStart)) }
                Text("${lens.name}${if (body.filter.isNone) "" else " + " + body.filter.name}  ·  ${body.stock.name}", color = Palette.cream.copy(alpha = 0.8f), fontSize = 10.sp,
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp))
                rigError?.let { Text(it, color = Palette.led, fontSize = 11.sp, modifier = Modifier.align(Alignment.Center).padding(16.dp)) }
                if (blackout > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = blackout)))
                if (mirrorUp && (body.busy || body.sweeping)) Text("HOLD STEADY", color = Palette.led, fontSize = 16.sp,
                    fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, modifier = Modifier.align(Alignment.Center))
            }
        }

        // ---- LED readout under the finder ----
        val ringM = body.focusM
        // While the mirror is up (the frame and the rangefinder sweep), the ring stays on the
        // subject: the sweep's hops between points aren't the photographer's focus.
        val liveM = liveD?.let { if (it > 0.01f) 1f / it else Float.POSITIVE_INFINITY }
        var heldM by remember { mutableStateOf<Float?>(null) }
        if (!(body.busy || body.sweeping)) heldM = liveM
        val shownM = ringM ?: heldM
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            Led("F ${fLabel(body.fNumber)}")
            Led(shutterLabel(body.shutterSeconds))
            Led(if (shownM == null) "-- m" else if (shownM.isInfinite()) "∞ m" else "%.2f m".format(shownM),
                // long-press the distance: collimate this camera's focus readings
                Modifier.pointerInput(Unit) { detectTapGestures(onLongPress = { body.startCollimating(); haptics.clunk() }) })
            when (lens.movement) {
                is Movement.Tilt -> Led("T %.0f°".format(kotlin.math.hypot(body.tiltX, body.tiltY)),
                    Modifier.clickable { body.centreMovements(); haptics.click() })
                is Movement.Shift -> Led("S %+.0f".format(body.shiftY),
                    Modifier.clickable { body.centreMovements(); haptics.click() })
                Movement.None -> Led(if (ringM == null) "AF" else "MF")
            }
            Led("${body.framesLeft}")
        }

        if (body.collimating || body.collimationNote.isNotEmpty() && body.collimation.isNotEmpty()) Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text((if (body.collimating) "COLLIMATE ${body.rig.currentCameraId ?: ""} · " else "") + body.collimationNote,
                color = Palette.amber, fontSize = 12.sp, modifier = Modifier.weight(1f))
            if (body.collimating) {
                Text("SET", color = Palette.leather, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Palette.amber)
                        .clickable { body.collimateSet(); haptics.click() }.padding(horizontal = 12.dp, vertical = 6.dp))
                Spacer(Modifier.width(6.dp))
                Text("✕", color = Palette.dim, fontSize = 16.sp, modifier = Modifier.clickable { body.collimating = false }.padding(6.dp))
            }
        }
        body.message?.let { Text(it, color = Palette.led, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) }
        // ---- the lens barrel: focus ring (with the DoF scale), aperture ring ----
        val marks = lens.focusMarks
        val focusPos = if (!body.focusPos.isNaN()) body.focusPos else positionFor(lens, shownM ?: 3f)
        val dof = dofMarks(lens, body.fNumber, shownM ?: 3f)
        BarrelRing(
            labels = marks.map(::mLabel),
            position = focusPos,
            onPosition = { body.focusPos = it },
            detents = false,
            onDetent = { haptics.tick() },
            onTap = { body.focusPos = Float.NaN; haptics.click() },     // tap the ring: back to the subject
            caption = if (body.focusPos.isNaN()) "FOCUS · AF (tap the finder)" else "FOCUS · tap ring for AF",
            extraMarks = dof,
            labelColor = { if (marks[it].isInfinite()) Palette.orange else Palette.engraving },
            modifier = Modifier.padding(top = 4.dp),
        )
        val stops = lens.apertureStops
        BarrelRing(
            labels = stops.map(::fLabel),
            position = body.aperturePos,
            onPosition = { body.aperturePos = it; body.save() },
            detents = true,
            onDetent = { haptics.click() },
            caption = "APERTURE",
            labelColor = { if (it == 0) Palette.amber else Palette.engraving },
            height = 52.dp,
            spacing = 58.dp,
            modifier = Modifier.padding(top = 2.dp),
        )
        BarrelRing(
            labels = shutterSpeeds.map { it.first },
            position = body.shutterPos,
            onPosition = { body.shutterPos = it; body.save() },
            detents = true,
            onDetent = { haptics.sounds.play(Sounds.Event.DIAL, 0.7f); haptics.tick() },
            caption = "SHUTTER",
            labelColor = { if (shutterSpeeds[it].second >= 1f / 30) Palette.orange else Palette.engraving },
            height = 48.dp,
            spacing = 54.dp,
            knurled = false,
        )

        // ---- the deck ----
        Row(Modifier.fillMaxWidth().height(104.dp).padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            DeckButton("BAG", Modifier.weight(1f)) { openBag() }
            ShutterRelease(busy = body.busy) {
                if (!body.busy && body.framesLeft > 0) { haptics.shutter(); mirrorUp = true }
                body.shoot(onShot = { haptics.advance() })
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                val last = body.darkroom.firstOrNull()
                Box(Modifier.size(64.dp).clip(RoundedCornerShape(6.dp)).background(Palette.panel)
                    .border(1.dp, Palette.panelHi, RoundedCornerShape(6.dp)).clickable { openDarkroom() },
                    contentAlignment = Alignment.Center) {
                    val bmp = last?.preview
                    if (bmp != null) Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    else Text(if (last != null) "…" else "DARK\nROOM", color = Palette.dim, fontSize = 9.sp, lineHeight = 10.sp)
                }
            }
        }
    }
}

/**
 * The phone camera's live picture in a TextureView, scaled so the lens's crop
 * fills the 2:3 finder. The camera system turns the picture upright; it shows
 * as 3:4, so the crop (1/crop of the long side, 2/3 of that across) is
 * enlarged by (9/8) x crop across and crop down.
 */
@Composable
private fun Finder(body: Body, crop: Float, modifier: Modifier) {
    androidx.compose.ui.viewinterop.AndroidView(
        modifier = modifier,
        factory = { ctx ->
            android.view.TextureView(ctx).apply {
                // The crop is a scale about the view's centre: apply it once the view has a size
                // (Compose's update can run before layout, when the centre would be the corner).
                addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> applyCrop(v as android.view.TextureView) }
                surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(t: android.graphics.SurfaceTexture, w: Int, h: Int) {
                        body.rig.attach(t); body.rig.select(body.lens)
                    }
                    override fun onSurfaceTextureSizeChanged(t: android.graphics.SurfaceTexture, w: Int, h: Int) {}
                    override fun onSurfaceTextureDestroyed(t: android.graphics.SurfaceTexture): Boolean { body.rig.detach(); return true }
                    override fun onSurfaceTextureUpdated(t: android.graphics.SurfaceTexture) {}
                }
            }
        },
        update = { tv -> tv.tag = crop; applyCrop(tv) },
    )
}

/** Enlarges the upright 3:4 picture so the lens's crop fills the 2:3 view, about its centre. */
private fun applyCrop(tv: android.view.TextureView) {
    val crop = tv.tag as? Float ?: return
    if (tv.width == 0 || tv.height == 0) return
    val m = android.graphics.Matrix()
    m.setScale(9f / 8f * crop, crop, tv.width / 2f, tv.height / 2f)
    tv.setTransform(m)
}

fun shutterLabel(t: Float): String = if (t >= 0.5f) "%.0f\"".format(t) else "1/%.0f".format(1f / t)

/**
 * Vintage Click's meter: the shutter speeds on a card at the finder's left edge, the set speed
 * lit, and a needle (with mass, so it swings) pointing at the speed the meter wants.
 */
@Composable
private fun MeterCard(setPos: Float, meterPos: Float, stops: Float, modifier: Modifier) {
    val needle by animateFloatAsState(meterPos.coerceIn(-0.6f, shutterSpeeds.size - 0.4f),
        androidx.compose.animation.core.spring(dampingRatio = 0.55f, stiffness = 60f), label = "needle")
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    Canvas(modifier.fillMaxHeight(0.62f).width(34.dp).padding(start = 4.dp)) {
        drawRect(Color(0x99000000))
        val n = shutterSpeeds.size
        val step = size.height / n
        fun y(i: Float) = size.height - (i + 0.5f) * step       // slow at the bottom, fast at the top
        for ((i, sp) in shutterSpeeds.withIndex()) {
            val on = kotlin.math.abs(i - setPos) < 0.5f
            val t = measurer.measure(sp.first, androidx.compose.ui.text.TextStyle(
                color = if (on) Palette.led else Palette.cream.copy(alpha = 0.55f), fontSize = 8.sp,
                fontWeight = if (on) FontWeight.Bold else FontWeight.Normal))
            drawText(t, topLeft = Offset(size.width - t.size.width - 2f, y(i.toFloat()) - t.size.height / 2f))
        }
        // the needle: a red pointer from the left edge
        val ny = y(needle)
        val over = stops > 0.3f; val under = stops < -0.3f
        val c = if (over || under) Palette.led else Color(0xFF7CFF6B)
        drawLine(c, Offset(0f, ny), Offset(size.width * 0.45f, ny), 3f)
    }
}

@Composable
private fun Led(text: String, modifier: Modifier = Modifier) {
    Text(text, color = Palette.led, fontFamily = FontFamily.Monospace, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = modifier)
}

/**
 * A tilt lens's sharp band: where the tilted plane of focus crosses a scene at
 * the subject's distance, through the focus point, as wide as the depth of field.
 * It narrows and turns as the lens is rocked.
 */
@Composable
private fun SharpBand(body: Body, modifier: Modifier) {
    val lens = body.lens
    val tilt = Movements.tiltDioptres(lens, floatArrayOf(body.tiltX, body.tiltY))
    val g = kotlin.math.hypot(tilt[0], tilt[1])
    if (g < 1e-4f) return
    Canvas(modifier) {
        val hd = kotlin.math.hypot(size.width / 2, size.height / 2)
        // depth of field in dioptres either side (0.03 mm circle), over the plane's slope across the frame
        val f = lens.focalMM / 1000f
        val s = body.focusM?.takeIf { it.isFinite() } ?: 2f
        val dofD = 0.00003f / ((lens.focalMM / 1000f / body.fNumber) * f / (1 - f / s))
        val half = (dofD / g * hd).coerceIn(4f, size.maxDimension)
        val dir = Offset(tilt[0] / g, tilt[1] / g)              // the way focus changes
        val along = Offset(-dir.y, dir.x)
        val c = Offset(body.focusX * size.width, body.focusY * size.height)
        val L = size.maxDimension * 1.5f
        val band = androidx.compose.ui.graphics.Path().apply {
            moveTo((c + dir * half - along * L).x, (c + dir * half - along * L).y)
            lineTo((c + dir * half + along * L).x, (c + dir * half + along * L).y)
            lineTo((c - dir * half + along * L).x, (c - dir * half + along * L).y)
            lineTo((c - dir * half - along * L).x, (c - dir * half - along * L).y)
            close()
        }
        drawPath(band, Color(0x22FF8A2A))
        val dash = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(14f, 10f))
        drawLine(Color(0xAAFF8A2A), c + dir * half - along * L, c + dir * half + along * L, 2f, pathEffect = dash)
        drawLine(Color(0xAAFF8A2A), c - dir * half - along * L, c - dir * half + along * L, 2f, pathEffect = dash)
    }
}

/** A shift lens's guides: verticals and horizontals to straighten buildings against. */
@Composable
private fun ShiftGuides(modifier: Modifier) {
    Canvas(modifier) {
        val c = Color(0x44EDE3D0)
        for (k in 1..5) {
            val x = size.width * k / 6; drawLine(c, Offset(x, 0f), Offset(x, size.height), 1f)
        }
        for (k in 1..3) {
            val y = size.height * k / 4; drawLine(c, Offset(0f, y), Offset(size.width, y), 1f)
        }
    }
}

@Composable
private fun FrameCounter(shot: Int, total: Int, modifier: Modifier = Modifier) {
    Box(modifier.size(42.dp).clip(CircleShape).background(Color(0xFF0A0A0A)).border(2.dp, Palette.chromeLo, CircleShape),
        contentAlignment = Alignment.Center) {
        Text("$shot", color = if (shot >= total) Palette.led else Palette.cream, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun DeckButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier, contentAlignment = Alignment.CenterStart) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(6.dp)).background(Palette.panel)
            .border(1.dp, Palette.panelHi, RoundedCornerShape(6.dp)).clickable(onClick = onClick),
            contentAlignment = Alignment.Center) {
            Text(label, color = Palette.amber, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        }
    }
}

/** A chrome shutter release with a soft collar: it sinks as it is pressed. */
@Composable
private fun ShutterRelease(busy: Boolean, onRelease: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.9f else 1f, label = "shutter")
    Box(Modifier.size(92.dp).clip(CircleShape).background(Brush.radialGradient(listOf(Color(0xFF2E2D2B), Color(0xFF111110)))),
        contentAlignment = Alignment.Center) {
        Box(Modifier.size(64.dp).scale(s).clip(CircleShape)
            .background(Brush.linearGradient(listOf(Palette.chromeHi, Palette.chromeLo, Palette.chromeHi)))
            .border(2.dp, if (busy) Palette.led else Color(0xFF555552), CircleShape)
            .clickable(interactionSource = src, indication = null, onClick = onRelease))
    }
}

/** The finder's marks: bright-line corners and a microprism spot at the focus point. */
@Composable
private fun FinderOverlay(fx: Float, fy: Float, modifier: Modifier) {
    Canvas(modifier) {
        val c = Color(0xCCEDE3D0)
        val l = size.minDimension * 0.06f
        val m = size.minDimension * 0.04f
        for ((x, y, dx, dy) in listOf(
            listOf(m, m, 1f, 1f), listOf(size.width - m, m, -1f, 1f),
            listOf(m, size.height - m, 1f, -1f), listOf(size.width - m, size.height - m, -1f, -1f))) {
            drawLine(c, Offset(x, y), Offset(x + dx * l, y), 2f)
            drawLine(c, Offset(x, y), Offset(x, y + dy * l), 2f)
        }
        val center = Offset(fx * size.width, fy * size.height)
        val r = size.minDimension * 0.07f
        drawCircle(Color(0x99EDE3D0), r, center, style = Stroke(1.5f))
        drawCircle(Color(0x55EDE3D0), r * 0.55f, center, style = Stroke(1f))
        // the microprism's fine hatch
        for (k in 0 until 12) {
            val a = k * Math.PI.toFloat() / 6
            drawLine(Color(0x33EDE3D0), center + Offset(cos(a), sin(a)) * (r * 0.6f), center + Offset(cos(a), sin(a)) * (r * 0.95f), 1f)
        }
    }
}

/** The phone camera's 35 mm-equivalent focal length (Nothing main camera: 0.68 of the long side x 36 mm). */
const val VIEW_FOCAL_MM = 0.68f * 36f

/** The focus ring's position (in scale marks) for a distance, interpolating in dioptres. */
fun positionFor(lens: Lens, m: Float): Float {
    val marks = lens.focusMarks
    val d = if (m.isInfinite()) 0f else 1f / m
    for (i in 0 until marks.size - 1) {
        val d0 = 1f / marks[i]; val d1 = 1f / marks[i + 1]
        if (d <= d0 && d >= d1) return i + (d0 - d) / (d0 - d1)
    }
    return if (d > 1f / marks[0]) 0f else (marks.size - 1).toFloat()
}

/** The DoF scale: the near and far limits (35 mm, 0.03 mm circle) as orange ticks on the focus ring. */
fun dofMarks(lens: Lens, n: Float, s: Float): List<Pair<Float, Color>> {
    if (s.isInfinite() || lens.isPinhole) return emptyList()
    // The frame is the lens's own field of view: its own focal length and f-number.
    val f = lens.focalMM / 1000f
    val h = f * f / (n * 0.00003f) + f
    val near = s * (h - f) / (h + s - 2 * f)
    val far = if (s < h) s * (h - f) / (h - s) else Float.POSITIVE_INFINITY
    return listOf(positionFor(lens, near) to Palette.orange, positionFor(lens, far) to Palette.orange)
}
