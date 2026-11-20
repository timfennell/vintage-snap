package app.relens.ui

import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.clipPath
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
    body.onParallaxFire = { haptics.sounds.play(Sounds.Event.SHUTTER, 0.8f) }
    // The mirror: up for the exposure, the finder black; down again, the picture back.
    var mirrorUp by remember { mutableStateOf(false) }
    // The mirror stays up while the frame is taken and the rangefinder sweep runs (hold steady),
    // and drops when both are done, as a slow exposure keeps an SLR's finder dark.
    LaunchedEffect(mirrorUp) {
        if (mirrorUp) {
            kotlinx.coroutines.delay(150)
            // (the parallax glide needs the finder: the mirror comes down for it)
            while ((body.busy || body.sweeping) && body.parallax == null) kotlinx.coroutines.delay(30)
            mirrorUp = false
        }
    }
    val blackout by animateFloatAsState(if (mirrorUp) 1f else 0f,
        androidx.compose.animation.core.tween(if (mirrorUp) 0 else 110), label = "mirror")

    // The ground glass, in screen pixels: the eyepiece effect over the whole body works round it.
    var glass by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    Column(Modifier.fillMaxSize().background(Palette.leather).eyepiece(glass, wall = 12.dp)
        .statusBarsPadding().navigationBarsPadding()) {
        // ---- top plate: what's mounted and loaded, the frame counter ----
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable { openBag() }) {
                Text(lens.name, color = Palette.cream, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                if (body.filter.isNone) Text(lens.inspiredBy, color = Palette.dim, fontSize = 10.sp, maxLines = 1)
                else Text("+ ${body.filter.name}", color = Palette.amber, fontSize = 10.sp, maxLines = 1)
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
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
          // The eyepiece: a dark surround round the picture, seen through a little pincushion,
          // with light from the picture's edge spilling into it (Finder.kt).
          Box {
            Box(Modifier.aspectRatio(2f / 3f).onGloballyPositioned { glass = it.boundsInRoot() }
                .clip(RoundedCornerShape(6.dp)).background(Color.Black)) {
                val shiftView = Movements.keystone(lens, floatArrayOf(body.shiftX, body.shiftY))
                Finder(body, framing?.crop ?: 1f, Modifier.fillMaxSize()
                    .graphicsLayer {
                        // The shift lens's perspective, live (the print gets the same turn in the shader).
                        rotationX = -Math.toDegrees(shiftView[0].toDouble()).toFloat()
                        rotationY = Math.toDegrees(shiftView[1].toDouble()).toFloat()
                        cameraDistance = lens.focalMM / 36f * maxOf(size.width, size.height) / density / 72f * 8f
                        val z = 1f + 0.9f * (kotlin.math.abs(shiftView[0]) + kotlin.math.abs(shiftView[1]))
                        scaleX = z; scaleY = z
                    }
                    // a tilted (or whacked) lens's blur, live: the sharp band and the fall-off either side
                    .then(if (lens.movement is Movement.Tilt) Modifier.tiltBlur(
                        Movements.tiltDioptres(lens, floatArrayOf(body.tiltX, body.tiltY)), body.focusX, body.focusY, lens.focalMM, body.fNumber)
                        else Modifier))
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
                        if (lens.movement is Movement.Tilt) {
                            // One finger round the focus point steers the band (round: turn it, out and
                            // in: tilt more and less); two fingers pinch it narrower or wider.
                            detectTransformGestures { centroid, pan, zoom, _ ->
                                if (kotlin.math.abs(zoom - 1f) > 0.002f) {
                                    body.scaleTilt(1f / zoom)
                                    travelled += kotlin.math.abs(zoom - 1f) * 400f
                                } else {
                                    val c = Offset(body.focusX * size.width, body.focusY * size.height)
                                    val now = centroid - c; val was = now - pan
                                    if (now.getDistance() > 8f && was.getDistance() > 8f) {
                                        var da = kotlin.math.atan2(now.y, now.x) - kotlin.math.atan2(was.y, was.x)
                                        if (da > Math.PI) da -= (2 * Math.PI).toFloat(); if (da < -Math.PI) da += (2 * Math.PI).toFloat()
                                        body.steerTilt(da, (now.getDistance() - was.getDistance()) / size.width, kotlin.math.atan2(now.y, now.x))
                                    }
                                    travelled += pan.getDistance()
                                }
                                if (travelled > 40f) { travelled = 0f; haptics.tick() }
                            }
                            return@pointerInput
                        }
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
                sceneEV?.let { ev -> MeterCard(body.shutterPos, body.meteredShutterPos(ev), Modifier.fillMaxSize()) }
                rigError?.let { Text(it, color = Palette.led, fontSize = 11.sp, modifier = Modifier.align(Alignment.Center).padding(16.dp)) }
                if (blackout > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = blackout)))
                body.parallax?.let { pc -> ParallaxFinder(pc, Modifier.fillMaxSize()) }
                if (mirrorUp && (body.busy || body.sweeping) && body.parallax == null) Text("HOLD STEADY", color = Palette.led, fontSize = 16.sp,
                    fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, modifier = Modifier.align(Alignment.Center))
            }
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
            Led(fLabel(body.fNumber), cells = 3, prefix = "F")
            body.shutterSeconds.let { t -> Led(if (t >= 0.5f) "%.0f".format(t) else "%.0f".format(1f / t), cells = 4, suffix = if (t >= 0.5f) "\"" else "") }
            Led(when { shownM == null -> "---"; shownM.isInfinite() -> "∞"; shownM < 10f -> "%.2f".format(shownM); shownM < 100f -> "%.1f".format(shownM); else -> "%.0f".format(shownM) }, cells = 3, suffix = "m",
                // long-press the distance: collimate this camera's focus readings
                modifier = Modifier.pointerInput(Unit) { detectTapGestures(onLongPress = { body.startCollimating(); haptics.clunk() }) })
            when (lens.movement) {
                is Movement.Tilt -> Led("%.0f".format(kotlin.math.hypot(body.tiltX, body.tiltY)), cells = 2, prefix = "T", suffix = "°",
                    modifier = Modifier.clickable { body.centreMovements(); haptics.click() })
                is Movement.Shift -> Led("%+.0f".format(body.shiftY), cells = 3, prefix = "S",
                    modifier = Modifier.clickable { body.centreMovements(); haptics.click() })
                Movement.None -> Led(if (ringM == null) "AF" else "MF", cells = 2)
            }
            Led("${body.framesLeft}", cells = 2)
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
        ModelsBanner(body)
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
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                DeckButton("BAG", Modifier) { openBag() }
                if (body.parallaxAvailable) {
                    Spacer(Modifier.width(12.dp))
                    ParallaxSwitch(body.parallaxOn) { body.toggleParallax(); haptics.click() }
                }
            }
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
                    else Text(if (last != null) "…" else "LIGHT\nTABLE", color = Palette.dim, fontSize = 9.sp, lineHeight = 10.sp)
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

@Composable
/**
 * The light meter, as in Vintage Click's finder (ViewfinderHUD.drawPlate and the
 * shader's meter_light): a scale of shutter speeds cast as light beside the image,
 * a faint cream bar with red caps (M at the foot), fast speeds in cream and the
 * slow ones (1/30 and longer) in red, each tick aimed at the needle's pivot, which
 * sits off the frame to the left; a green tab at the set speed; and the needle, lit
 * red with a soft glow, swinging in from the dark edge to the speed the meter
 * wants. It has mass: it overshoots a little and settles.
 */
private fun MeterCard(setPos: Float, meterPos: Float, modifier: Modifier) {
    val needle by animateFloatAsState(meterPos.coerceIn(-1.5f, shutterSpeeds.size + 0.5f),
        androidx.compose.animation.core.spring(dampingRatio = 0.71f, stiffness = 60f), label = "needle")
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val cream = Color(0xFFFFE6C7); val red = Color(0xFFFF6647); val green = Color(0xE673F2B3)
    val needleRed = Color(0xFFE6140D)
    // A dark backing under the scale and a solid needle, drawn normally: light alone can't
    // show against a bright scene (a white wall), so the scale gets a faint smoked strip
    // to sit on, as the game's finder is dark round it.
    Canvas(modifier.fillMaxSize()) {
        val u = 8.dp.toPx()
        val cardW = 3.6f * u; val cardH = size.height * 0.66f
        val card = androidx.compose.ui.geometry.Rect(Offset(1.0f * u, (size.height - cardH) / 2), androidx.compose.ui.geometry.Size(cardW, cardH))
        drawRoundRect(Color.Black.copy(alpha = 0.42f), Offset(card.left - 0.3f * u, card.top - 0.3f * u),
            androidx.compose.ui.geometry.Size(card.width + 2.2f * u, card.height + 0.6f * u), androidx.compose.ui.geometry.CornerRadius(0.7f * u))
        val cap = 2.0f * u; val n = shutterSpeeds.size
        val step = (card.height - 2 * cap) / n
        fun y(i: Float) = card.top + cap + (n - 1 - i + 0.5f) * step
        val pivot = Offset(card.left - 9f * u, card.center.y)
        val aim = Offset(card.left + 0.1f * u, y(needle))
        val reach = (card.left + 1.15f * u - pivot.x) / (aim.x - pivot.x)
        val tip = pivot + (aim - pivot) * reach
        val fade = Brush.horizontalGradient(0f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.6f), startX = 0f, endX = 3.5f * u)
        drawLine(fade, pivot, tip, 0.42f * u, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        val red = Brush.horizontalGradient(0f to Color.Transparent, 1f to Color(0xFFE6140D), startX = 0f, endX = 3.5f * u)
        drawLine(red, pivot, tip, maxOf(1.5f, 0.2f * u), cap = androidx.compose.ui.graphics.StrokeCap.Round)
    }
    Canvas(modifier.fillMaxSize().graphicsLayer {
        // projected light: it adds to the image under it
        compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
        blendMode = androidx.compose.ui.graphics.BlendMode.Plus
    }) {
        val u = 8.dp.toPx()
        val cardW = 3.6f * u; val cardH = size.height * 0.66f
        val card = androidx.compose.ui.geometry.Rect(Offset(1.0f * u, (size.height - cardH) / 2), androidx.compose.ui.geometry.Size(cardW, cardH))
        val cap = 2.0f * u
        val n = shutterSpeeds.size
        val step = (card.height - 2 * cap) / n
        fun y(i: Float) = card.top + cap + (n - 1 - i + 0.5f) * step         // fast at the top
        val pivot = Offset(card.left - 9f * u, card.center.y)
        val corner = androidx.compose.ui.geometry.CornerRadius(0.5f * u)
        drawRoundRect(cream.copy(alpha = 0.16f), card.topLeft, card.size, corner)
        val capSize = androidx.compose.ui.geometry.Size(cardW, cap * 0.8f)
        drawRoundRect(red.copy(alpha = 0.75f), card.topLeft, capSize, androidx.compose.ui.geometry.CornerRadius(0.4f * u))
        drawRoundRect(red.copy(alpha = 0.75f), Offset(card.left, card.bottom - cap * 0.8f), capSize, androidx.compose.ui.geometry.CornerRadius(0.4f * u))
        val m = measurer.measure("M", androidx.compose.ui.text.TextStyle(color = Color.Black.copy(alpha = 0.55f), fontSize = (1.0f * u).toSp(),
            fontFamily = Futuraish, fontWeight = FontWeight.SemiBold))
        drawText(m, topLeft = Offset(card.center.x - m.size.width / 2f, card.bottom - cap * 0.4f - m.size.height / 2f))
        val fontPx = minOf(step * 0.58f, cardW * 0.27f)
        for ((i, sp) in shutterSpeeds.withIndex()) {
            val slow = sp.second >= 1f / 30
            val c = if (slow) red else cream
            val yy = y(i.toFloat())
            val t = measurer.measure(sp.first, androidx.compose.ui.text.TextStyle(color = c, fontSize = fontPx.toSp(),
                fontFamily = Futuraish, fontWeight = FontWeight.Medium,
                shadow = androidx.compose.ui.graphics.Shadow(c.copy(alpha = 0.5f), Offset.Zero, 0.5f * u)))
            drawText(t, topLeft = Offset(card.right - t.size.width - 0.35f * u, yy - t.size.height / 2f))
            // a tick at each mark, along the needle's line when it points there
            val p0 = Offset(card.left + 0.1f * u, yy)
            val d = p0 - pivot
            val dir = d / d.getDistance()
            drawLine(c.copy(alpha = 0.85f), p0, p0 + dir * (0.9f * u), 0.13f * u)
        }
        // the green tab at the set speed, pointing out of the scale
        val ty = y(setPos)
        val tab = androidx.compose.ui.graphics.Path().apply {
            moveTo(card.right - 0.2f * u, ty - 0.45f * step); lineTo(card.right + 1.4f * u, ty - 0.25f * step)
            lineTo(card.right + 1.4f * u, ty + 0.25f * step); lineTo(card.right - 0.2f * u, ty + 0.45f * step); close()
        }
        drawPath(tab, green)
        // the needle: aimed through the reading's tick, ending just past the ticks
        val aim = Offset(card.left + 0.1f * u, y(needle))
        val reach = (card.left + 1.15f * u - pivot.x) / (aim.x - pivot.x)
        val tip = pivot + (aim - pivot) * reach
        // lit only inside the frame: it fades in from the edge, swinging out of the dark
        val lit = Brush.horizontalGradient(0f to Color.Transparent, 1f to needleRed, startX = 0f, endX = 3.5f * u)
        val litGlow = Brush.horizontalGradient(0f to Color.Transparent, 1f to needleRed.copy(alpha = 0.25f), startX = 0f, endX = 3.5f * u)
        drawLine(litGlow, pivot, tip, 0.9f * u, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(lit, pivot, tip, maxOf(1.5f, 0.2f * u), cap = androidx.compose.ui.graphics.StrokeCap.Round)
    }
}

/** A readout under the finder: red seven-segment digits with their glow (Finder.kt). */
@Composable
private fun Led(value: String, cells: Int, modifier: Modifier = Modifier, prefix: String = "", suffix: String? = null) {
    SevenSegment(value, cells, prefix, suffix, height = 20.dp, modifier = modifier)
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
    Canvas(modifier.wireLayer()) {
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
        // as the parallax guide draws (Vintage Click's light heads): the band's edges in warm
        // light, a faint cream line through the focus point
        wire(c + dir * half - along * L, c + dir * half + along * L, WIRE_WARM)
        wire(c - dir * half - along * L, c - dir * half + along * L, WIRE_WARM)
        wire(c - along * L, c + along * L, WIRE_CREAM.copy(alpha = 0.3f))
    }
}

/** A shift lens's guides: verticals and horizontals to straighten buildings against. */
@Composable
private fun ShiftGuides(modifier: Modifier) {
    Canvas(modifier.wireLayer()) {
        val c = WIRE_CREAM.copy(alpha = 0.32f)
        for (k in 1..5) { val x = size.width * k / 6; wire(Offset(x, 0f), Offset(x, size.height), c) }
        for (k in 1..3) { val y = size.height * k / 4; wire(Offset(0f, y), Offset(size.width, y), c) }
    }
}

/** The wireframe colours of the parallax guide (ParallaxCapture): warm cream light, and paler. */
private val WIRE_WARM = Color(1.0f, 0.80f, 0.55f, 0.80f)
private val WIRE_CREAM = Color(1.0f, 0.92f, 0.80f, 0.55f)

/** Light added onto the finder, as the wireframes are, not paint. */
private fun Modifier.wireLayer() = this.graphicsLayer {
    compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
    blendMode = androidx.compose.ui.graphics.BlendMode.Plus
}

/** A line as Vintage Click's wireframes draw it: a faint wide glow, a softer middle, then a fine core. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.wire(a: Offset, b: Offset, colour: Color) {
    val cap = androidx.compose.ui.graphics.StrokeCap.Round
    drawLine(colour.copy(alpha = colour.alpha * 0.16f), a, b, 8.dp.toPx(), cap = cap)
    drawLine(colour.copy(alpha = colour.alpha * 0.35f), a, b, 3.dp.toPx(), cap = cap)
    drawLine(colour, a, b, 1.2.dp.toPx(), cap = cap)
}

/**
 * The frame counter, as on an SLR's top plate: a black dial with the frames engraved round
 * its rim (S, then the even frames as numerals and the odd ones as dots, the roll's last frame
 * in orange) turning one click per frame under a small domed glass window in a chrome bezel,
 * read against a red index above.
 */
@Composable
private fun FrameCounter(shot: Int, total: Int, modifier: Modifier = Modifier) {
    val turn by animateFloatAsState(shot.toFloat(),
        androidx.compose.animation.core.spring(dampingRatio = 0.5f, stiffness = 300f), label = "counter")
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    Canvas(modifier.size(48.dp)) {
        val c = center
        val outer = size.minDimension / 2
        val win = outer * 0.72f
        // the bezel: turned chrome, light from above
        drawCircle(Brush.linearGradient(listOf(Color(0xFFE8E8E4), Color(0xFF6E6E6A), Color(0xFFBDBDB8), Color(0xFF3A3A38)),
            start = Offset(c.x, 0f), end = Offset(c.x, size.height)), outer, c)
        drawCircle(Color(0xFF151514), outer * 0.86f, c)
        // the dial, seen through the window
        val window = androidx.compose.ui.graphics.Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, win)) }
        clipPath(window) {
            drawCircle(Color(0xFF0C0C0B), win, c)
            val R = win * 2.6f                                  // the dial is much bigger than the window
            val hub = Offset(c.x, c.y + R - win * 0.15f)
            val step = 15f                                      // degrees per frame
            for (f in -2..total + 2) {
                val ang = (f - turn) * step
                if (kotlin.math.abs(ang) > 50f) continue
                rotate(ang, pivot = hub) {
                    val top = Offset(hub.x, hub.y - R)
                    val col = when { f < 0 -> Palette.led; f == total -> Palette.orange; f > total -> Color(0xFF6A6A66); else -> Palette.engraving }
                    when {
                        f == -2 -> { val t = measurer.measure("S", androidx.compose.ui.text.TextStyle(color = col, fontSize = (win * 0.62f).toSp(), fontFamily = Futuraish, fontWeight = FontWeight.SemiBold))
                            drawText(t, topLeft = Offset(top.x - t.size.width / 2f, top.y - t.size.height / 2f)) }
                        f < 0 || f % 2 == 1 -> drawCircle(col, win * 0.07f, top)
                        else -> { val t = measurer.measure("$f", androidx.compose.ui.text.TextStyle(color = col, fontSize = (win * 0.62f).toSp(), fontFamily = Futuraish, fontWeight = FontWeight.SemiBold))
                            drawText(t, topLeft = Offset(top.x - t.size.width / 2f, top.y - t.size.height / 2f)) }
                    }
                    // the dial's graduations, below the figures
                    drawLine(col.copy(alpha = 0.5f), Offset(top.x, top.y + win * 0.48f), Offset(top.x, top.y + win * 0.62f), win * 0.04f)
                }
            }
            // the window's depth: the dial falls into shadow under its rim
            drawCircle(Brush.radialGradient(0.6f to Color.Transparent, 1f to Color(0xCC000000), center = c, radius = win), win, c)
            // the red index, printed on the inside of the window's rim
            val idx = androidx.compose.ui.graphics.Path().apply {
                moveTo(c.x - win * 0.12f, c.y - win * 0.98f); lineTo(c.x + win * 0.12f, c.y - win * 0.98f); lineTo(c.x, c.y - win * 0.78f); close()
            }
            drawPath(idx, Palette.led)
            // the glass: a domed highlight across the top, a faint glint at the foot
            drawOval(Brush.verticalGradient(listOf(Color(0x40FFFFFF), Color(0x00FFFFFF)), startY = c.y - win, endY = c.y),
                topLeft = Offset(c.x - win * 0.72f, c.y - win * 0.92f), size = androidx.compose.ui.geometry.Size(win * 1.44f, win * 0.8f))
            drawArc(Color(0x33FFFFFF), 40f, 100f, false, topLeft = Offset(c.x - win * 0.82f, c.y - win * 0.82f),
                size = androidx.compose.ui.geometry.Size(win * 1.64f, win * 1.64f), style = Stroke(win * 0.05f))
        }
        // the window's rim
        drawCircle(Color(0xFF050505), win, c, style = Stroke(outer * 0.06f))
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
    Box(Modifier.size(92.dp).clip(CircleShape).background(Brush.radialGradient(listOf(Color(0xFF1A1918), Color(0xFF080808)))),
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

/**
 * The parallax switch on the deck: a small lever, its lamp glowing green when on. On, each
 * shot is followed by the guided left and right frames (ParallaxCapture).
 */
@Composable
private fun ParallaxSwitch(on: Boolean, onToggle: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onToggle).padding(4.dp)) {
        Canvas(Modifier.size(10.dp)) {
            // a little green lamp, glowing when on (the meter tab's green)
            val lamp = Color(0xFF73F2B3)
            if (on) drawCircle(Brush.radialGradient(listOf(lamp.copy(alpha = 0.6f), Color.Transparent), radius = size.minDimension * 1.1f), size.minDimension * 1.1f)
            drawCircle(if (on) lamp else Color(0xFF1F2A24), size.minDimension / 2.6f)
            if (on) drawCircle(Color.White.copy(alpha = 0.6f), size.minDimension / 7f, center + Offset(-size.minDimension / 12f, -size.minDimension / 12f))
        }
        Spacer(Modifier.height(4.dp))
        // the lever: up for on
        Box(Modifier.size(width = 22.dp, height = 34.dp).clip(RoundedCornerShape(11.dp)).background(Color(0xFF0A0A09))
            .border(1.dp, Palette.panelHi, RoundedCornerShape(11.dp))) {
            Box(Modifier.align(if (on) Alignment.TopCenter else Alignment.BottomCenter).padding(3.dp).size(16.dp).clip(CircleShape)
                .background(Brush.radialGradient(listOf(Palette.chromeHi, Palette.chromeLo))))
        }
        Spacer(Modifier.height(4.dp))
        Text("PARALLAX", color = if (on) Palette.cream else Palette.dim, fontSize = 7.sp, letterSpacing = 1.sp)
    }
}

/**
 * The finder during parallax capture: ARCore's view (in a TextureView, so the eyepiece
 * still bends and blooms it), the ghost camera's wireframes drawn over it, and the
 * hint in the finder's own lettering. ✕ gives up (the frame keeps single-shot depth).
 */
@Composable
private fun ParallaxFinder(pc: app.relens.parallax.ParallaxCapture, modifier: Modifier) {
    val hint by pc.hint.collectAsState()
    val phase by pc.phase.collectAsState()
    val held by pc.held.collectAsState()
    Box(modifier.background(Color.Black)) {
        androidx.compose.ui.viewinterop.AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx ->
            android.view.TextureView(ctx).apply { surfaceTextureListener = pc.loop }
        })
        val step = when (phase) {
            app.relens.parallax.ParallaxCapture.Phase.LEFT -> "1 / 2 · LEFT"
            app.relens.parallax.ParallaxCapture.Phase.RIGHT -> "2 / 2 · RIGHT"
            app.relens.parallax.ParallaxCapture.Phase.DONE -> "PARALLAX ✓"
            else -> "PARALLAX"
        }
        Text(step, color = Color(0xFFFFEBCC), fontSize = 12.sp, letterSpacing = 2.sp,
            modifier = Modifier.align(Alignment.TopStart).padding(12.dp))
        Text("✕", color = Color(0xFFFFEBCC), fontSize = 18.sp,
            modifier = Modifier.align(Alignment.TopEnd).clickable { pc.cancel() }.padding(12.dp))
        // The hint upright for the photographer, however the phone is held.
        Box(Modifier.fillMaxSize().padding(22.dp), contentAlignment = Alignment.Center) {
            Text(hint, color = Color(0xFFFFEBCC), fontSize = 15.sp,
                modifier = Modifier.align(when (held) { 90 -> Alignment.CenterEnd; 270 -> Alignment.CenterStart; 180 -> Alignment.TopCenter; else -> Alignment.BottomCenter })
                    .graphicsLayer { rotationZ = -held.toFloat() })
        }
    }
}

/**
 * Until the depth models are on the phone: what they are, how big, and a button to fetch them
 * (once, from the project's GitHub release); then a progress bar; gone when they're in.
 */
@Composable
private fun ModelsBanner(body: Body) {
    val st by body.models.state.collectAsState()
    if (st is app.relens.engine.Models.State.Ready) return
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(6.dp)).background(Palette.panel)
        .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Text("DEPTH MODELS", color = Palette.amber, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            val mb = body.models.totalBytes / 1_000_000
            when (val s = st) {
                is app.relens.engine.Models.State.Downloading -> {
                    Text("downloading… %d of %d MB".format(s.done / 1_000_000, s.total / 1_000_000), color = Palette.dim, fontSize = 10.sp)
                    androidx.compose.material3.LinearProgressIndicator(progress = { s.done / maxOf(s.total, 1).toFloat() },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp), color = Palette.amber, trackColor = Palette.panelHi)
                }
                is app.relens.engine.Models.State.Failed -> Text("didn't finish (${s.why}): try again", color = Palette.led, fontSize = 10.sp)
                else -> Text("needed to develop prints · $mb MB, once, from GitHub", color = Palette.dim, fontSize = 10.sp)
            }
        }
        if (st !is app.relens.engine.Models.State.Downloading) {
            Spacer(Modifier.width(10.dp))
            Text("DOWNLOAD", color = Palette.leather, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Palette.amber).clickable { body.downloadModels() }
                    .padding(horizontal = 12.dp, vertical = 6.dp))
        }
    }
}
