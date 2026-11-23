package app.relens.ui

import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.nativeCanvas

import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache

import androidx.compose.ui.layout.layout
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
    BrassLightSensor()
    val framing by body.rig.framing.collectAsState()
    val rigError by body.rig.error.collectAsState()
    val raw = framing?.camera?.raw == true
    val lens = body.lens
    body.onParallaxFire = { haptics.sounds.fire(1f / 60) }   // ARCore's frames: a quick release
    // the live depth map (split prism, live blur), while the camera screen is up
    val liveDepth by body.liveDepth.collectAsState()
    // the prism's halves glide to where the (smoothed) defocus puts them, rather than snapping
    val prismTarget = body.centreD?.let { c -> body.finderFocusD?.let { it - c } } ?: 0f
    val prismShift by animateFloatAsState(prismTarget, androidx.compose.animation.core.spring(dampingRatio = 0.9f, stiffness = 120f), label = "prism")
    androidx.compose.runtime.DisposableEffect(Unit) { body.startLiveDepth(); body.startGlow(); onDispose { body.stopLiveDepth(); body.stopGlow() } }
    // Leaving the app hands the camera back (Android takes it from background apps anyway);
    // coming back opens it again, with nothing left over from the time away.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, ev ->
            when (ev) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> if (body.parallax == null && !body.busy) body.rig.pause()
                androidx.lifecycle.Lifecycle.Event.ON_START -> body.rig.resume()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
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
    // The mirror's swing, as seen through the finder: the dark edge sweeps up over the glass as it
    // flips up, and back down as it drops, rebounding once off its stop; each slap shakes the
    // view a little (a damped wobble), as the whole camera does.
    val mirror = remember { androidx.compose.animation.core.Animatable(0f) }
    val shake = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(mirrorUp) {
        if (mirrorUp) {
            launch { shake.snapTo(0f); shake.animateTo(0f, androidx.compose.animation.core.keyframes {
                durationMillis = 160; -1.6f at 25; 1.1f at 60; -0.5f at 100; 0.2f at 130 }) }
            mirror.animateTo(1f, androidx.compose.animation.core.tween(60, easing = androidx.compose.animation.core.FastOutLinearInEasing))
        } else if (mirror.value > 0f) {
            launch { shake.snapTo(0f); shake.animateTo(0f, androidx.compose.animation.core.keyframes {
                durationMillis = 260; 2.4f at 70; -1.7f at 115; 1.0f at 160; -0.45f at 205; 0.15f at 235 }) }
            mirror.animateTo(0f, androidx.compose.animation.core.tween(80, easing = androidx.compose.animation.core.FastOutLinearInEasing))
            mirror.animateTo(0.16f, androidx.compose.animation.core.tween(30, easing = androidx.compose.animation.core.LinearOutSlowInEasing))
            mirror.animateTo(0f, androidx.compose.animation.core.tween(55, easing = androidx.compose.animation.core.FastOutLinearInEasing))
        }
    }

    // The ground glass, in screen pixels: the eyepiece effect over the whole body works round it.
    var glass by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    // (the eyepiece shader works on the finder's part of the body only, from the top plate to the
    // readout: the rings and the deck below aren't redrawn through it with every camera frame)
    val ringM = body.focusM
    // While the mirror is up (the frame and the rangefinder sweep), the ring stays on the
    // subject: the sweep's hops between points aren't the photographer's focus.
    val liveM = liveD?.let { if (it > 0.01f) 1f / it else Float.POSITIVE_INFINITY }
    var heldM by remember { mutableStateOf<Float?>(null) }
    if (!(body.busy || body.sweeping)) heldM = liveM
    val shownM = ringM ?: heldM
    var eyeTop by remember { mutableStateOf(Offset.Zero) }
    Column(Modifier.portraitBody().fillMaxSize().background(Palette.leather)
        .statusBarsPadding().navigationBarsPadding()) {
      Box(Modifier.weight(1f).onGloballyPositioned { eyeTop = it.boundsInRoot().topLeft }) {
      Column(Modifier.fillMaxSize().eyepiece(glass?.translate(-eyeTop), wall = 12.dp)) {
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

        }

        // ---- the finder: the lens's own field of view, 3:2 like 35 mm film (2:3 held upright) ----
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
          // The eyepiece: a dark surround round the picture, seen through a little pincushion,
          // with light from the picture's edge spilling into it (Finder.kt).
          Box {
            Box(Modifier.aspectRatio(2f / 3f).onGloballyPositioned { glass = it.boundsInRoot() }
                .graphicsLayer { translationY = shake.value * density }
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
                    // the lens's blur, live: depth of field from the live depth map, and a tilt's plane
                    // (the depth's blur only while the DOF lever is held: live, it lags and jumps)
                    .lensBlur(liveDepth?.takeIf { body.dofPreview }, framing?.crop ?: 1f,
                        if (lens.movement is Movement.Tilt) Movements.tiltDioptres(lens, floatArrayOf(body.tiltX, body.tiltY)) else floatArrayOf(0f, 0f),
                        body.focusX, body.focusY, body.finderFocusD, lens.focalMM, body.fNumber)
                    // the split-image spot and microprism collar at the centre
                    .splitPrism(prismShift, lens.focalMM, lens.maxAperture))
                GroundGlass(Modifier.fillMaxSize())
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
                if (mirror.value > 0f) MirrorShade(mirror.value, Modifier.fillMaxSize())
                body.parallax?.let { pc -> ParallaxFinder(pc, body, Modifier.fillMaxSize()) }
                // HOLD STEADY slaps into place with the mirror: in a touch large, landing with a bounce
                val holdOn = mirrorUp && (body.busy || body.sweeping) && body.parallax == null
                val slap = remember { androidx.compose.animation.core.Animatable(0f) }
                LaunchedEffect(holdOn) {
                    if (holdOn) { slap.snapTo(0f); slap.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.42f, stiffness = 900f)) }
                    else slap.snapTo(0f)
                }
                if (holdOn) Text("HOLD STEADY", color = Palette.led, fontSize = 17.sp, letterSpacing = 3.sp,
                    fontWeight = FontWeight.Bold, fontFamily = Futuraish,
                    modifier = Modifier.align(Alignment.Center).graphicsLayer {
                        val k = slap.value
                        val sc = 1.35f - 0.35f * k
                        scaleX = sc; scaleY = sc
                        alpha = (k * 3f).coerceIn(0f, 1f)
                        translationY = shake.value * density * 1.5f
                    })
            }
          }
        }

        // ---- LED readout under the finder ----
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            // the readouts show what the rings are set to: the nearest engraved value, never in between
            val stops = lens.apertureStops
            Led(fLabel(stops[body.aperturePos.roundToInt().coerceIn(0, stops.size - 1)]), cells = 3, prefix = "F")
            shutterSpeeds[body.shutterPos.roundToInt().coerceIn(0, shutterSpeeds.size - 1)].let { (label, t) ->
                Led(label, cells = 4, suffix = if (t >= 0.5f) "\"" else "") }
            // the focus ring turns smoothly (no clicks): its distance is shown as it is
            Led(when { shownM == null -> "---"; shownM.isInfinite() -> "∞"; shownM < 10f -> "%.2f".format(shownM); shownM < 100f -> "%.1f".format(shownM); else -> "%.0f".format(shownM) }, cells = 3, suffix = "m",
                // long-press the distance: collimate this camera's focus readings
                modifier = Modifier.pointerInput(Unit) { detectTapGestures(onLongPress = {
                    body.calibCameras().firstOrNull { it.camera.id == body.rig.currentCameraId }?.let { body.startCalibration(it) }
                    haptics.clunk() }) })
            when (lens.movement) {
                is Movement.Tilt -> Led("%.0f".format(kotlin.math.hypot(body.tiltX, body.tiltY)), cells = 2, prefix = "T", suffix = "°",
                    modifier = Modifier.clickable { body.centreMovements(); haptics.click() })
                is Movement.Shift -> Led("%+.0f".format(body.shiftY), cells = 3, prefix = "S",
                    modifier = Modifier.clickable { body.centreMovements(); haptics.click() })
                Movement.None -> Led(if (ringM == null) "AF" else "MF", cells = 2)
            }
            Led("${body.framesLeft}", cells = 2)
        }

      }
      FinderGlowLayer(glass?.translate(-eyeTop), Modifier.matchParentSize(), light = 1f - mirror.value, shakeY = shake.value * androidx.compose.ui.platform.LocalDensity.current.density)
      }
        body.calib?.let { cal -> CalibrationBanner(body, cal, haptics) }
        if (body.collimating || body.collimationNote.isNotEmpty() && body.collimation.isNotEmpty()) Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text((if (body.collimating) "COLLIMATE THE ${(body.rig.framing.value?.cameraRole ?: "").uppercase()} CAMERA (${body.rig.currentCameraId ?: ""}) · " else "") + body.collimationNote,
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
        // how much light the finder casts down on the rings: the scene's brightness through it
        val finderLight = sceneEV?.let { (((it - 3f) / 9f).coerceIn(0.15f, 1f) * 10f).roundToInt() / 10f } ?: 0.3f   // in steps: the rings don't redraw at every flicker
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
            finderLight = finderLight,
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
            finderLight = finderLight * 0.55f,
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
            finderLight = finderLight * 0.30f,
        )

        // ---- the deck: a brushed steel top plate ----
        // left, the big film winder; then the parallax slide; in the middle a waist-level viewer
        // onto the light table; on the right the frame counter, the shutter release and DOF.
        val steel = remember { if (android.os.Build.VERSION.SDK_INT >= 33) android.graphics.RuntimeShader(BRUSHED_STEEL_AGSL) else null }
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().height(132.dp)
            .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }   // cached: not redrawn with every camera frame
            .drawBehind {
                if (steel != null) {
                    Chassis.bind(steel)
                    steel.setFloatUniform("iResolution", size.width, size.height)
                    steel.setFloatUniform("uDens", density)
                    drawRect(androidx.compose.ui.graphics.ShaderBrush(steel))
                } else drawRect(Brush.verticalGradient(listOf(Color(0xFF9A9EA3), Color(0xFF6C7075))))
                drawRect(Color.Black.copy(alpha = 0.7f), size = androidx.compose.ui.geometry.Size(size.width, 2f))
                drawRect(Color.White.copy(alpha = 0.25f), topLeft = Offset(0f, 2f), size = androidx.compose.ui.geometry.Size(size.width, 1f))
            }) {
            val W = maxWidth; val H = maxHeight
            @Composable fun at(cx: androidx.compose.ui.unit.Dp, cy: androidx.compose.ui.unit.Dp, w: androidx.compose.ui.unit.Dp, h: androidx.compose.ui.unit.Dp, content: @Composable () -> Unit) {
                Box(Modifier.offset(x = cx - w / 2, y = cy - h / 2).size(w, h), contentAlignment = Alignment.Center) { content() }
            }
            val mid = H * 0.52f
            // the winder
            at(W * 0.125f, mid, 92.dp, 92.dp) { FilmRewindKnob(body.counterShows) { openFilm(); haptics.click() } }
            // the parallax slide, its label engraved above
            if (body.parallaxAvailable) {
                // the slide's bottom at the DOF button's (mid + 27): LED 10 + gap 5 + slide 24, centred
                at(W * 0.335f, mid + 7.5.dp, 60.dp, 48.dp) { ParallaxSlide(body.parallaxOn) { body.toggleParallax(); haptics.click() } }
                at(W * 0.335f, mid + 42.dp, 60.dp, 16.dp) { DeckLabel("PRLX") }      // on DOF's line
            }
            // the waist-level viewer onto the light table
            at(W * 0.50f, mid, 70.dp, 100.dp) { WaistLevelViewer(body.darkroom.firstOrNull()) { openDarkroom() } }
            // the right cluster: counter and DOF stacked, the shutter release beside them
            at(W * 0.69f, mid - 28.dp, 46.dp, 46.dp) { FrameCounter(body.counterShows, body.stock.exposures, Modifier.clickable { openFilm() }) }
            at(W * 0.69f, mid + 18.dp, 34.dp, 34.dp) { DofButton(body.dofPreview) { held -> body.dofPreview = held; if (held) haptics.tick() } }
            at(W * 0.69f, mid + 42.dp, 60.dp, 16.dp) { DeckLabel("DOF") }
            // the film plane mark, engraved by the winder
            at(W * 0.125f + 42.dp, mid + 46.dp, 22.dp, 14.dp) { FilmPlaneMark() }    // toward the back, where the film lies
            at(W * 0.865f, mid - 14.dp, 92.dp, 92.dp) {
                ShutterRelease(busy = body.busy) {
                    if (!body.busy && body.framesLeft > 0) { haptics.shutter(body.shutterSeconds); mirrorUp = true }
                    body.shoot(onShot = { haptics.advance() })
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
                body.finderView = this
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
    // the needle follows the reading, but not its every flicker (a moving needle redraws the meter)
    var target by remember { mutableStateOf(meterPos) }
    if (kotlin.math.abs(meterPos - target) > 0.12f) target = meterPos
    val needle by animateFloatAsState(target.coerceIn(-1.5f, shutterSpeeds.size + 0.5f),
        androidx.compose.animation.core.spring(dampingRatio = 0.71f, stiffness = 60f), label = "needle")
    // only the strip's own band of the finder is drawn and shaded (the eyepiece shader is costly per pixel)
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxSize()) {
    val finderW = constraints.maxWidth.toFloat(); val finderH = constraints.maxHeight.toFloat()
    val band = Modifier.fillMaxHeight().width(88.dp)
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    // A printed scale strip at the frame's left edge, as in the classic finders: it comes in from
    // under the frame's border (the part beyond is in the dark), cream with dark figures, the slow
    // speeds and the end caps red; the needle swings in from outside. Seen through the eyepiece,
    // not on the focusing screen, so a little soft: the whole meter is gently out of focus.
    val ca = remember { if (android.os.Build.VERSION.SDK_INT >= 33) android.graphics.RuntimeShader(EYEPIECE_CA) else null }
    // frosted glass: fine light and dark specks
    val frost = remember {
        val n = 128; val rng = kotlin.random.Random(17)
        val px = IntArray(n * n) {
            val v = rng.nextFloat()
            val a = (kotlin.math.abs(v - 0.5f) * 0.32f)
            ((a * 255).toInt() shl 24) or (if (v > 0.5f) 0xFFFFFF else 0x5A5248)
        }
        android.graphics.Bitmap.createBitmap(px, n, n, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    Canvas(band.graphicsLayer {
        when {
            // the eyepiece: sharp in the middle of the finder, softening toward its corners, with a
            // little lateral colour growing the same way
            android.os.Build.VERSION.SDK_INT >= 33 && ca != null -> {
                ca.setFloatUniform("size", finderW, finderH)
                ca.setFloatUniform("maxBlur", 1.8f * density)
                ca.setFloatUniform("maxShift", 0.7f * density)
                ca.setFloatUniform("wobble", 0f)
                renderEffect = android.graphics.RenderEffect.createRuntimeShaderEffect(ca, "image").asComposeRenderEffect()
            }
            android.os.Build.VERSION.SDK_INT >= 31 -> renderEffect = androidx.compose.ui.graphics.BlurEffect(0.5f * density, 0.5f * density)
        }
    }) {
        val u = 8.dp.toPx()
        val ink = Color(0xFF2E2A26); val red = Color(0xFFD83A22); val paper = Color(0xFFEFE6D4)
        val stripH = finderH * 0.62f
        val strip = androidx.compose.ui.geometry.Rect(Offset(-1.2f * u, (finderH - stripH) / 2), androidx.compose.ui.geometry.Size(5.0f * u, stripH))
        val cap = 2.0f * u; val n = shutterSpeeds.size
        val step = (strip.height - 2 * cap) / n
        fun y(i: Float) = strip.top + cap + (n - 1 - i + 0.5f) * step         // fast at the top
        val corner = androidx.compose.ui.geometry.CornerRadius(0.35f * u)
        // the strip: frosted glass, the finder's light coming through it (the scene faintly
        // behind, a fine frost grain, a little brighter where the light comes from), its caps red
        drawRoundRect(Brush.verticalGradient(listOf(paper.copy(alpha = 0.62f), paper.copy(alpha = 0.50f)), startY = strip.top, endY = strip.bottom),
            strip.topLeft, strip.size, corner)
        drawRoundRect(androidx.compose.ui.graphics.ShaderBrush(androidx.compose.ui.graphics.ImageShader(frost,
            androidx.compose.ui.graphics.TileMode.Repeated, androidx.compose.ui.graphics.TileMode.Repeated)), strip.topLeft, strip.size, corner)
        val capSize = androidx.compose.ui.geometry.Size(strip.width, cap * 0.8f)
        drawRoundRect(red, strip.topLeft, capSize, corner)
        drawRoundRect(red, Offset(strip.left, strip.bottom - cap * 0.8f), capSize, corner)
        drawRect(red, Offset(strip.left, strip.top + cap * 0.4f), androidx.compose.ui.geometry.Size(strip.width, cap * 0.4f))
        drawRect(red, Offset(strip.left, strip.bottom - cap * 0.8f), androidx.compose.ui.geometry.Size(strip.width, cap * 0.4f))
        val m = measurer.measure("M", androidx.compose.ui.text.TextStyle(color = paper, fontSize = (1.0f * u).toSp(),
            fontFamily = Futuraish, fontWeight = FontWeight.SemiBold))
        drawText(m, topLeft = Offset(strip.right - m.size.width - 0.5f * u, strip.bottom - cap * 0.4f - m.size.height / 2f))
        val fontPx = minOf(step * 0.62f, strip.width * 0.24f)
        val pivot = Offset(strip.left - 9f * u, strip.center.y)
        for ((i, sp) in shutterSpeeds.withIndex()) {
            val c = if (sp.second >= 1f / 30) red else ink
            val yy = y(i.toFloat())
            val t = measurer.measure(sp.first, androidx.compose.ui.text.TextStyle(color = c, fontSize = fontPx.toSp(),
                fontFamily = Futuraish, fontWeight = FontWeight.Medium))
            drawText(t, topLeft = Offset(strip.right - t.size.width - 0.45f * u, yy - t.size.height / 2f))
        }
        // the needle: in from the dark beyond the frame, aimed through the reading's tick
        val aim = Offset(1.0f * u, y(needle))
        val reach = (1.15f * u - pivot.x) / (aim.x - pivot.x)
        val tip = pivot + (aim - pivot) * reach
        drawLine(Color(0xFFC81810), pivot, tip, maxOf(1.6f, 0.22f * u), cap = androidx.compose.ui.graphics.StrokeCap.Round)
        // the frame's border beyond: the strip vanishes under it, softly (it's out of focus too)
        drawRect(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.55f), 1f to Color.Transparent, startX = 0f, endX = 1.1f * u),
            Offset(0f, strip.top), androidx.compose.ui.geometry.Size(1.1f * u, strip.height))
    }
    // The set speed: green light reflected into the finder, like a head-up display. Added light,
    // soft-edged and glowing, a little see-through, with the faint second image a reflection off
    // the far side of the glass leaves.
    val hudBlur = remember { if (android.os.Build.VERSION.SDK_INT >= 33) android.graphics.RuntimeShader(EYEPIECE_CA) else null }
    Canvas(band.graphicsLayer {
        compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
        blendMode = androidx.compose.ui.graphics.BlendMode.Plus
        // its focus wanders as it travels along the glass: softer toward the ends, and unevenly so
        if (android.os.Build.VERSION.SDK_INT >= 33 && hudBlur != null) {
            hudBlur.setFloatUniform("size", finderW, finderH)
            hudBlur.setFloatUniform("maxBlur", 2.6f * density)
            hudBlur.setFloatUniform("maxShift", 0.5f * density)
            hudBlur.setFloatUniform("wobble", 0.85f)
            renderEffect = android.graphics.RenderEffect.createRuntimeShaderEffect(hudBlur, "image").asComposeRenderEffect()
        }
    }) {
        val u = 8.dp.toPx()
        val stripH = finderH * 0.62f
        val top = (finderH - stripH) / 2
        val cap = 2.0f * u; val n = shutterSpeeds.size
        val step = (stripH - 2 * cap) / n
        val ty = top + cap + (n - 1 - setPos + 0.5f) * step
        val x0 = -1.2f * u + 5.0f * u + 0.15f * u
        fun tri(dx: Float, dy: Float, grow: Float) = androidx.compose.ui.graphics.Path().apply {
            moveTo(x0 + dx - grow, ty + dy - 0.40f * step - grow); lineTo(x0 + dx + 1.15f * u + grow * 1.4f, ty + dy)
            lineTo(x0 + dx - grow, ty + dy + 0.40f * step + grow); close()
        }
        val hud = Color(0xFF14E05A)                       // a pure green: added light keeps its colour over bright scenes
        val cv = drawContext.canvas.nativeCanvas
        val glow = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(120, 0x08, 0xD0, 0x40)
            maskFilter = android.graphics.BlurMaskFilter(0.9f * u, android.graphics.BlurMaskFilter.Blur.NORMAL)
        }
        cv.drawPath(tri(0f, 0f, 0.15f * u).asAndroidPath(), glow)                       // the bloom round it
        drawPath(tri(0.35f * u, -0.22f * u, 0f), hud.copy(alpha = 0.16f))                 // the ghost reflection
        drawPath(tri(0f, 0f, 0f), hud.copy(alpha = 0.78f))                                // the reflected light
        drawPath(tri(0.12f * u, 0f, -0.18f * u), Color(0xFF60FF90).copy(alpha = 0.30f))   // its brighter core
    }
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
    // In step with the film advance recordings: the lever's stroke starts about half a second in
    // and the ratchet lands at about 1.1 s; the dial turns through the stroke and settles on it.
    val turn by animateFloatAsState(shot.toFloat(),
        androidx.compose.animation.core.tween(durationMillis = 600, delayMillis = 500,
            easing = androidx.compose.animation.core.FastOutSlowInEasing), label = "counter")
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val glass = remember { if (android.os.Build.VERSION.SDK_INT >= 33) android.graphics.RuntimeShader(GLASS_WINDOW_AGSL) else null }
    Box(modifier.fillMaxSize()) {
    Canvas(Modifier.fillMaxSize()) {
        val c = center
        val outer = size.minDimension / 2
        val win = outer * 0.72f
        // the bezel: turned brass (as the shutter release), light from above
        val bl = BrassLight.dir
        val bn = maxOf(kotlin.math.hypot(bl.x, bl.y), 0.2f)
        drawCircle(Brush.linearGradient(listOf(Color(0xFFE8ECF0), Color(0xFF6E7378), Color(0xFFBFC4C9), Color(0xFF3A3D40)),
            start = c + Offset(bl.x / bn * outer, bl.y / bn * outer), end = c - Offset(bl.x / bn * outer, bl.y / bn * outer)), outer, c)
        var rr = outer * 0.87f
        while (rr < outer * 0.99f) { drawCircle(Color.White.copy(alpha = 0.06f), rr, c, style = androidx.compose.ui.graphics.drawscope.Stroke(0.6f)); rr += maxOf(1.1f, outer * 0.03f) }
        drawCircle(Color(0xFF3A2A12).copy(alpha = 0.8f), outer, c, style = androidx.compose.ui.graphics.drawscope.Stroke(outer * 0.035f))
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
    // the polished brass bezel and the curved crystal over the dial, in the shared environment
    // (the window is the bezel's inner 0.80; the dial's own window sits at 0.72 inside it)
    if (glass != null) Canvas(Modifier.fillMaxSize()) {
        Chassis.bind(glass)
        glass.setFloatUniform("iResolution", size.width, size.height)
        glass.setFloatUniform("uPx", 2f / size.height)
        glass.setFloatUniform("uRound", 1f)
        glass.setFloatUniform("uAr", 0f)
        glass.setFloatUniform("uBrass", 1f)
        glass.setFloatUniform("uKnurl", 0f)
        drawRect(androidx.compose.ui.graphics.ShaderBrush(glass))
    }
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

/** A brass shutter release, lathe-turned, in a dark collar: it sinks as it is pressed. */
@Composable
private fun ShutterRelease(busy: Boolean, onRelease: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.9f else 1f, label = "shutter")
    Box(Modifier.size(92.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(64.dp)) { deckShadow() }
        val press by animateFloatAsState(if (pressed) 1f else 0f, label = "press")
        val shader = remember { if (android.os.Build.VERSION.SDK_INT >= 33) android.graphics.RuntimeShader(SOFT_RELEASE_AGSL) else null }
        Canvas(Modifier.size(64.dp).scale(s).graphicsLayer { translationY = press * 1.5f * density }.clip(CircleShape)
            .clickable(interactionSource = src, indication = null, onClick = onRelease)) {
            if (shader != null) {
                // mirror-polished brass, lit by the shared studio (GPU)
                Chassis.bind(shader)
                shader.setFloatUniform("iResolution", size.width, size.height)
                shader.setFloatUniform("uPx", 2f / size.minDimension)
                shader.setFloatUniform("uPressed", press)
                shader.setFloatUniform("uSocket", 1f)
                shader.setFloatUniform("uEnamel", 0f)
                drawRect(androidx.compose.ui.graphics.ShaderBrush(shader))
            } else with(Brass) { release(center, size.minDimension / 2, pressed = pressed) }
            if (busy) drawCircle(Palette.led.copy(alpha = 0.8f), size.minDimension / 2 - 1.dp.toPx(), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
        }
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


/**
 * The finder during parallax capture: ARCore's view (in a TextureView, so the eyepiece
 * still bends and blooms it), the ghost camera's wireframes drawn over it, and the
 * hint in the finder's own lettering. ✕ gives up (the frame keeps single-shot depth).
 */
@Composable
private fun ParallaxFinder(pc: app.relens.parallax.ParallaxCapture, body: Body, modifier: Modifier) {
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
            if (!body.parallaxIntroSeen) Box(Modifier.align(Alignment.Center).padding(20.dp).clip(RoundedCornerShape(8.dp))
            .background(Color(0xE6141312)).clickable { body.dismissParallaxIntro() }.padding(16.dp)) {
            Text("PARALLAX\n\nGlide the camera to the ghost on the left, then on the right: each frame is taken by itself " +
                "when you line up. The three views measure the scene's depth, so the blur falls where it should.\n\n" +
                "Shot on autofocus (tap to focus), they also teach the camera's focus: once the switch reads FOCUS ✓ you can " +
                "turn parallax off for moving subjects and quick shots.\n\ntap to close",
                color = Palette.cream, fontSize = 12.sp, lineHeight = 16.sp)
        }
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

/**
 * The camera body keeps a phone's proportions: on a wide screen (a tablet, an unfolded
 * foldable, a landscape window: Android 16 lets those rotate a portrait app) it stands centred
 * at the screen's height rather than stretching sideways.
 */
private fun Modifier.portraitBody() = this.then(Modifier.layout { m, c ->
    val w = if (c.hasBoundedHeight && c.maxWidth > c.maxHeight * 0.62f) (c.maxHeight * 0.56f).toInt() else c.maxWidth
    val p = m.measure(c.copy(minWidth = w, maxWidth = w))
    layout(c.maxWidth, p.height) { p.place((c.maxWidth - w) / 2, 0) }
})

/**
 * The depth-of-field preview lever: held, the finder shows the lens's blur at the set aperture
 * (from the live depth map); let go, the clean live view. As on an SLR, where the finder is
 * wide open until the lever stops the lens down.
 */



/**
 * The focus calibration under way, under the finder: the step, what to do, the measured
 * distance typed in (metres), SET; DONE or ✕ goes back to the lens that was on.
 */
@Composable
private fun CalibrationBanner(body: Body, cal: Body.Calib, haptics: Haptics) {
    androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
        .clip(RoundedCornerShape(6.dp)).background(Color(0xE6141210)).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("CALIBRATE THE ${cal.role.uppercase()} CAMERA · " + when (cal.step) { 0 -> "STEP 1 OF 3"; 1 -> "STEP 2 OF 3"; 2 -> "STEP 3 OF 3"; else -> "DONE" },
                color = Palette.amber, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
            Text(if (cal.step == 3) "DONE" else "✕", color = if (cal.step == 3) Palette.leather else Palette.dim, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(if (cal.step == 3) Palette.amber else Color.Transparent)
                    .clickable { body.endCalibration(); haptics.click() }.padding(horizontal = 10.dp, vertical = 4.dp))
        }
        Text(cal.note, color = Palette.cream, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
        if (cal.step in 1..2) Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.text.BasicTextField(cal.distance, { cal.distance = it.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' }.take(6) },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = Palette.led, fontSize = 20.sp, fontWeight = FontWeight.Bold),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Palette.led),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                modifier = Modifier.width(110.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF050505)).padding(horizontal = 10.dp, vertical = 6.dp))
            Text("  m measured", color = Palette.dim, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text("SET", color = Palette.leather, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Palette.amber)
                    .clickable { body.calibSet(); haptics.click() }.padding(horizontal = 14.dp, vertical = 8.dp))
        }
    }
}


/**
 * The mirror's edge crossing the focusing screen: dark from the bottom up to [m] of the way,
 * with a soft leading edge (it moves faster than the eye can follow).
 */
@Composable
private fun MirrorShade(m: Float, modifier: Modifier) {
    Canvas(modifier) {
        val edge = size.height * (1f - m)
        val feather = size.height * 0.10f
        drawRect(Color.Black, topLeft = Offset(0f, edge), size = androidx.compose.ui.geometry.Size(size.width, size.height - edge))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black), startY = edge - feather, endY = edge),
            topLeft = Offset(0f, edge - feather), size = androidx.compose.ui.geometry.Size(size.width, feather))
    }
}

/**
 * The focusing screen itself: a fine, fixed matte grain (the ground surface), the brighter
 * centre and dimmer edges a screen has, and the faint rings of its Fresnel lens.
 */
@Composable
private fun GroundGlass(modifier: Modifier) {
    val grain = remember {
        val n = 256; val rng = kotlin.random.Random(41)
        val px = IntArray(n * n) {
            val v = rng.nextFloat()
            // most of the surface a hair light or dark, now and then a brighter grain catching the light
            val a = (if (v > 0.985f) 0.16f else kotlin.math.abs(v - 0.5f) * 0.11f)
            val c = if (v > 0.5f) 0xFFFFFF else 0x000000
            ((a * 255).toInt() shl 24) or c
        }
        android.graphics.Bitmap.createBitmap(px, n, n, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    // drawn once per size into a bitmap and reused (the rings are dozens of circles)
    androidx.compose.foundation.layout.Spacer(modifier.drawWithCache {
        val w = size.width.toInt().coerceAtLeast(1); val h = size.height.toInt().coerceAtLeast(1)
        val img = androidx.compose.ui.graphics.ImageBitmap(w, h)
        val cv = androidx.compose.ui.graphics.Canvas(img)
        androidx.compose.ui.graphics.drawscope.CanvasDrawScope().draw(this, layoutDirection, cv, size) {
            drawRect(androidx.compose.ui.graphics.ShaderBrush(androidx.compose.ui.graphics.ImageShader(grain,
                androidx.compose.ui.graphics.TileMode.Repeated, androidx.compose.ui.graphics.TileMode.Repeated)))
            val r = size.maxDimension * 0.75f
            drawRect(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.05f), Color.Transparent, Color.Black.copy(alpha = 0.22f)),
                center = center, radius = r))
            val step = 5.5f * density
            var rad = step
            while (rad < r) {
                drawCircle(Color.White.copy(alpha = 0.018f), radius = rad, center = center, style = androidx.compose.ui.graphics.drawscope.Stroke(0.6f * density))
                rad += step
            }
        }
        onDrawBehind { drawImage(img) }
    })
}

/** A hair of softness on the finder's image (Android 12+): the ground surface scatters it. */
private fun Modifier.groundGlassSoftness(): Modifier =
    if (android.os.Build.VERSION.SDK_INT < 31) this
    else graphicsLayer { renderEffect = androidx.compose.ui.graphics.BlurEffect(0.9f * density, 0.9f * density) }


/** An eyepiece's lateral colour on what it shows near the frame's edge: red pulled outward, blue in. */
private const val EYEPIECE_CA = """
uniform shader image;
uniform float2 size;
uniform float maxBlur;
uniform float maxShift;
uniform float wobble;                                      // focus wandering along the way (0: none)
half4 main(float2 p) {
    float2 c = size * 0.5;
    float2 d = (p - c) / c;
    float r = length(d);                                   // 0 at the centre, ~1.4 in the corners
    float blur = maxBlur * smoothstep(0.55, 1.35, r) * (1.0 + wobble * (0.5 + 0.5 * sin(p.y * 0.021 + 1.3) * sin(p.y * 0.0073 + 0.4)));
    float2 dir = d / max(r, 1e-3);
    float2 sh = dir * maxShift * smoothstep(0.3, 1.4, r);  // red outward, blue inward
    // a five-tap disc for the softness; the colour fringe from one extra pair of samples
    half4 acc = half4(0);
    for (int i = 0; i < 5; i++) {
        float a = float(i) * 2.39996;
        float rr = (i == 0) ? 0.0 : blur * sqrt(float(i) / 4.0);
        acc += image.eval(p + float2(cos(a), sin(a)) * rr);
    }
    acc /= 5.0;
    half4 rd = image.eval(p - sh);
    half4 bl = image.eval(p + sh);
    return half4(mix(acc.r, rd.r, 0.5), acc.g, mix(acc.b, bl.b, 0.5), max(acc.a, max(rd.a, bl.a) * 0.5));
}
"""


/** Brass: warm gold, lit from above left, lathe-turned rings, a bevelled rim, a few specks of patina. */
private object Brass {
    fun androidx.compose.ui.graphics.drawscope.DrawScope.release(c: Offset, r: Float, pressed: Boolean) = releaseBody(c, r, pressed)
    private val hi = Color(0xFFF2D892); private val mid = Color(0xFFC49A4E); private val lo = Color(0xFF6E5126); private val deep = Color(0xFF3A2A12)

    fun androidx.compose.ui.graphics.drawscope.DrawScope.disc(c: Offset, r: Float, pressed: Boolean, rings: Boolean) {
        val k = if (pressed) 0.72f else 1f
        fun Color.dim() = Color(red * k, green * k, blue * k, alpha)
        // the light, where the phone's tilt puts it (fixed in the room: it moves over the brass as the phone turns)
        val l = BrassLight.dir
        val lang = Math.toDegrees(kotlin.math.atan2(l.y.toDouble(), l.x.toDouble())).toFloat()
        // the body, lit from the light's side
        drawCircle(Brush.radialGradient(listOf(hi.dim(), mid.dim(), lo.dim()), center = c + Offset(r * l.x, r * l.y), radius = r * 1.55f), r, c)
        // lathe-turned rings: fine concentric lines, catching the light unevenly
        if (rings) {
            var rr = r * 0.12f
            var i = 0
            while (rr < r * 0.86f) {
                val a = if (i % 2 == 0) 0.07f else 0.05f
                drawCircle((if (i % 2 == 0) Color.White else Color.Black).copy(alpha = a), rr, c, style = androidx.compose.ui.graphics.drawscope.Stroke(0.6f))
                rr += maxOf(1.1f, r * 0.028f); i++
            }
            // the turned surface's sheen: a soft band across it
            turnedStreak(c, r * 0.86f, lang, 0.30f * k)
        }
        // a soft specular highlight up at the top left, as polished brass shows a light
        val spec = c + Offset(r * l.x * 1.05f, r * l.y * 1.05f)
        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.45f * k), Color.White.copy(alpha = 0f)),
            center = spec, radius = r * 0.32f), r * 0.32f, spec)
        // the bevel: dark outer edge, a bright lip just inside it at the top left
        drawCircle(deep.copy(alpha = 0.85f), r, c, style = androidx.compose.ui.graphics.drawscope.Stroke(r * 0.07f))
        drawArc(hi.copy(alpha = 0.55f * k), lang - 55f, 110f, false, topLeft = c - Offset(r * 0.88f, r * 0.88f),
            size = androidx.compose.ui.geometry.Size(r * 1.76f, r * 1.76f), style = androidx.compose.ui.graphics.drawscope.Stroke(r * 0.05f))
        drawArc(Color.Black.copy(alpha = 0.30f), lang + 180f - 60f, 120f, false, topLeft = c - Offset(r * 0.88f, r * 0.88f),
            size = androidx.compose.ui.geometry.Size(r * 1.76f, r * 1.76f), style = androidx.compose.ui.graphics.drawscope.Stroke(r * 0.05f))
        // patina: a few darker specks where fingers have been
        val rng = kotlin.random.Random((r * 13).toInt())
        repeat(5) {
            val a = rng.nextFloat() * 6.2832f; val d = r * 0.8f * kotlin.math.sqrt(rng.nextFloat())
            drawCircle(deep.copy(alpha = 0.06f + 0.06f * rng.nextFloat()), r * (0.04f + 0.06f * rng.nextFloat()),
                c + Offset(kotlin.math.cos(a) * d, kotlin.math.sin(a) * d))
        }
    }
}

/**
 * The shutter release as a real one is made: a knurled rim, a slightly dished turned face with
 * the two-lobed streak of light turned metal throws (toward the light and away from it), and the
 * threaded socket for a cable release at its centre, chamfered. Olive-gold brass; all of it lit
 * from where the phone's tilt puts the light.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.releaseBody(c: Offset, r: Float, pressed: Boolean) {
    val k = if (pressed) 0.72f else 1f
    fun col(v: Long) = Color(v).let { Color(it.red * k, it.green * k, it.blue * k) }
    val hi = col(0xFFEAD6A0); val mid = col(0xFFB89452); val lo = col(0xFF5E4624); val deep = col(0xFF2C2010)
    val l = BrassLight.dir
    val lang = kotlin.math.atan2(l.y, l.x)
    val Stroke = androidx.compose.ui.graphics.drawscope.Stroke
    // the knurled rim: fine ridges, lit on the light's side and falling into shadow round the other
    drawCircle(mid, r, c)
    val ridges = 96
    for (i in 0 until ridges) {
        val a = i / ridges.toFloat() * 6.2832f
        val facing = kotlin.math.cos(a - lang)                          // 1 toward the light
        val ca = kotlin.math.cos(a); val sa = kotlin.math.sin(a)
        val p0 = c + Offset(ca * r * 0.865f, sa * r * 0.865f); val p1 = c + Offset(ca * r * 0.985f, sa * r * 0.985f)
        val lit = (0.5f + 0.5f * facing)
        drawLine(Color(hi.red, hi.green, hi.blue, 0.25f + 0.55f * lit * lit), p0, p1, r * 0.022f)
        val a2 = a + 3.1416f / ridges
        val q0 = c + Offset(kotlin.math.cos(a2) * r * 0.865f, kotlin.math.sin(a2) * r * 0.865f)
        val q1 = c + Offset(kotlin.math.cos(a2) * r * 0.985f, kotlin.math.sin(a2) * r * 0.985f)
        drawLine(deep.copy(alpha = 0.55f - 0.3f * lit), q0, q1, r * 0.02f)
    }
    // the rim's outer edge and the step down to the face
    drawCircle(deep.copy(alpha = 0.9f), r, c, style = Stroke(r * 0.03f))
    drawCircle(deep.copy(alpha = 0.8f), r * 0.86f, c, style = Stroke(r * 0.025f))
    // the face: dished (a concave face is brighter on the side away from the light)
    val face = r * 0.85f
    drawCircle(Brush.radialGradient(listOf(hi, mid, lo), center = c - Offset(l.x * face * 0.55f, l.y * face * 0.55f), radius = face * 1.7f), face, c)
    // the turned streak: two lobes along the line through the light, the mark of lathe-turned metal
    val deg = Math.toDegrees(lang.toDouble()).toFloat()
    turnedStreak(c, face * 0.98f, deg, 0.42f * k)
    // fine turning lines over the face
    var rr = r * 0.2f; var i = 0
    while (rr < face) {
        drawCircle((if (i % 2 == 0) Color.White else Color.Black).copy(alpha = 0.045f), rr, c, style = Stroke(0.6f))
        rr += maxOf(1.0f, r * 0.022f); i++
    }
    // the cable-release socket: a chamfered lip (lit on the far side: it's a hole), threads, the dark bore
    val sock = r * 0.17f
    drawCircle(Brush.radialGradient(listOf(lo, hi), center = c - Offset(l.x * sock, l.y * sock), radius = sock * 1.6f), sock, c)
    drawCircle(Color(0xFF0B0805), sock * 0.68f, c)
    for (t in 1..3) drawCircle(mid.copy(alpha = 0.22f), sock * (0.30f + 0.12f * t), c, style = Stroke(0.7f))
    drawCircle(Color.Black, sock * 0.28f, c)
    // a polished glint on the rim nearest the light, where fingers have worn it bright
    drawArc(Color(0xFFFFF0C8).copy(alpha = 0.55f * k), deg - 28f, 56f, false, topLeft = c - Offset(r * 0.93f, r * 0.93f),
        size = androidx.compose.ui.geometry.Size(r * 1.86f, r * 1.86f), style = Stroke(r * 0.035f))
}

/** The two-lobed streak of light on lathe-turned metal, along the line through the light at [deg]. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.turnedStreak(c: Offset, r: Float, deg: Float, alpha: Float) {
    // sampled round the circle: bright toward the light and directly away, falling off over ~22 degrees
    val n = 72
    val stops = Array(n + 1) { i ->
        val a = i * 360f / n
        var d = kotlin.math.abs(((a - deg) % 180f + 180f) % 180f); if (d > 90f) d = 180f - d
        val v = kotlin.math.exp(-(d / 14f) * (d / 14f))
        (i / n.toFloat()) to Color.White.copy(alpha = alpha * v)
    }
    drawCircle(Brush.sweepGradient(*stops, center = c), r, c)
}

/** Black leatherette: a pebbled grain whose little bumps catch the light from above, tiled. */
private object Leatherette {
    fun texture(): androidx.compose.ui.graphics.ImageBitmap {
        val n = 192; val rng = kotlin.random.Random(23)
        // pebbles: a jittered grid of bumps; each pixel takes its nearest bump's height falloff
        val cell = 6
        val g = n / cell
        val px = FloatArray(g * g); val py = FloatArray(g * g)
        for (i in 0 until g * g) { px[i] = (i % g + rng.nextFloat()) * cell; py[i] = (i / g + rng.nextFloat()) * cell }
        val h = FloatArray(n * n)
        for (y in 0 until n) for (x in 0 until n) {
            val cx = x / cell; val cy = y / cell
            var best = 1e9f
            for (dy in -1..1) for (dx in -1..1) {
                val gx = (cx + dx + g) % g; val gy = (cy + dy + g) % g
                var ddx = x - px[gy * g + gx]; var ddy = y - py[gy * g + gx]
                if (ddx > n / 2) ddx -= n; if (ddx < -n / 2) ddx += n
                if (ddy > n / 2) ddy -= n; if (ddy < -n / 2) ddy += n
                best = minOf(best, ddx * ddx + ddy * ddy)
            }
            h[y * n + x] = 1f - (kotlin.math.sqrt(best) / (cell * 0.75f)).coerceIn(0f, 1f)
        }
        val out = IntArray(n * n)
        for (y in 0 until n) for (x in 0 until n) {
            val a = h[y * n + x]; val b = h[((y + 1) % n) * n + (x + 1) % n]
            val light = (a - b) * 0.9f                               // lit from the top left
            val v = (0.075f + 0.035f * a + light * 0.10f + (rng.nextFloat() - 0.5f) * 0.01f).coerceIn(0f, 1f)
            val c = (v * 255).toInt()
            out[y * n + x] = (0xFF shl 24) or ((c * 1.02f).toInt().coerceAtMost(255) shl 16) or (c shl 8) or (c * 0.95f).toInt()
        }
        return android.graphics.Bitmap.createBitmap(out, n, n, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
    }

    fun androidx.compose.ui.graphics.drawscope.DrawScope.draw(tex: androidx.compose.ui.graphics.ImageBitmap) {
        drawRect(androidx.compose.ui.graphics.ShaderBrush(androidx.compose.ui.graphics.ImageShader(tex,
            androidx.compose.ui.graphics.TileMode.Repeated, androidx.compose.ui.graphics.TileMode.Repeated)))
        // the scene's light on the leather (a faint tint from the finder above), and a soft sheen
        // that drifts with the tilt across the grain
        val h = Chassis.horizon
        drawRect(Color(h[0], h[1], h[2]).copy(alpha = 0.05f), blendMode = androidx.compose.ui.graphics.BlendMode.Plus)
        val l = BrassLight.dir
        drawRect(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.045f), Color.Transparent),
            center = Offset(size.width * (0.5f + l.x * 0.6f), size.height * (0.5f + l.y * 0.8f)), radius = size.width * 0.45f),
            blendMode = androidx.compose.ui.graphics.BlendMode.Plus)
        // the edge where it meets the barrel: a dark seam and a faint lit lip
        drawRect(Color.Black.copy(alpha = 0.6f), size = androidx.compose.ui.geometry.Size(size.width, 2f))
        drawRect(Color.White.copy(alpha = 0.05f), topLeft = Offset(0f, 2f), size = androidx.compose.ui.geometry.Size(size.width, 1f))
        // a soft falloff toward the bottom
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.35f))))
    }
}


/** Where the light falls on the brass, as a direction on the screen (-1..1): the phone's tilt moves it. */
object BrassLight {
    var dir by mutableStateOf(Offset(-0.35f, -0.42f))
    /** Up, on the screen (gravity's opposite): portrait (0, -1), turned on its side (+-1, 0). */
    var up by mutableStateOf(Offset(0f, -1f))
}

/**
 * Follows the phone's tilt (gravity) while the camera screen is up, moving [BrassLight] as a
 * light fixed in the room would move over the brass: upright, it sits near the top; laid flat,
 * overhead; tipped to a side, it slides the other way.
 */
@Composable
private fun BrassLightSensor() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.DisposableEffect(Unit) {
        val sm = ctx.getSystemService(android.content.Context.SENSOR_SERVICE) as android.hardware.SensorManager
        val sensor = sm.getDefaultSensor(android.hardware.Sensor.TYPE_GRAVITY) ?: sm.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)
        var sx = -0.35f; var sy = -0.42f
        var upX = 0f; var upY = -1f
        val listener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(e: android.hardware.SensorEvent) {
                val gx = e.values[0] / 9.81f; val gy = e.values[1] / 9.81f
                val tx = (-0.25f + gx * 0.55f).coerceIn(-0.75f, 0.75f)
                val ty = (-0.55f * gy.coerceIn(-1f, 1f) - 0.05f).coerceIn(-0.75f, 0.75f)
                sx += (tx - sx) * 0.12f; sy += (ty - sy) * 0.12f
                val d = BrassLight.dir
                if (kotlin.math.abs(d.x - sx) + kotlin.math.abs(d.y - sy) > 0.012f) BrassLight.dir = Offset(sx, sy)
                // which way is up, for the world the metal reflects (kept when the phone lies flat)
                val ux = e.values[0]; val uy = -e.values[1]; val ul = kotlin.math.hypot(ux, uy)
                if (ul > 3f) {
                    upX += (ux / ul - upX) * 0.15f; upY += (uy / ul - upY) * 0.15f
                    val n = kotlin.math.hypot(upX, upY)
                    val nu = Offset(upX / n, upY / n); val ou = BrassLight.up
                    if (kotlin.math.abs(nu.x - ou.x) + kotlin.math.abs(nu.y - ou.y) > 0.02f) BrassLight.up = nu
                }
            }
            override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
        }
        if (sensor != null) sm.registerListener(listener, sensor, android.hardware.SensorManager.SENSOR_DELAY_UI)
        onDispose { sm.unregisterListener(listener) }
    }
}


/** The film rewind knob in polished brass, turning a step with every frame wound on. */
@Composable
private fun FilmRewindKnob(frame: Int, onTap: () -> Unit) {
    // counter-clockwise as the film winds on (a positive shader angle turns it that way on screen)
    val angle by animateFloatAsState(frame * 240f,
        androidx.compose.animation.core.tween(durationMillis = 600, delayMillis = 500,
            easing = androidx.compose.animation.core.FastOutSlowInEasing), label = "rewind")
    val shader = remember { if (android.os.Build.VERSION.SDK_INT >= 33) android.graphics.RuntimeShader(REWIND_KNOB_AGSL) else null }
    Box(Modifier.fillMaxSize().clickable(onClick = onTap), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            deckShadow()
            if (shader != null) {
                Chassis.bind(shader)
                shader.setFloatUniform("iResolution", size.width, size.height)
                shader.setFloatUniform("uPx", 2f / size.minDimension)
                shader.setFloatUniform("uKnobAngle", Math.toRadians(angle.toDouble()).toFloat())
                shader.setFloatUniform("uEnamel", 0f)
                drawRect(androidx.compose.ui.graphics.ShaderBrush(shader))
            } else {
                with(Brass) { disc(center, size.minDimension / 2, pressed = false, rings = true) }
                rotate(-angle) { drawRect(Color(0xFF3A2A12), topLeft = Offset(size.width * 0.15f, size.height * 0.42f), size = androidx.compose.ui.geometry.Size(size.width * 0.7f, size.height * 0.16f)) }
            }
        }
    }
}


/** The engraved labels on the black enamel plates (PARALLAX, DOF), all one height. */
private val parallaxLabelHeight = 7.dp


/** One soft shadow under a round control on the leather, falling away from the light. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.deckShadow(scale: Float = 1f) {
    val l = BrassLight.dir
    val off = Offset(-l.x * 5.dp.toPx(), -l.y * 5.dp.toPx() + 3.dp.toPx())
    val r = size.minDimension / 2 * scale
    drawCircle(Brush.radialGradient(0.70f to Color.Black.copy(alpha = 0.62f), 1f to Color.Transparent, center = center + off, radius = r * 1.18f),
        r * 1.18f, center + off)
}

/** A label for the deck: engraved into the steel and filled black (all one size and baseline). */
@Composable
private fun DeckLabel(text: String) {
    val dens = androidx.compose.ui.platform.LocalDensity.current.density
    val img = remember(text) { Engraving.render(text, Color(0xFF121212), dens * 0.5f, text.hashCode()) }
    Canvas(Modifier.size(width = 60.dp, height = 16.dp)) {
        // one scale for every label (each engraving's own bounds differ, so fitting each to a
        // height would size the letters differently)
        val k = 1.16f
        val w = img.width * k; val h = img.height * k
        drawImage(img, dstOffset = androidx.compose.ui.unit.IntOffset((center.x - w / 2).toInt(), (center.y - h / 2).toInt()),
            dstSize = androidx.compose.ui.unit.IntSize(w.toInt(), h.toInt()))
    }
}

/** The parallax switch: a horizontal slide cut into the steel (right for on), a green LED set flush above it. */
@Composable
private fun ParallaxSlide(on: Boolean, onToggle: () -> Unit) {
    val sdk33 = android.os.Build.VERSION.SDK_INT >= 33
    val slide = remember { if (sdk33) android.graphics.RuntimeShader(SLIDE_V_AGSL) else null }
    val led = remember { if (sdk33) android.graphics.RuntimeShader(FLUSH_LED_AGSL) else null }
    val knob by animateFloatAsState(if (on) 1f else -1f, androidx.compose.animation.core.spring(dampingRatio = 0.6f, stiffness = 600f), label = "slide")
    val lit by animateFloatAsState(if (on) 1f else 0f, androidx.compose.animation.core.tween(180), label = "led")
    Column(Modifier.fillMaxSize().clickable(onClick = onToggle), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Canvas(Modifier.size(10.dp)) {
            if (lit > 0f) drawCircle(Brush.radialGradient(listOf(Color(0xFF40FF90).copy(alpha = 0.45f * lit), Color.Transparent), radius = size.minDimension * 1.6f), size.minDimension * 1.6f)
            if (led != null) {
                Chassis.bind(led)
                led.setFloatUniform("iResolution", size.width, size.height)
                led.setFloatUniform("uPx", 2f / size.minDimension)
                led.setFloatUniform("uLit", lit)
                drawRect(androidx.compose.ui.graphics.ShaderBrush(led))
            } else drawCircle(if (on) Color(0xFF73F2B3) else Color(0xFF1F2A24), size.minDimension / 2)
        }
        Spacer(Modifier.height(5.dp))
        // the slide: the vertical slide's shader turned a quarter (up becomes right)
        // (the ball, 0.75 of the half-width of 24 dp: 18 dp across, as the DOF button)
        Box(Modifier.size(width = 40.dp, height = 24.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.requiredSize(width = 24.dp, height = 40.dp).graphicsLayer { rotationZ = 90f }) {
                if (slide != null) {
                    Chassis.bind(slide)
                    slide.setFloatUniform("iResolution", size.width, size.height)
                    slide.setFloatUniform("uPx", 2f / size.width)
                    slide.setFloatUniform("uKnob", knob)
                    drawRect(androidx.compose.ui.graphics.ShaderBrush(slide))
                } else drawRoundRect(Color(0xFF0A0A0A), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width / 2))
            }
        }
    }
}

/** The light table seen through a waist-level viewer: the latest frame (or its darkroom timer) under glass in a stainless bezel. */
@Composable
private fun WaistLevelViewer(last: DarkroomEntry?, onOpen: () -> Unit) {
    val bezel = remember { if (android.os.Build.VERSION.SDK_INT >= 33) android.graphics.RuntimeShader(VIEWER_BEZEL_AGSL) else null }
    Box(Modifier.fillMaxSize().clickable(onClick = onOpen), contentAlignment = Alignment.Center) {
        // the picture, sunk under the frame (its edges darkened, as a ground glass seen from above)
        Box(Modifier.fillMaxSize(0.75f).clip(RoundedCornerShape(7.dp)).background(Color(0xFF0B0A09)), contentAlignment = Alignment.Center) {
            val bmp = last?.preview
            if (bmp != null) Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else if (last != null && !last.status.startsWith("failed"))
                DarkroomTimerView(last.devStart, last.devSet, Modifier.fillMaxSize().background(Color(0xFF020302)).padding(4.dp))
            Canvas(Modifier.fillMaxSize()) {
                drawRect(Brush.radialGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.55f), center = center, radius = size.maxDimension * 0.62f))
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            if (bezel != null) {
                Chassis.bind(bezel)
                bezel.setFloatUniform("iResolution", size.width, size.height)
                val hh = size.height / 2
                bezel.setFloatUniform("uPx", 1f / hh)
                bezel.setFloatUniform("uOpen", size.width * 0.37f / hh, size.height * 0.37f / hh)
                bezel.setFloatUniform("uFrame", size.height * 0.10f / hh)   // (outer edge 0.94: its rounded corners stay inside the canvas)
                drawRect(androidx.compose.ui.graphics.ShaderBrush(bezel))
            } else drawRoundRect(Color(0xFF8E9297), style = androidx.compose.ui.graphics.drawscope.Stroke(size.height * 0.1f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()))
        }
    }
}

/** The depth-of-field preview button: a small polished steel sphere; held, it previews. */
@Composable
private fun DofButton(held: Boolean, onHeld: (Boolean) -> Unit) {
    val shader = remember { if (android.os.Build.VERSION.SDK_INT >= 33) android.graphics.RuntimeShader(SPHERE_BUTTON_AGSL) else null }
    val press by animateFloatAsState(if (held) 1f else 0f, label = "dofpress")
    Canvas(Modifier.size(18.dp).scale(1f - 0.06f * press)
        .pointerInput(Unit) { detectTapGestures(onPress = { onHeld(true); tryAwaitRelease(); onHeld(false) }) }) {
        deckShadow()
        if (shader != null) {
            Chassis.bind(shader)
            shader.setFloatUniform("iResolution", size.width, size.height)
            shader.setFloatUniform("uPx", 2f / size.minDimension)
            shader.setFloatUniform("uPressed", press)
            drawRect(androidx.compose.ui.graphics.ShaderBrush(shader))
        } else drawCircle(Color(0xFF0C0C0C), size.minDimension / 2)
    }
}

/** The light table: a loupe in a black enamel bezel showing the latest frame (or its darkroom timer). */
@Composable
private fun LightTableLoupe(last: DarkroomEntry?, onOpen: () -> Unit) {
    val loupeGlass = remember { if (android.os.Build.VERSION.SDK_INT >= 33) android.graphics.RuntimeShader(GLASS_WINDOW_AGSL) else null }
    val loupeLens = remember { if (android.os.Build.VERSION.SDK_INT >= 33) android.graphics.RuntimeShader(LOUPE_LENS_AGSL) else null }
    Box(Modifier.fillMaxSize().clickable(onClick = onOpen), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) { deckShadow() }
        Box(Modifier.fillMaxSize(0.80f).clip(CircleShape).background(Color(0xFF0C0B0A)).graphicsLayer {
            if (loupeLens != null) {
                loupeLens.setFloatUniform("size", size.width, size.height)
                renderEffect = android.graphics.RenderEffect.createRuntimeShaderEffect(loupeLens, "image").asComposeRenderEffect()
            }
        }, contentAlignment = Alignment.Center) {
            val bmp = last?.preview
            if (bmp != null) Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else if (last != null && !last.status.startsWith("failed"))
                DarkroomTimerView(last.devStart, last.devSet, Modifier.fillMaxSize().background(Color(0xFF020302)).padding(2.dp))
        }
        if (loupeGlass != null) Canvas(Modifier.fillMaxSize()) {
            Chassis.bind(loupeGlass)
            loupeGlass.setFloatUniform("iResolution", size.width, size.height)
            loupeGlass.setFloatUniform("uPx", 2f / size.height)
            loupeGlass.setFloatUniform("uRound", 1f)
            loupeGlass.setFloatUniform("uAr", 1f)
            loupeGlass.setFloatUniform("uBrass", 0f)
            loupeGlass.setFloatUniform("uKnurl", 1f)
            drawRect(androidx.compose.ui.graphics.ShaderBrush(loupeGlass))
        }
    }
}


/** The film plane mark: a circle with a line through it, engraved into the steel and filled black. */
@Composable
private fun FilmPlaneMark() {
    Canvas(Modifier.fillMaxSize()) {
        val c = center; val r = size.height * 0.30f
        val ink = Color(0xFF121212); val lip = Color.White.copy(alpha = 0.35f)
        val sw = 1.3.dp.toPx(); val d = 0.6.dp.toPx()
        // the lip catching light just below the cut, then the black fill
        for ((col, dy) in listOf(lip to d, ink to 0f)) {
            drawCircle(col, r, c + Offset(0f, dy), style = androidx.compose.ui.graphics.drawscope.Stroke(sw))
            drawLine(col, Offset(0f, c.y + dy), Offset(size.width, c.y + dy), sw)
        }
    }
}
