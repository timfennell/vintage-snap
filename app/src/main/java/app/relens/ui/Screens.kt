package app.relens.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.relens.film.filmStocks
import app.relens.optics.Movement
import app.relens.optics.fits
import app.relens.optics.lensBag
import app.relens.optics.lensFilters
import app.relens.optics.lensCollections
import kotlin.math.pow

@Composable
private fun Panel(title: String, subtitle: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(Palette.leather).statusBarsPadding().navigationBarsPadding().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("‹", color = Palette.amber, fontSize = 28.sp, modifier = Modifier.clickable(onClick = onBack).padding(end = 12.dp))
            Text(title, color = Palette.amber, fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        }
        Text(subtitle, color = Palette.dim, fontSize = 11.sp, modifier = Modifier.padding(start = 26.dp, bottom = 8.dp))
        content()
    }
}

/** The camera bag: Vintage Click's shelves; tap a lens to mount it. */
@Composable
fun LensBagScreen(body: Body, haptics: Haptics, onBack: () -> Unit, startShelf: String? = null) {
    val mounted = body.lens
    var shelf by remember { mutableIntStateOf((lensCollections.map { it.name } + "Filters" + "Film" + "Focus").indexOf(startShelf).takeIf { it >= 0 }
        ?: lensCollections.indexOfFirst { it.name == mounted.collection }.coerceAtLeast(0)) }
    val filtersShelf = lensCollections.size
    val filmShelf = lensCollections.size + 1
    val focusShelf = lensCollections.size + 2
    Panel("CAMERA BAG", when (shelf) {
        filtersShelf -> "screw-in and drop-in filters: colour for b&w and colour film, ND, effects"
        filmShelf -> "film: loading a roll rewinds the one in the camera (frame ${body.framesShot} of ${body.stock.exposures})"
        focusShelf -> "focus calibration: each of the phone's cameras, once, against a tape measure"
        else -> lensCollections[shelf].about }, onBack) {
        // the shelves' tabs, scrolled so the open one shows (Filters and Film sit at the end)
        val tabs = rememberScrollState()
        androidx.compose.runtime.LaunchedEffect(tabs.maxValue) { if (shelf >= filtersShelf) tabs.scrollTo(tabs.maxValue) }
        Row(Modifier.horizontalScroll(tabs).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (lensCollections.map { it.name } + "Filters" + "Film" + "Focus").forEachIndexed { i, name ->
                Text(name, color = if (i == shelf) Palette.leather else Palette.cream, fontSize = 12.sp,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(if (i == shelf) Palette.amber else Palette.panel)
                        .clickable { shelf = i; haptics.tick() }.padding(horizontal = 10.dp, vertical = 6.dp))
            }
        }
        if (shelf == filtersShelf) { FilterShelf(body, haptics, onBack); return@Panel }
        if (shelf == filmShelf) { FilmShelf(body, haptics, onBack); return@Panel }
        if (shelf == focusShelf) { FocusShelf(body, haptics, onBack); return@Panel }
        // Read the shelf here, not inside the lazy list's content lambda: that runs on a
        // snapshot change, and can see the Filters index before this recomposes away.
        val lenses = lensCollections.getOrNull(shelf)?.lenses.orEmpty().filter { body.rig.framingFor(it) != null }
        LazyColumn(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Palette.panel)) {
            // Only the lenses a phone camera can frame (not wider than the ultra-wide, not
            // so long the crop leaves under ~1 MP); each with what it leaves.
            itemsIndexed(lenses) { _, lens ->
                val on = lens.name == mounted.name
                Column(Modifier.fillMaxWidth().background(if (on) Color(0x33E8B04A) else Color.Transparent)
                    .clickable {
                        body.mount(lensBag.indexOfFirst { it.name == lens.name })
                        haptics.lensMounted()
                        onBack()
                    }.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    // the name on its line, the specs on the next (a long note can't crush the name)
                    Text((if (on) "▶ " else "") + lens.name, color = if (on) Palette.amber else Palette.cream,
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    run {
                        val iris = if (lens.isPinhole) "pinhole ${lens.pinholeMM} mm" else if (lens.blades >= 3) "${lens.blades}-blade iris" else "round iris"
                        val move = when (val m = lens.movement) { is Movement.Tilt -> " · tilt ${m.maxDegrees.toInt()}°"; is Movement.Shift -> " · shift ${m.maxMM.toInt()} mm"; else -> "" }
                        val fr = body.rig.framingFor(lens)
                        val cam = fr?.cameraRole ?: "main"
                        // wider than any camera: shown at the widest view there is
                        val wider = fr != null && fr.camera.f36 > lens.focalMM * 1.005f
                        Text("$iris · close ${(lens.minFocusM * 100).toInt()} cm$move · $cam %.1f MP".format(fr?.megapixels ?: 0f) +
                            if (wider) " · at the ultra-wide's %.0f mm view".format(fr!!.camera.f36) else "",
                            color = Palette.amber.copy(alpha = 0.75f), fontSize = 11.sp)
                    }
                    Text(lens.inspiredBy, color = Palette.dim, fontSize = 11.sp)
                }
            }
        }
    }
}

/** The filters: tap one to screw it on (a rear-filter lens takes colour and ND only). */
@Composable
private fun FilterShelf(body: Body, haptics: Haptics, onBack: () -> Unit) {
    val lens = body.lens
    LazyColumn(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Palette.panel)) {
        itemsIndexed(lensFilters) { i, f ->
            val on = i == body.filterIndex
            val fits = lens.fits(f)
            Row(Modifier.fillMaxWidth().background(if (on) Color(0x33E8B04A) else Color.Transparent)
                .clickable(enabled = fits) { body.screwOn(i); haptics.click(); onBack() }
                .padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                // a swatch of the filter's colour
                Box(Modifier.size(18.dp).clip(RoundedCornerShape(9.dp)).background(
                    Color(f.transmission[0].coerceIn(0f, 1f), f.transmission[1].coerceIn(0f, 1f), f.transmission[2].coerceIn(0f, 1f))))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text((if (on) "▶ " else "") + f.name, color = when { on -> Palette.amber; fits -> Palette.cream; else -> Palette.dim },
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (fits) f.about else "doesn't fit the ${lens.name}: rear filters take colour and ND only",
                        color = Palette.dim, fontSize = 11.sp)
                }
                if (f.stops >= 0.25f) Text("-%.1f EV".format(f.stops), color = Palette.dim, fontSize = 11.sp)
            }
        }
    }
}

/** Loading film: the stock and how it will be rated (push / pull). Loading starts a fresh roll. */
@Composable
fun FilmScreen(body: Body, haptics: Haptics, onBack: () -> Unit) {
    Panel("LOAD FILM", "the roll in the camera (frame ${body.framesShot} of ${body.stock.exposures}) is rewound and put away", onBack) {
        FilmShelf(body, haptics, onBack)
    }
}

/** The film stocks, the rating (push / pull) and LOAD: on its own screen, and as the camera bag's Film shelf. */
@Composable
private fun ColumnScope.FilmShelf(body: Body, haptics: Haptics, onLoaded: () -> Unit) {
    var choice by remember { mutableIntStateOf(body.stockIndex) }
    var push by remember { mutableIntStateOf(body.push) }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Palette.panel)) {
            itemsIndexed(filmStocks) { i, s ->
                val on = i == choice
                Row(Modifier.fillMaxWidth().background(if (on) Color(0x33E8B04A) else Color.Transparent)
                    .clickable { choice = i; haptics.tick() }.padding(horizontal = 12.dp, vertical = 9.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text((if (on) "▶ " else "") + s.name, color = if (on) Palette.amber else Palette.cream, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(s.inspiredBy, color = Palette.dim, fontSize = 11.sp)
                    }
                    val kind = if (s.blackAndWhite) "B&W" else if (s.falseColourIR) "IR" else "colour"
                    Text("ISO ${s.iso.toInt()} · $kind · ${s.exposures} exp", color = Palette.dim, fontSize = 11.sp)
                }
            }
        }
        val iso = filmStocks[choice].iso
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center) {
            Text("◀", color = Palette.amber, fontSize = 22.sp, modifier = Modifier.clickable { if (push > -1) { push--; haptics.click() } }.padding(12.dp))
            Text("rate at EI ${(iso * 2f.pow(push)).toInt()}  " + when {
                push == 0 -> "(box speed)"; push > 0 -> "(push +$push)"; else -> "(pull $push)"
            }, color = Palette.amber, fontSize = 15.sp)
            Text("▶", color = Palette.amber, fontSize = 22.sp, modifier = Modifier.clickable { if (push < 3) { push++; haptics.click() } }.padding(12.dp))
        }
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Palette.amber)
            .clickable { body.loadRoll(choice, push); haptics.filmLoaded(); onLoaded() }.padding(14.dp), contentAlignment = Alignment.Center) {
            Text("LOAD ${filmStocks[choice].name.uppercase()}", color = Palette.leather, fontWeight = FontWeight.Bold)
        }
}

/** The darkroom: prints as they come out. Hold a print to see the camera's own picture. */
@Composable
fun DarkroomScreen(body: Body, onBack: () -> Unit, openBench: () -> Unit) {
    Panel("DARKROOM", "prints are saved to Pictures/Vintage Snap", onBack) {
        // The print in high dynamic range as a linear DNG beside it.
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Palette.panel).clickable { body.toggleDng() }
            .padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("DNG BESIDE EACH PRINT", color = Palette.cream, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text("the print in high dynamic range (film, grain and all), for a raw editor", color = Palette.dim, fontSize = 10.sp)
            }
            Text(if (body.dngOut) "ON" else "OFF", color = if (body.dngOut) Color(0xFF73F2B3) else Palette.dim, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Palette.panel).clickable { body.toggleRaw() }
            .padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("KEEP THE CAMERA RAW", color = Palette.cream, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text("the phone's own untouched RAW, saved to the gallery beside the print", color = Palette.dim, fontSize = 10.sp)
            }
            Text(if (body.rawOut) "ON" else "OFF", color = if (body.rawOut) Color(0xFF73F2B3) else Palette.dim, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(10.dp))
        if (body.darkroom.isEmpty()) {
            Text("Nothing developed yet.", color = Palette.dim, modifier = Modifier.padding(12.dp))
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(body.darkroom) { _, e ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Palette.panel).padding(8.dp)) {
                    val bmp = e.preview
                    if (bmp != null) {
                        Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth().aspectRatio(bmp.width / bmp.height.toFloat()))
                    } else {
                        Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                            Text(e.status, color = Palette.dim, fontSize = 12.sp)
                        }
                    }
                    Text(e.label, color = Palette.cream, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                    if (e.done) Text(e.status, color = Palette.dim, fontSize = 10.sp)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("performance bench ›", color = Palette.dim, fontSize = 11.sp,
            modifier = Modifier.align(Alignment.End).clickable(onClick = openBench).padding(6.dp))
        Spacer(Modifier.width(1.dp))
    }
}


/**
 * The camera bag's Focus shelf: each of the phone's cameras, how far its focus readings can be
 * trusted, and CALIBRATE (or REDO): three steps on the camera screen, infinity measured by
 * itself and two targets at distances measured with a tape.
 */
@Composable
private fun FocusShelf(body: Body, haptics: Haptics, onBack: () -> Unit) {
    val cams = remember(body.focusLearnTick) { body.calibCameras() }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("The phone reports where its lens is focused only roughly. Calibrate each camera once (a tape measure and two things to aim at); redo it if the distance readout ever disagrees with the tape.",
                color = Palette.dim, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
        }
        items(cams.size) { i ->
            val c = cams[i]
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Palette.panel).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${c.role.uppercase()} CAMERA", color = Palette.cream, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Text("%.0f mm equivalent · camera %s".format(c.camera.f36, c.camera.id), color = Palette.dim, fontSize = 10.sp)
                    Text(c.status, color = if (c.done) Color(0xFF73F2B3) else Palette.amber, fontSize = 11.sp)
                }
                if (c.camera.focusCalibration != 2) Text(if (c.done) "REDO" else "CALIBRATE", color = Palette.leather, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Palette.amber)
                        .clickable { body.startCalibration(c); haptics.click(); onBack() }.padding(horizontal = 12.dp, vertical = 8.dp))
            }
        }
    }
}
