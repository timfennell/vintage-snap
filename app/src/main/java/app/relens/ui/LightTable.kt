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
    var scale by remember { mutableFloatStateOf(initialScale) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var viewing by remember { mutableStateOf<DarkroomEntry?>(null) }
    val measurer = rememberTextMeasurer()
    // The session's frames by roll, oldest first, cut into strips of six.
    val byRoll = body.darkroom.groupBy { it.roll }.toSortedMap().mapValues { (_, l) -> l.sortedBy { it.frame } }
    val strips = byRoll.flatMap { (_, frames) -> frames.chunked(6) }

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
            fun stripRect(i: Int, n: Int): Rect {
                val col = i % cols; val row = i / cols
                val x = (6f + col * 41f) * mm
                val y = top + row * (stripLen(6) + 10f) * mm
                return Rect(Offset(x, y), Size(35f * mm, stripLen(n) * mm))
            }
            fun frameRect(strip: Rect, j: Int) = Rect(Offset(strip.left + 5.5f * mm, strip.top + (2f + j * 38f + 1f) * mm), Size(24f * mm, 36f * mm))
            fun toSheet(p: Offset) = (p - pan) / scale

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
                    for ((i, st) in strips.withIndex()) {
                        val r = stripRect(i, st.size)
                        drawStrip(r, mm, st, i, frameRect = { j -> frameRect(r, j) }, measurer = measurer)
                    }
                }
                if (strips.isEmpty()) {
                    val t = measurer.measure("Nothing developed yet this session.", TextStyle(color = Color(0xFF55524C), fontSize = 14.sp, fontFamily = Futuraish))
                    drawText(t, topLeft = Offset((size.width - t.size.width) / 2, size.height * 0.4f))
                }
            }
        }
        // The table's edge: back, the DNG switch; along the foot what's on the glass.
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            // dark on the lit glass; light once the loupe's barrel darkens the view
            val ink = if (scale >= 3f) Palette.cream else Color(0xFF3A3530)
            Text("‹", color = ink, fontSize = 28.sp, modifier = Modifier.clickable(onClick = onBack).padding(end = 10.dp))
            Text("LIGHT TABLE", color = ink, fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Spacer(Modifier.weight(1f))
            Text("DNG " + if (body.dngOut) "ON" else "OFF", color = if (body.dngOut) Color(0xFF1E8A5C) else Color(0xFF7A746C),
                fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0x22000000)).clickable { body.toggleDng() }
                    .padding(horizontal = 10.dp, vertical = 5.dp))
        }
        val rolls = byRoll.keys.joinToString { "roll $it" }
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
 * A frame held up on its own: the print, full screen; hold it to see the phone's own
 * picture (what the lens and film made of it); tap to put it back.
 */
@Composable
private fun PrintView(e: DarkroomEntry, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    var big by remember(e) { mutableStateOf<Bitmap?>(null) }
    var showOriginal by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(e) {
        big = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            e.file?.takeIf { it.exists() }?.let { f -> runCatching {
                val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(f.absolutePath, o)
                var ss = 1; while (maxOf(o.outWidth, o.outHeight) / (ss * 2) >= 2400) ss *= 2
                android.graphics.BitmapFactory.decodeFile(f.absolutePath, android.graphics.BitmapFactory.Options().apply { inSampleSize = ss })
            }.getOrNull() }
        }
    }
    // after a restart the phone's picture isn't in memory: the negative it was shot as (uncropped)
    var negative by remember(e) { mutableStateOf<Bitmap?>(null) }
    androidx.compose.runtime.LaunchedEffect(e) {
        if (e.original != null) return@LaunchedEffect
        negative = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching {
            val dir = e.file?.parentFile?.parentFile?.let { java.io.File(it, "negatives") } ?: return@runCatching null
            val f = dir.listFiles { x -> x.name.startsWith("R%03d_F%02d_".format(e.roll, e.frame)) && x.name.count { it == '.' } == 1 && x.name.endsWith(".jpg") }
                ?.maxByOrNull { it.lastModified() } ?: return@runCatching null
            val b = android.graphics.BitmapFactory.decodeFile(f.absolutePath, android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 })
            val deg = when (androidx.exifinterface.media.ExifInterface(f).getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, 1)) {
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f }
            if (deg == 0f) b else Bitmap.createBitmap(b, 0, 0, b.width, b.height, android.graphics.Matrix().apply { postRotate(deg) }, true)
        }.getOrNull() }
    }
    val source = e.original ?: negative
    val shown = (if (showOriginal) source else null) ?: big ?: e.preview
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var sharing by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Color(0xF2050505))
        .pointerInput(e) { detectTapGestures(onTap = { if (sharing) sharing = false else onClose() }, onLongPress = { sharing = true }) },
        contentAlignment = Alignment.Center) {
        shown?.let { androidx.compose.foundation.Image(it.asImageBitmap(), null, contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            modifier = Modifier.fillMaxSize().padding(12.dp)) }
        // before / after: the phone's own picture against the print
        if (source != null) Text(if (showOriginal) "AFTER" else "BEFORE", color = Palette.cream, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp).clip(RoundedCornerShape(4.dp))
                .background(Color(0x55000000)).clickable { showOriginal = !showOriginal }.padding(horizontal = 12.dp, vertical = 6.dp))
        Text((if (showOriginal) "the phone's own picture · " else "") + e.label + "  ·  hold to share",
            color = Palette.cream, fontSize = 11.sp,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp))
        if (sharing) ShareSheet(e, Modifier.align(Alignment.BottomCenter)) { action ->
            sharing = false
            action?.let { (file, mime, open) -> handOff(ctx, file, mime, open) }
        }
    }
}

/**
 * Long-press on a print: hand it on. Share (Instagram, Photos, a message: Android's share
 * sheet) or open in another app (an editor: Snapseed takes the DNG), the print or its DNG.
 */
@Composable
private fun ShareSheet(e: DarkroomEntry, modifier: Modifier, onPick: (Triple<java.io.File, String, Boolean>?) -> Unit) {
    val jpg = e.file?.takeIf { it.exists() }
    val dng = jpg?.let { java.io.File(it.parentFile, it.nameWithoutExtension + ".dng") }?.takeIf { it.exists() }
    androidx.compose.foundation.layout.Column(modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp)
        .clip(RoundedCornerShape(10.dp)).background(Color(0xF0141312)).padding(vertical = 6.dp)) {
        @Composable fun item(label: String, sub: String, pick: Triple<java.io.File, String, Boolean>?) {
            androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth()
                .then(if (pick != null) Modifier.clickable { onPick(pick) } else Modifier).padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(label, color = if (pick != null) Palette.cream else Palette.dim, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(sub, color = Palette.dim, fontSize = 10.sp)
            }
        }
        item("Share the print", "Instagram, Photos, a message…", jpg?.let { Triple(it, "image/jpeg", false) })
        item("Open the print in…", "an editor or viewer", jpg?.let { Triple(it, "image/jpeg", true) })
        item("Share the DNG", if (dng != null) "the relensed raw, no film look · %d MB".format(dng.length() / 1_000_000) else "no DNG for this frame (the light table's DNG switch was off)",
            dng?.let { Triple(it, "image/x-adobe-dng", false) })
        item("Open the DNG in…", "Snapseed, Lightroom…", dng?.let { Triple(it, "image/x-adobe-dng", true) })
        item("Cancel", "", null)
    }
}

/** Hands [file] to another app: the share sheet, or (open) an app that views or edits it. */
private fun handOff(ctx: android.content.Context, file: java.io.File, mime: String, open: Boolean) {
    runCatching {
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
        val intent = if (open) android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri, mime)
            else android.content.Intent(android.content.Intent.ACTION_SEND).setType(mime).putExtra(android.content.Intent.EXTRA_STREAM, uri)
        intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (!open) intent.clipData = android.content.ClipData.newRawUri(file.name, uri)
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
            val r = (0.4f + 1.6f * rng.nextFloat() * rng.nextFloat()) * px
            val c = Offset(ox + rng.nextFloat() * tile, oy + rng.nextFloat() * tile)
            drawOval(Color(0xFF404040).copy(alpha = 0.12f + 0.35f * rng.nextFloat()), c, Size(2 * r * 2, 2 * r * 2 * (0.6f + 0.4f * rng.nextFloat())))
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
                                measurer: androidx.compose.ui.text.TextMeasurer) {
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
                val msg = if (e.status.startsWith("failed")) "failed" else "in the darkroom"
                val t = measurer.measure(msg, TextStyle(color = Color(0x99FFE6C7), fontSize = (2.0f * mm).toSp(), fontFamily = Futuraish))
                drawText(t, topLeft = Offset(fr.center.x - t.size.width / 2f, fr.center.y - t.size.height / 2f))
                continue
            }
            drawFrame(bmp, fr)
        }
        // dust printed in with the pictures; now and then a hair
        repeat(2 + rng.nextInt(3) + frames.size * 2) {
            val p = Offset(r.left + rng.nextFloat() * r.width, r.top + rng.nextFloat() * r.height)
            val rad = (0.08f + 0.25f * rng.nextFloat() * rng.nextFloat()) * mm
            drawOval(Color(0xB3EBEBEB), p - Offset(rad, rad * 0.8f), Size(2 * rad, 1.6f * rad))
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
    // a magnifier's pincushion, each colour its own amount
    float k = 0.10 * t;
    float ca = 0.010 * t;
    float2 sg = c + (p - c) * (1.0 - k * r2);
    float2 sr = c + (p - c) * (1.0 - k * r2 - ca * r2);
    float2 sb = c + (p - c) * (1.0 - k * r2 + ca * r2);
    // softer toward the rim (the glass's field curves)
    float blur = t * 4.0 * r2 * r2;
    half3 col = half3(0);
    for (int i = 0; i < 4; i++) {
        float a = float(i) * 1.5708 + 0.785;
        float2 o = float2(cos(a), sin(a)) * blur;
        col += half3(image.eval(sr + o).r, image.eval(sg + o).g, image.eval(sb + o).b);
    }
    col *= 0.25;
    // falling off inside the glass, dark past its edge (the barrel), a faint glint on the rim
    col *= 1.0 - 0.35 * t * r2 * r2;
    float outside = smoothstep(0.97, 1.04, r);
    col = mix(col, half3(0.02, 0.02, 0.025), outside * t);
    // The lit glass blooms past its edge into the dark: the light just inside the rim,
    // gathered along it (soft, not a copy), spreading out a tight halo and a broad glow.
    if (r > 0.9) {
        float2 dir = d / max(r, 1e-4);
        float2 tang = float2(-dir.y, dir.x);
        half3 rim = half3(0);
        for (int i = -3; i <= 3; i++) {
            float2 q = c + (dir * 0.9 + tang * float(i) * 0.07) * R;
            rim += image.eval(q).rgb;
        }
        rim /= 7.0;
        float out_ = max(r - 0.985, 0.0);
        float bloom = 0.55 * exp(-out_ / 0.035) + 0.22 * exp(-out_ / 0.16);
        col += rim * rim * half3(1.0, 0.97, 0.92) * bloom * t * smoothstep(0.9, 0.99, r);
    }
    col += half3(0.10) * t * exp(-pow((r - 0.985) / 0.012, 2.0)) * (0.6 + 0.4 * d.y * -1.0);
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
