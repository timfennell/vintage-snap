package app.relens.ui

import androidx.camera.compose.CameraXViewfinder
import androidx.camera.viewfinder.compose.MutableCoordinateTransformer
import androidx.camera.viewfinder.core.ImplementationMode
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
    val surface by body.camera.surfaceRequest.collectAsState()
    val liveD by body.camera.liveFocusD.collectAsState()
    val raw by body.camera.rawSupported.collectAsState()
    val lens = body.lens
    val transformer = remember { MutableCoordinateTransformer() }
    // The mirror: up for the exposure, the finder black; down again, the picture back.
    var mirrorUp by remember { mutableStateOf(false) }
    LaunchedEffect(mirrorUp) { if (mirrorUp) { kotlinx.coroutines.delay(150); mirrorUp = false } }
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
                Text("EI ${body.ratedEI.toInt()}${if (body.push != 0) " (%+d)".format(body.push) else ""} · ${if (raw) "RAW+JPEG" else "JPEG"}",
                    color = Palette.dim, fontSize = 10.sp)
            }
            Spacer(Modifier.width(10.dp))
            FrameCounter(body.framesShot, body.stock.exposures, Modifier.clickable { openFilm() })
        }

        // ---- the finder ----
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.aspectRatio(3f / 4f).clip(RoundedCornerShape(6.dp)).background(Color.Black)) {
                val shiftView = Movements.keystone(lens, floatArrayOf(body.shiftX, body.shiftY))
                surface?.let { req ->
                    CameraXViewfinder(
                        surfaceRequest = req,
                        // Embedded (a TextureView): the filter's tint can blend over it.
                        implementationMode = ImplementationMode.EMBEDDED,
                        coordinateTransformer = transformer,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                            .graphicsLayer {
                                // The shift lens's perspective, live (the print gets the same turn in the shader).
                                rotationX = -Math.toDegrees(shiftView[0].toDouble()).toFloat()
                                rotationY = Math.toDegrees(shiftView[1].toDouble()).toFloat()
                                cameraDistance = 0.68f * maxOf(size.width, size.height) / density / 72f * 8f
                                val z = 1f + 0.9f * (kotlin.math.abs(shiftView[0]) + kotlin.math.abs(shiftView[1]))
                                scaleX = z; scaleY = z
                            }
                            .pointerInput(Unit) {
                                detectTapGestures { tap ->
                                    body.tapFocus(tap.x / size.width, tap.y / size.height)
                                    with(transformer) { val s = tap.transform(); body.camera.focusAt(s.x, s.y) }
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
                            },
                    )
                }
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
                Text("${lens.name}${if (body.filter.isNone) "" else " + " + body.filter.name}  ·  ${body.stock.name}", color = Palette.cream.copy(alpha = 0.8f), fontSize = 10.sp,
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp))
                if (blackout > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = blackout)))
            }
        }

        // ---- LED readout under the finder ----
        val ringM = body.focusM
        val shownM = ringM ?: liveD?.let { if (it > 0.01f) 1f / it else Float.POSITIVE_INFINITY }
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            Led("F ${fLabel(body.fNumber)}")
            Led(if (shownM == null) "-- m" else if (shownM.isInfinite()) "∞ m" else "%.2f m".format(shownM))
            when (lens.movement) {
                is Movement.Tilt -> Led("T %.0f°".format(kotlin.math.hypot(body.tiltX, body.tiltY)),
                    Modifier.clickable { body.centreMovements(); haptics.click() })
                is Movement.Shift -> Led("S %+.0f".format(body.shiftY),
                    Modifier.clickable { body.centreMovements(); haptics.click() })
                Movement.None -> Led(if (ringM == null) "AF" else "MF")
            }
            Led("${body.framesLeft}")
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
            height = 56.dp,
            spacing = 58.dp,
            modifier = Modifier.padding(top = 2.dp),
        )

        // ---- the deck ----
        Row(Modifier.fillMaxWidth().height(116.dp).padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
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
        val f = VIEW_FOCAL_MM / 1000f
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
    // Equivalent framing (Optics.cocRadiusPxPerDioptre): the lens's pupil, the phone's focal length.
    val f = VIEW_FOCAL_MM / 1000f
    val ne = n * VIEW_FOCAL_MM / lens.focalMM
    val h = f * f / (ne * 0.00003f) + f
    val near = s * (h - f) / (h + s - 2 * f)
    val far = if (s < h) s * (h - f) / (h - s) else Float.POSITIVE_INFINITY
    return listOf(positionFor(lens, near) to Palette.orange, positionFor(lens, far) to Palette.orange)
}
