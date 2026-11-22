package app.relens.ui

import android.graphics.Bitmap
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.random.Random

/**
 * The light table, as in Vintage Click (ContactSheet.swift): the session's rolls cut into
 * strips of six and laid on lit glass, sprocket holes letting the light through, the
 * stock's name and frame numbers printed in the rebate, dust on the film, frames still
 * developing marked "in the darkroom", keepers ringed in red china marker. No loupe is
 * drawn: pinch to look closer, and from 2x the view starts to be seen through one (its
 * pincushion, colour fringes, soft rim and round vignette, fully there by 3.5x).
 *
 * The strips stand upright (the phone is held so): each frame 24 mm across and 36 down,
 * so an upright print fills it and a landscape one lies on its side, as on the film.
 */
@Composable
fun LightTableScreen(body: Body, onBack: () -> Unit, openBench: () -> Unit, initialScale: Float = 1f) {
    BackHandler(onBack = onBack)
    // the table is the gallery folder: read it again on opening and whenever the app comes back
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        body.refreshLightTable()
        val obs = androidx.lifecycle.LifecycleEventObserver { _, ev ->
            if (ev == androidx.lifecycle.Lifecycle.Event.ON_START) body.refreshLightTable()
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    var scale by remember { mutableFloatStateOf(initialScale) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var centred by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<DarkroomEntry?>(null) }
    val measurer = rememberTextMeasurer()
    // the darkroom timers run while any frame is still developing
    val nowMs = frameClock(body.darkroom.any { !it.done && it.preview == null && !it.status.startsWith("failed") })
    // The frames by day, oldest first; within a day by roll and frame, cut into strips of six.
    val dayKey = { e: DarkroomEntry -> java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date(e.taken)) }
    val byDay = body.darkroom.groupBy(dayKey).toSortedMap()
    val strips = ArrayList<List<DarkroomEntry>>(); val stripDay = ArrayList<Int>(); val dayNames = ArrayList<String>()
    for ((d, list) in byDay) {
        val day = dayNames.size
        dayNames += java.text.SimpleDateFormat("EEEE d MMMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(list.first().taken))
        for ((_, frames) in list.groupBy { it.roll }.toSortedMap()) for (st in frames.sortedBy { it.frame }.chunked(6)) { strips += st; stripDay += day }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFFE9EAEE))) {
        BoxWithConstraints(Modifier.fillMaxSize().loupe(scale)) {
            val wPx = constraints.maxWidth.toFloat(); val hPx = constraints.maxHeight.toFloat()
            val table = remember(constraints.maxWidth, constraints.maxHeight) { tableGlass(constraints.maxWidth, constraints.maxHeight) }
            // Film millimetres to pixels: three strips across the table at 1x.
            val cols = 3
            val mm = wPx / (cols * 35f + (cols + 1) * 6f)
            val stripLen = { n: Int -> (n * 38f + 4f) }
            // below the status bar and the table's title
            val top = with(androidx.compose.ui.platform.LocalDensity.current) { 110.dp.toPx() }
            // each day starts a new row of strips under its date, written on the glass
            val dayLabel = 14f
            val stripRects = ArrayList<Rect>(); val labelAt = ArrayList<Offset>()
            run {
                var y = top; var col = 0; var lastDay = -1
                for ((i, st) in strips.withIndex()) {
                    if (stripDay[i] != lastDay) {
                        if (lastDay >= 0) y += (stripLen(6) + 10f) * mm
                        labelAt += Offset(6f * mm, y); y += dayLabel * mm; col = 0; lastDay = stripDay[i]
                    } else if (col == cols) { col = 0; y += (stripLen(6) + 10f) * mm }
                    stripRects += Rect(Offset((6f + col * 41f) * mm, y), Size(35f * mm, stripLen(st.size) * mm))
                    col++
                }
            }
            fun stripRect(i: Int, @Suppress("UNUSED_PARAMETER") n: Int) = stripRects[i]
            fun frameRect(strip: Rect, j: Int) = Rect(Offset(strip.left + 5.5f * mm, strip.top + (2f + j * 38f + 1f) * mm), Size(24f * mm, 36f * mm))
            fun toSheet(p: Offset) = (p - pan) / scale
            // Opened on the newest frame: the last of the latest roll, in the middle of the table
            // at the zoom it opens at (once, when the frames are there).
            androidx.compose.runtime.LaunchedEffect(strips.size, wPx, hPx) {
                // (the newest strip: the last of the latest day)
                if (centred || strips.isEmpty()) return@LaunchedEffect
                val i = strips.lastIndex; val st = strips[i]
                val c = frameRect(stripRect(i, st.size), st.lastIndex).center
                pan = Offset(wPx / 2f, hPx / 2f) - c * scale
                centred = true
            }

            Canvas(Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, panBy, zoom, _ ->
                        val ns = (scale * zoom).coerceIn(0.6f, 8f)
                        // zoom about the fingers
                        pan = centroid - (centroid - pan) * (ns / scale) + panBy
                        scale = ns
                    }
                }
                .pointerInput(strips) {
                    detectTapGestures(
                        onDoubleTap = { p ->
                            val ns = if (scale < 2f) 3f else 1f
                            pan = if (ns == 1f) Offset.Zero else p - (p - pan) * (ns / scale)
                            scale = ns
                        },
                        onTap = { p ->
                            val q = toSheet(p)
                            for ((i, st) in strips.withIndex()) {
                                val r = stripRect(i, st.size)
                                for ((j, e) in st.withIndex()) if (e.preview != null && frameRect(r, j).contains(q)) { viewing = e; return@detectTapGestures }
                            }
                        },
                        onLongPress = { p ->
                            val q = toSheet(p)
                            for ((i, st) in strips.withIndex()) {
                                val r = stripRect(i, st.size)
                                for ((j, e) in st.withIndex()) if (frameRect(r, j).inflate(2f * mm).contains(q)) { body.toggleMark(e); return@detectTapGestures }
                            }
                        })
                }) {
                withTransform({ translate(pan.x, pan.y); scale(scale, scale, Offset.Zero) }) {
                    // the glass and the light through it move with the film: the glow (repeated
                    // in mirror image, so it never seams), then the dust on it
                    val view = Rect(toSheet(Offset.Zero), toSheet(Offset(size.width, size.height)))
                    drawRect(androidx.compose.ui.graphics.ShaderBrush(androidx.compose.ui.graphics.ImageShader(table,
                        androidx.compose.ui.graphics.TileMode.Mirror, androidx.compose.ui.graphics.TileMode.Mirror)), view.topLeft, view.size)
                    glassDust(view, density / 2.625f)
                    // the day, in grease pencil on the glass above its strips
                    for ((k, at) in labelAt.withIndex()) {
                        val t = measurer.measure(dayNames[k].uppercase(), TextStyle(color = Color(0xCC2E2A26), fontSize = (4.2f * mm).toSp(),
                            fontFamily = Futuraish, fontWeight = FontWeight.Bold, letterSpacing = (0.8f * mm).toSp()))
                        rotate(-0.6f + (k % 3) * 0.4f, pivot = at) { drawText(t, topLeft = at + Offset(0f, (dayLabel * mm - t.size.height) * 0.55f)) }
                    }
                    for ((i, st) in strips.withIndex()) {
                        val r = stripRect(i, st.size)
                        drawStrip(r, mm, st, i, frameRect = { j -> frameRect(r, j) }, measurer = measurer, nowMs = nowMs, zoom = scale)
                    }
                }
                if (strips.isEmpty()) {
                    val t = measurer.measure("Nothing in Pictures/Vintage Snap yet.", TextStyle(color = Color(0xFF55524C), fontSize = 14.sp, fontFamily = Futuraish))
                    drawText(t, topLeft = Offset((size.width - t.size.width) / 2, size.height * 0.4f))
                }
            }
        }
        // The table's edge: back, the DNG switch; along the foot what's on the glass.
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            // dark on the lit glass; light once the loupe's barrel darkens the view
            val ink = if (scale >= 3f) Palette.cream else Color(0xFF3A3530)
            // back: the arrow and the title together, a generous target (at least 56 dp tall)
            Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onBack).heightIn(min = 56.dp)
                .padding(start = 4.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("‹", color = ink, fontSize = 32.sp, modifier = Modifier.padding(end = 12.dp))
                Text("LIGHT TABLE", color = ink, fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            }
            Spacer(Modifier.weight(1f))
            Text("DNG " + if (body.dngOut) "ON" else "OFF", color = if (body.dngOut) Color(0xFF1E8A5C) else Color(0xFF7A746C),
                fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0x22000000)).clickable { body.toggleDng() }
                    .padding(horizontal = 10.dp, vertical = 5.dp))
            Spacer(Modifier.width(6.dp))
            // the camera's own untouched RAW, into the gallery beside the print
            Text("RAW " + if (body.rawOut) "ON" else "OFF", color = if (body.rawOut) Color(0xFF1E8A5C) else Color(0xFF7A746C),
                fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0x22000000)).clickable { body.toggleRaw() }
                    .padding(horizontal = 10.dp, vertical = 5.dp))
        }
        val rolls = if (dayNames.isEmpty()) "" else if (dayNames.size == 1) "1 day" else "${dayNames.size} days"
        val developing = body.darkroom.count { !it.done && it.preview == null }
        val foot = listOfNotNull(rolls.ifEmpty { null }, "${body.darkroom.size} frames",
            if (developing > 0) "$developing in the darkroom" else null,
            if (scale >= 2f) "loupe %.1fx".format(scale) else "pinch to look closer", "tap a frame to see it", "hold to mark it").joinToString("  ·  ")
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xE6141312)).navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(foot, color = Palette.cream, fontSize = 11.sp, maxLines = 2, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text("bench ›", color = Palette.dim, fontSize = 11.sp, modifier = Modifier.clickable(onClick = openBench))
            }
        }
        viewing?.let { e -> PrintView(e) { viewing = null } }
    }
}

/**
 * A frame held up on its own: the print, full screen; hold it to share it, tap to put it
 * back.
 */
@Composable
private fun PrintView(e: DarkroomEntry, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    var big by remember(e) { mutableStateOf<Bitmap?>(null) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(e) {
        big = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching {
            // the gallery's copy, or the app's own while it still holds the print
            val open: () -> java.io.InputStream? = e.uri?.let { u -> { ctx.contentResolver.openInputStream(u) } }
                ?: e.file?.takeIf { it.exists() }?.let { f -> { f.inputStream() } } ?: return@runCatching null
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            open()?.use { android.graphics.BitmapFactory.decodeStream(it, null, o) }
            var ss = 1; while (maxOf(o.outWidth, o.outHeight) / (ss * 2) >= 2400) ss *= 2
            open()?.use { android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = ss }) }
        }.getOrNull() }
    }
    val shown = big ?: e.preview
    var sharing by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Color(0xF2050505))
        .pointerInput(e) { detectTapGestures(onTap = { if (sharing) sharing = false else onClose() }, onLongPress = { sharing = true }) },
        contentAlignment = Alignment.Center) {
        shown?.let { androidx.compose.foundation.Image(it.asImageBitmap(), null, contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            modifier = Modifier.fillMaxSize().padding(12.dp)) }
        Text(e.label + "  ·  hold to share",
            color = Palette.cream, fontSize = 11.sp,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp))
        if (sharing) ShareSheet(e, Modifier.align(Alignment.BottomCenter)) { action ->
            sharing = false
            action?.let { (uri, mime, open) -> handOff(ctx, uri, mime, open) }
        }
    }
}

/**
 * Long-press on a print: hand it on. Share (Instagram, Photos, a message: Android's share
 * sheet) or open in another app (an editor: Snapseed takes the DNG), the print or its DNG.
 */
@Composable
private fun ShareSheet(e: DarkroomEntry, modifier: Modifier, onPick: (Triple<android.net.Uri, String, Boolean>?) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // the gallery's copies; a print the app still holds goes out through its file provider
    fun provided(f: java.io.File?) = f?.takeIf { it.exists() }?.let { androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", it) }
    val jpg = e.uri ?: provided(e.file)
    val dng = e.dngUri ?: provided(e.file?.let { java.io.File(it.parentFile, it.nameWithoutExtension + ".dng") })
    androidx.compose.foundation.layout.Column(modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp)
        .clip(RoundedCornerShape(10.dp)).background(Color(0xF0141312)).padding(vertical = 6.dp)) {
        @Composable fun item(label: String, sub: String, pick: Triple<android.net.Uri, String, Boolean>?) {
            androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth()
                .then(if (pick != null) Modifier.clickable { onPick(pick) } else Modifier).padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(label, color = if (pick != null) Palette.cream else Palette.dim, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(sub, color = Palette.dim, fontSize = 10.sp)
            }
        }
        item("Share the print", "Instagram, Photos, a message…", jpg?.let { Triple(it, "image/jpeg", false) })
        item("Open the print in…", "an editor or viewer", jpg?.let { Triple(it, "image/jpeg", true) })
        item("Share the DNG", if (dng != null) "the print in high dynamic range, for a raw editor" else "no DNG for this frame (the light table's DNG switch was off)",
            dng?.let { Triple(it, "image/x-adobe-dng", false) })
        item("Open the DNG in…", "Snapseed, Lightroom…", dng?.let { Triple(it, "image/x-adobe-dng", true) })
        item("Cancel", "", null)
    }
}

/** Hands [uri] to another app: the share sheet, or (open) an app that views or edits it. */
private fun handOff(ctx: android.content.Context, uri: android.net.Uri, mime: String, open: Boolean) {
    runCatching {
        val intent = if (open) android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri, mime)
            else android.content.Intent(android.content.Intent.ACTION_SEND).setType(mime).putExtra(android.content.Intent.EXTRA_STREAM, uri)
        intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (!open) intent.clipData = android.content.ClipData.newRawUri(uri.lastPathSegment ?: "print", uri)
        ctx.startActivity(android.content.Intent.createChooser(intent, if (open) "Open with" else "Share").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { app.relens.Trace.w("hand-off failed", it) }
}

/**
 * Dust, hairs and fine scratches on the glass itself, in the table's own space (they pan
 * and grow under the loupe with the film): laid out tile by tile from a fixed seed, so the
 * glass never runs out however far it is moved. [view]: the visible region, in table space.
 */
private fun DrawScope.glassDust(view: Rect, px: Float) {
    val tile = 320f * px
    val x0 = kotlin.math.floor(view.left / tile).toInt(); val x1 = kotlin.math.floor(view.right / tile).toInt()
    val y0 = kotlin.math.floor(view.top / tile).toInt(); val y1 = kotlin.math.floor(view.bottom / tile).toInt()
    if ((x1 - x0 + 1) * (y1 - y0 + 1) > 400) return             // zoomed far out: too many to bother
    for (ty in y0..y1) for (tx in x0..x1) {
        val rng = Random(tx * 73856093 xor ty * 19349663 xor 2024)
        val ox = tx * tile; val oy = ty * tile
        repeat(10) {
            // a particle: one of the pre-made irregular specks, turned and sized at random
            val sprite = DustSprites.all[rng.nextInt(DustSprites.all.size)]
            val d = (1.6f + 6.5f * rng.nextFloat() * rng.nextFloat()) * px
            val c = Offset(ox + rng.nextFloat() * tile, oy + rng.nextFloat() * tile)
            val alpha = 0.25f + 0.6f * rng.nextFloat()
            rotate(rng.nextFloat() * 360f, pivot = c) {
                drawImage(sprite, dstOffset = IntOffset((c.x - d / 2).toInt(), (c.y - d / 2).toInt()),
                    dstSize = IntSize(maxOf(1, d.toInt()), maxOf(1, d.toInt())), alpha = alpha)
            }
        }
        if (rng.nextFloat() < 0.25f) {                          // a hair
            val path = Path()
            var p = Offset(ox + rng.nextFloat() * tile, oy + rng.nextFloat() * tile); path.moveTo(p.x, p.y)
            var a = rng.nextFloat() * 6.28f
            repeat(10) { a += (rng.nextFloat() - 0.5f) * 0.7f; p += Offset(cos(a), kotlin.math.sin(a)) * (4f * px); path.lineTo(p.x, p.y) }
            drawPath(path, Color(0x4D4D4D4D), style = Stroke(0.6f * px))
        }
        if (rng.nextFloat() < 0.12f) {                          // a long fine scratch
            val p = Offset(ox + rng.nextFloat() * tile, oy + rng.nextFloat() * tile)
            val a = rng.nextFloat() * Math.PI.toFloat(); val len = (120f + 500f * rng.nextFloat()) * px
            drawLine(if (rng.nextBoolean()) Color(0x1EFFFFFF) else Color(0x1E646464), p, p + Offset(cos(a), kotlin.math.sin(a)) * len, 1.5f * px)
        }
    }
}

/** One strip of film on the glass: punched base, rebate printing, the frames, dust, keepers. */
private fun DrawScope.drawStrip(r: Rect, mm: Float, frames: List<DarkroomEntry>, index: Int, frameRect: (Int) -> Rect,
                                measurer: androidx.compose.ui.text.TextMeasurer, nowMs: Long, zoom: Float = 1f) {
    val rng = Random(index * 131 + (frames.firstOrNull()?.roll ?: 0) * 17 + 5)
    val first = frames.first()
    // laid down by hand: never quite square
    rotate(degrees = (rng.nextFloat() - 0.5f) * 0.7f, pivot = r.center) {
        // the base, punched: the light shows through the sprocket holes
        val film = Path().apply {
            fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
            addRect(r)
            var y = r.top + 1.2f * mm
            while (y < r.bottom - 2f * mm) {
                for (x in floatArrayOf(r.left + 2.0f * mm, r.right - 4.79f * mm))
                    addRoundRect(androidx.compose.ui.geometry.RoundRect(Rect(Offset(x, y), Size(2.79f * mm, 1.98f * mm)),
                        androidx.compose.ui.geometry.CornerRadius(0.5f * mm)))
                y += 4.75f * mm
            }
        }
        drawPath(film, Color(0xFF0D0D0D))
        // the rebate printing: exposed into the film at the factory, glowing out of the black
        val edge = if (first.bw) Color(0xD9CCCCCC) else Color(0xD9FFB34D)
        val style = TextStyle(color = edge, fontSize = (1.5f * mm).toSp(), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, letterSpacing = (0.2f * mm).toSp())
        val name = "${first.stock.uppercase()}  SAFETY FILM"
        rotate(90f, pivot = Offset(r.left + 0.5f * mm, r.top + 3f * mm)) {
            var x = 0f
            val t = measurer.measure(name, style)
            while (x < r.height - 10 * mm) { drawText(t, topLeft = Offset(r.left + 0.5f * mm + x, r.top + 3f * mm - t.size.height)); x += 115 * mm }
        }
        for ((j, e) in frames.withIndex()) {
            val fr = frameRect(j)
            rotate(90f, pivot = Offset(r.right - 0.3f * mm, fr.top)) {
                val n = measurer.measure("${e.frame}", style)
                drawText(n, topLeft = Offset(r.right - 0.3f * mm + 2f * mm, fr.top))
                val a = measurer.measure("▸ ${e.frame}A", style)
                drawText(a, topLeft = Offset(r.right - 0.3f * mm + fr.height / 2 + 1f * mm, fr.top))
            }
        }
        // the frames
        for ((j, e) in frames.withIndex()) {
            val fr = frameRect(j)
            val bmp = e.preview
            if (bmp == null) {
                if (e.status.startsWith("failed")) {
                    val t = measurer.measure("failed", TextStyle(color = Color(0x99FFE6C7), fontSize = (2.0f * mm).toSp(), fontFamily = Futuraish))
                    drawText(t, topLeft = Offset(fr.center.x - t.size.width / 2f, fr.center.y - t.size.height / 2f))
                } else with(DarkroomTimer) {
                    // still in the darkroom: its timer, glowing in the dark, counting the development down
                    drawRect(Color(0xFF020302), fr.topLeft, fr.size)
                    drawTimer(fr.center, minOf(fr.width, fr.height) * 0.46f, DarkroomTimer.remaining(e.devStart, e.devSet, nowMs), e.devSet, nowMs, zoom)
                }
                continue
            }
            drawFrame(bmp, fr)
        }
        // dust printed in with the pictures; now and then a hair
        repeat(2 + rng.nextInt(3) + frames.size * 2) {
            val p = Offset(r.left + rng.nextFloat() * r.width, r.top + rng.nextFloat() * r.height)
            val d = (0.25f + 0.7f * rng.nextFloat() * rng.nextFloat()) * mm
            val sprite = DustSprites.all[rng.nextInt(DustSprites.all.size)]
            rotate(rng.nextFloat() * 360f, pivot = p) {
                drawImage(sprite, dstOffset = IntOffset((p.x - d / 2).toInt(), (p.y - d / 2).toInt()),
                    dstSize = IntSize(maxOf(1, d.toInt()), maxOf(1, d.toInt())), alpha = 0.8f,
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color(0xFFEBEBEB), androidx.compose.ui.graphics.BlendMode.SrcIn))
            }
        }
        if (rng.nextFloat() < 0.4f) {
            val p0 = Offset(r.left + rng.nextFloat() * r.width, r.top + rng.nextFloat() * r.height)
            val hair = Path().apply {
                moveTo(p0.x, p0.y)
                var p = p0; var a = rng.nextFloat() * 6.28f
                repeat(8) { a += (rng.nextFloat() - 0.5f) * 0.8f; p += Offset(cos(a), kotlin.math.sin(a)) * (0.8f * mm); lineTo(p.x, p.y) }
            }
            drawPath(hair, Color(0x8CE6E6E6), style = Stroke(0.07f * mm))
        }
        // keepers: ringed in red china marker, wobbly, gone over twice
        for ((j, e) in frames.withIndex()) if (e.marked) greasePencil(frameRect(j), e.roll * 100 + e.frame, mm)
    }
}

/** A print in its frame: an upright one fills it, a landscape one lies on its side (as on the film). */
private fun DrawScope.drawFrame(bmp: Bitmap, fr: Rect) {
    val img = bmp.asImageBitmap()
    val landscape = bmp.width > bmp.height
    if (!landscape) {
        drawImage(img, IntOffset.Zero, IntSize(bmp.width, bmp.height), IntOffset(fr.left.toInt(), fr.top.toInt()),
            IntSize(fr.width.toInt(), fr.height.toInt()))
    } else {
        rotate(90f, pivot = fr.center) {
            val w = fr.height; val h = fr.width
            drawImage(img, IntOffset.Zero, IntSize(bmp.width, bmp.height),
                IntOffset((fr.center.x - w / 2).toInt(), (fr.center.y - h / 2).toInt()), IntSize(w.toInt(), h.toInt()))
        }
    }
}

private fun DrawScope.greasePencil(fr: Rect, seed: Int, mm: Float) {
    val rng = Random(seed * 7919 + 3)
    val box = fr.inflate(1.6f * mm)
    fun j() = (rng.nextFloat() - 0.5f) * 2.4f * mm
    for (pass in 0..1) {
        val c = listOf(box.topLeft, box.topRight, box.bottomRight, box.bottomLeft)
        val start = c[0] + Offset(j(), j())
        val path = Path().apply {
            moveTo(start.x, start.y)
            for (k in 1..4) {
                val a = c[(k - 1) % 4]; val b = c[k % 4]
                val mid = (a + b) / 2f + Offset(j() * 0.6f, j() * 0.6f)
                val end = if (k == 4) start + Offset(2f * mm, j() * 0.4f) else b + Offset(j(), j())
                quadraticTo(mid.x, mid.y, end.x, end.y)
            }
        }
        drawPath(path, Color(0xFFCC1412).copy(alpha = if (pass == 0) 0.72f else 0.5f),
            style = Stroke((if (pass == 0) 0.9f else 0.6f) * mm, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** The light through the glass: tube banding and a soft mottle (it repeats in mirror image as the table moves). */
private fun tableGlass(w: Int, h: Int): ImageBitmap {
    val rng = Random(2024)
    val gw = 9; val gh = 7
    val grid = FloatArray(gw * gh) { rng.nextFloat() * 2 - 1 }
    fun mottle(x: Float, y: Float): Float {
        val fx = x * (gw - 1); val fy = y * (gh - 1)
        val ix = minOf(gw - 2, fx.toInt()); val iy = minOf(gh - 2, fy.toInt())
        val tx = fx - ix; val ty = fy - iy
        val sx = tx * tx * (3 - 2 * tx); val sy = ty * ty * (3 - 2 * ty)
        val top = grid[iy * gw + ix] + (grid[iy * gw + ix + 1] - grid[iy * gw + ix]) * sx
        val low = grid[(iy + 1) * gw + ix] + (grid[(iy + 1) * gw + ix + 1] - grid[(iy + 1) * gw + ix]) * sx
        return top + (low - top) * sy
    }
    // at a quarter size (it's smooth), then scaled up
    val qw = maxOf(1, w / 4); val qh = maxOf(1, h / 4)
    val px = IntArray(qw * qh)
    for (y in 0 until qh) {
        val v = y / qh.toFloat()
        for (x in 0 until qw) {
            val u = x / qw.toFloat()
            val dx = u - 0.5f; val dy = v - 0.5f
            var lum = 0.93f + 0.03f * cos(v * 2 * Math.PI.toFloat() * 3.5f + 0.7f) + 0.016f * mottle(u, v)
            @Suppress("UNUSED_VARIABLE") val r2 = dx * dx + dy * dy   // no fall-off: the glow repeats as the glass moves
            lum += rng.nextFloat() * 0.012f - 0.006f
            val warm = 0.012f * mottle(1 - v, u)
            fun b(f: Float) = (f * 255).toInt().coerceIn(0, 255)
            px[y * qw + x] = (0xFF shl 24) or (b(lum + warm) shl 16) or (b(lum + 0.004f) shl 8) or b(lum - warm + 0.03f)
        }
    }
    val small = Bitmap.createBitmap(px, qw, qh, Bitmap.Config.ARGB_8888)
    val big = Bitmap.createScaledBitmap(small, w, h, true)
    return big.asImageBitmap()
}

/**
 * Dust as it really sits on glass: specks with ragged outlines (a wobbling radius, a bite or
 * two taken out), mottled inside, some translucent flakes lighter in the middle, a few
 * fibres; soft at the very edge. Made once, small, and drawn scaled and turned.
 */
private object DustSprites {
    val all: List<androidx.compose.ui.graphics.ImageBitmap> by lazy { List(16) { make(it) } }

    private fun make(seed: Int): androidx.compose.ui.graphics.ImageBitmap {
        val n = 64
        val rng = Random(seed * 7919 + 13)
        val px = IntArray(n * n)
        val fibre = seed % 6 == 5
        // the outline: a radius that wobbles with the angle
        val harm = 7
        val amp = FloatArray(harm) { k -> if (k < 2) 0f else (0.10f + 0.12f * rng.nextFloat()) / Math.pow(k.toDouble(), 0.8).toFloat() }
        val ph = FloatArray(harm) { rng.nextFloat() * 6.2832f }
        val bites = List(rng.nextInt(3)) { Triple(rng.nextFloat() * 6.2832f, 0.25f + 0.2f * rng.nextFloat(), 0.25f + 0.25f * rng.nextFloat()) }
        val translucent = rng.nextFloat() < 0.35f
        val stretch = if (fibre) 3.2f else 1f + 0.5f * rng.nextFloat()
        val tone = 40 + rng.nextInt(30)
        val warm = rng.nextInt(10)
        // value noise for the surface
        val g = 9; val lat = FloatArray(g * g) { rng.nextFloat() }
        fun noise(x: Float, y: Float): Float {
            val fx = x * (g - 1); val fy = y * (g - 1)
            val ix = fx.toInt().coerceIn(0, g - 2); val iy = fy.toInt().coerceIn(0, g - 2)
            val tx = fx - ix; val ty = fy - iy
            val a = lat[iy * g + ix]; val b = lat[iy * g + ix + 1]; val c = lat[(iy + 1) * g + ix]; val d = lat[(iy + 1) * g + ix + 1]
            return (a + (b - a) * tx) + ((c + (d - c) * tx) - (a + (b - a) * tx)) * ty
        }
        for (y in 0 until n) for (x in 0 until n) {
            val u = (x + 0.5f) / n * 2f - 1f; val v = (y + 0.5f) / n * 2f - 1f
            val uu = u * stretch; val vv = v
            val rho = kotlin.math.sqrt(uu * uu + vv * vv) / 0.9f * (if (fibre) 1f else 1.15f)
            val th = kotlin.math.atan2(vv, uu)
            var edge = if (fibre) 0.28f else 0.62f
            for (k in 2 until harm) edge += amp[k] * edge * kotlin.math.sin(k * th + ph[k])
            for ((ba, bw, bd) in bites) {
                var dth = kotlin.math.abs(th - ba); if (dth > Math.PI) dth = (2 * Math.PI - dth).toFloat()
                if (dth < bw) edge *= 1f - bd * (1f - dth / bw)
            }
            if (fibre) edge *= 3.2f                              // a fibre is thin across, long along
            val rr = if (fibre) kotlin.math.abs(vv) / 0.9f * 3.2f + kotlin.math.max(0f, kotlin.math.abs(uu) / 0.9f - 2.6f) * 3f else rho
            val e = if (fibre) 0.9f else edge
            val inside = ((e - rr) / 0.07f + 0.5f).coerceIn(0f, 1f)
            if (inside <= 0f) continue
            val tex = 0.55f + 0.45f * noise(x / n.toFloat(), y / n.toFloat()) + 0.15f * (rng.nextFloat() - 0.5f)
            var a = inside * tex.coerceIn(0f, 1f)
            if (translucent) a *= 0.45f + 0.55f * (rr / e).coerceIn(0f, 1f)
            val l = (tone + 30 * (1f - tex)).toInt().coerceIn(0, 255)
            px[y * n + x] = ((a * 255).toInt().coerceIn(0, 255) shl 24) or ((l + warm).coerceAtMost(255) shl 16) or (l shl 8) or (l - warm / 2).coerceAtLeast(0)
        }
        return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
}

private const val LOUPE_AGSL = """
uniform shader image;
uniform float2 size;
uniform float t;          // 0 at 2x, 1 by 3.5x

half4 main(float2 p) {
    float2 c = size * 0.5;
    // the loupe's field: a circle the table's width, closing in from beyond the corners
    float R = mix(length(size) * 0.75, min(size.x, size.y) * 0.52, t);
    float2 d = (p - c) / R;
    float r2 = dot(d, d);
    float r = sqrt(r2);
    // A magnifier's pincushion, each colour its own amount, and softer toward the rim (the
    // glass's field curves). Sixteen samples spread over a small disc and along the spectrum
    // (red pulled in, blue pushed out), each pixel's pattern turned at random, so the
    // fringes and the softness melt into a blur instead of stacking up as copies.
    float k = 0.10 * t;
    float ca = 0.012 * t;
    float blur = t * (0.8 + 5.0 * r2 * r2);
    float jit = fract(52.9829189 * fract(dot(p, float2(0.06711056, 0.00583715))));
    half3 col = half3(0);
    half3 wsum = half3(0);
    for (int i = 0; i < 16; i++) {
        float fi = (float(i) + 0.5) / 16.0;
        float s = fi * 2.0 - 1.0;                       // -1 red .. +1 blue
        float2 q = c + (p - c) * (1.0 - k * r2 + ca * r2 * s);
        float a = float(i) * 2.39996 + jit * 6.2832;
        float rr = blur * sqrt(fract(fi * 7.0 + jit));
        q += float2(cos(a), sin(a)) * rr;
        half3 w = half3(clamp(1.0 - (s + 1.0) * 0.75, 0.0, 1.0),
                        clamp(1.0 - abs(s) * 1.2, 0.0, 1.0),
                        clamp(1.0 - (1.0 - s) * 0.75, 0.0, 1.0));
        col += image.eval(q).rgb * w;
        wsum += w;
    }
    col /= wsum;
    // falling off inside the glass, dark past its edge (the barrel), a faint glint on the rim
    col *= 1.0 - 0.35 * t * r2 * r2;
    float outside = smoothstep(0.95, 1.07, r);          // a soft edge: the barrel is out of focus
    col = mix(col, half3(0.02, 0.02, 0.025), outside * t);
    // The lit glass blooms past its edge into the dark: the light just inside the rim,
    // gathered along it (soft, not a copy), spreading out a tight halo and a broad glow.
    if (r > 0.9) {
        float2 dir = d / max(r, 1e-4);
        float2 tang = float2(-dir.y, dir.x);
        float out_ = max(r - 0.985, 0.0);
        // gathered over an arc that widens the further out the light has travelled, from two
        // depths inside the rim, each pixel's samples shifted at random: a soft spread, no rays
        float spread = 0.08 + out_ * 2.2;
        half3 rim = half3(0);
        for (int i = 0; i < 14; i++) {
            float fi = (float(i) + jit) / 14.0;
            float2 q = c + (dir * (0.86 + 0.07 * step(0.5, fract(float(i) * 0.5))) + tang * (fi * 2.0 - 1.0) * spread) * R;
            rim += image.eval(q).rgb;
        }
        rim /= 14.0;
        float bloom = 0.55 * exp(-out_ / 0.035) + 0.22 * exp(-out_ / 0.16);
        col += rim * rim * half3(1.0, 0.97, 0.92) * bloom * t * smoothstep(0.9, 0.99, r);
    }
    col += half3(0.10) * t * exp(-pow((r - 0.985) / 0.025, 2.0)) * (0.6 + 0.4 * d.y * -1.0);
    return half4(col, 1.0);
}
"""

/** From 2x, the view seen through a loupe (no loupe drawn: only what its glass does). */
@Composable
private fun Modifier.loupe(scale: Float): Modifier {
    if (Build.VERSION.SDK_INT < 33) return this
    val t = ((scale - 2f) / 1.5f).coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }
    val shader = remember { RuntimeShader(LOUPE_AGSL) }
    return this.graphicsLayer {
        if (t <= 0.001f) { renderEffect = null; return@graphicsLayer }
        shader.setFloatUniform("size", size.width, size.height)
        shader.setFloatUniform("t", t)
        clip = true
        renderEffect = RenderEffect.createRuntimeShaderEffect(shader, "image").asComposeRenderEffect()
    }
}
