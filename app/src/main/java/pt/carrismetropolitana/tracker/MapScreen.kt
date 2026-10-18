package pt.carrismetropolitana.tracker

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.widget.Toast
import android.graphics.Point
import android.graphics.PointF
import android.view.MotionEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import org.osmdroid.views.Projection
import kotlin.math.hypot

private const val DEFAULT_LAT = 38.7577
private const val DEFAULT_LON = -9.2360

/** Screen radius of a bus dot, in dp. */
private const val BUS_DOT_DP = 9f

/** Taps further than this (dp) from a dot count as hitting it. */
private const val BUS_TAP_SLOP_DP = 22f

/**
 * Below this zoom, bus dots stop carrying their line number.
 *
 * At the default zoom 12 with 60+ buses on screen, a number on every dot is an unreadable
 * wall of overlapping text. Above this, there is room for them to be useful.
 */
private const val LABEL_MIN_ZOOM = 14.0

/** Zoom used when framing saved stops, where they fill a reasonable part of the screen. */
private const val SAVED_STOPS_ZOOM = 13.5

/**
 * Zoom above which a bus dot also carries the name of the stop it is heading to.
 *
 * Higher than [LABEL_MIN_ZOOM]: these are much longer than a line number, so they need more
 * room before they stop being clutter.
 */
private const val STOP_NAME_MIN_ZOOM = 15.0

/** Stop names are truncated to this; the dots are small and text does not wrap. */
private const val MAX_STOP_NAME_CHARS = 20

/**
 * Zoom for the first frame of the map.
 *
 * The MapView was constructed at zoom 12 and never changed, which frames most of metropolitan
 * Lisbon. Fine for seeing the whole network, useless for finding the stop you are standing at.
 */
private const val INITIAL_ZOOM = 15.0

private const val OSM_ATTRIBUTION = "© OpenStreetMap contributors"

/** Height of the hairline between the two providers' chips. Roughly a FilterChip's height. */
private val CHIP_DIVIDER_HEIGHT = 28.dp

/** Shown when the re-centre button is tapped before the location provider has a fix. */
private const val LOCATION_PENDING = "Ainda a obter a sua localização…"

@Composable
fun MapScreen(state: AppState, padding: PaddingValues) {
    val context = LocalContext.current
    val now by state.now.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    val lines by state.lines.collectAsState()
    val vehicles by state.vehicles.collectAsState()
    val shapes by state.shapes.collectAsState()
    val stops by state.stops.collectAsState()
    val allStops by state.allStops.collectAsState()

    /**
     * Filters by **short name**, not by route id.
     *
     * A rider-facing line can be several `route_id`s: 746 is `108_0` (Marquês Pombal - Estação
     * Damaia) and `108_3` (Rossio - Sul e Sueste). Filtering by route id would split one line
     * into two chips both labelled 746, and a tap would only ever show half of it.
     */
    var filter by remember { mutableStateOf<String?>(null) }

    /** Every route id belonging to the filtered short name; empty when unfiltered. */
    val filterIds = remember(lines, filter) {
        if (filter == null) emptySet() else lines.filter { it.shortName == filter }.map { it.id }.toSet()
    }
    var selected by remember { mutableStateOf<Vehicle?>(null) }

    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants -> hasLocationPermission = grants.values.any { it } }

    LaunchedEffect(Unit) {
        Configuration.getInstance().load(
            context,
            context.getSharedPreferences("osmdroid", android.content.Context.MODE_PRIVATE)
        )
        Configuration.getInstance().userAgentValue = context.packageName
        if (!hasLocationPermission) {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }

    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(12.0)
            controller.setCenter(GeoPoint(DEFAULT_LAT, DEFAULT_LON))
        }
    }
    val myLocationOverlay = remember { MyLocationNewOverlay(GpsMyLocationProvider(context), mapView) }

    /**
     * OSM tile attribution.
     *
     * osmdroid 6.1.20 does not add this itself — neither MapView nor TilesOverlay constructs a
     * CopyrightOverlay — and the attribution is a condition of using the tiles. It has to be
     * re-added after every `overlays.clear()` below, or it disappears on the first frame.
     */
    val copyrightOverlay = remember {
        CopyrightOverlay(context).apply {
            setCopyrightNotice(OSM_ATTRIBUTION)
            setAlignBottom(true)
            setAlignRight(true)
            setOffset(4, 4)
        }
    }

    /** Rebuilt on every state change: it draws straight from the latest lists. */
    val busOverlay = remember {
        BusOverlay(context) { onTap -> selected = onTap }
    }

    /** The user's own stops, which were previously never drawn at all. */
    val stopOverlay = remember { SavedStopOverlay(context, AndroidColor.parseColor("#C61D23")) }

    fun visibleVehicles(): List<Vehicle> =
        if (filter == null) vehicles else vehicles.filter { it.lineId in filterIds }

    /** Saved stops that have coordinates, for framing. */
    val savedPoints = remember(stops) {
        stops.mapNotNull { s ->
            val lat = s.lat
            val lon = s.lon
            if (lat == null || lon == null) null else GeoPoint(lat, lon)
        }
    }

    /**
     * What the frame button frames.
     *
     * Unfiltered, framing every vehicle across both networks zooms out to a scale where no
     * individual bus means anything — the two feeds together span most of the metro area. The
     * user's own stops are the more meaningful anchor, so that is the fallback.
     */
    val fitTargets = remember(filter, filterIds, savedPoints, vehicles) {
        if (filter != null) visibleVehicles().map { GeoPoint(it.lat, it.lon) } else savedPoints
    }
    val canFit = fitTargets.isNotEmpty()

    /**
     * Pulls the stop catalog in so saved stops can be given coordinates.
     *
     * Without this, stops saved before coordinates were recorded never got them — the backfill
     * only ran when the stop picker was opened — so the frame button stayed permanently
     * disabled on this tab.
     */
    LaunchedEffect(Unit) { state.ensureAllStops() }

    /**
     * Frame something sensible the first time the map lays out, then never again.
     *
     * The hardcoded default centre sat in Carris Metropolitana territory, which was reasonable
     * before Lisbon was covered and is less so now. This prefers the user's location, falls
     * back to their saved stops, and leaves the default if neither is available. Guarded by
     * osmdroid's first-layout callback so it never fights a deliberate pan or zoom.
     *
     * The zoom is set here too, and only when there is something to centre on. It used to keep
     * the MapView's constructor value of 12, which frames most of metropolitan Lisbon. Leaving
     * the zoom alone when no centre is known would pin a closer zoom to the wrong part of the map.
     */
    LaunchedEffect(Unit) {
        mapView.addOnFirstLayoutListener { _, _, _, _, _ ->
            val target = if (hasLocationPermission) {
                myLocationOverlay.myLocation ?: savedPoints.firstOrNull()
            } else {
                savedPoints.firstOrNull()
            }
            if (target != null) mapView.controller.animateTo(target, INITIAL_ZOOM, null)
        }
    }


    // Shapes carry ~1 700 points each; rebuilding these on every recomposition (every 20 s
    // poll, every tap) was the map's frame cost. Rebuilt only when the routes actually change.
    val routeOverlays = remember(shapes, lines, filter) {
        lines.filter { filter == null || it.shortName == filter }.mapNotNull { line ->
            val points = shapes[line.id].orEmpty()
            if (points.size < 2) null
            else Polyline().apply {
                outlinePaint.color = AndroidColor.parseColor(line.color)
                outlinePaint.strokeWidth = 12f
                setPoints(points.map { (lat, lon) -> GeoPoint(lat, lon) })
            }
        }
    }
    val busColor = remember(lines) { lines.associate { it.id to it.color } }

    /**
     * Stop id to name, for the label under each bus dot.
     *
     * From the catalog, which the map tab already loads. It is a 12 700 stop list that arrives
     * from disk or the network, so this is empty for the first moments and the labels simply
     * appear once it does — no separate fetch.
     */
    val stopNames = remember(allStops) { allStops.orEmpty().associate { it.id to it.name } }

/**
 * Live vehicles per rider-facing line, so a chip can say whether anything is running.
 *
 * A chip for a line with no buses right now looked exactly like one with a dozen, which
 * made an empty line look like a bug.
 */
val busCount = remember(vehicles, lines) {
    vehicles.groupingBy { lines.shortNameOf(it.lineId) }.eachCount()
}

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            myLocationOverlay.disableMyLocation()
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onPause()
        }
    }

    MapLayout(
        padding = padding,
        filterRow = {
            LineFilterRow(lines = lines, busCount = busCount, filter = filter) { filter = it }
        }
    ) { mapModifier ->
        AndroidView(
            modifier = mapModifier,
            factory = { mapView },
            update = { map ->
                    map.overlays.clear()
                    map.overlays.addAll(routeOverlays)

                    // Above the route lines, below the buses.
                    stopOverlay.stops = stops
                    map.overlays.add(stopOverlay)

                    if (hasLocationPermission) {
                        myLocationOverlay.enableMyLocation()
                        map.overlays.add(myLocationOverlay)
                    }

                    // Added last so buses always sit on top of the route lines.
                    busOverlay.vehicles = visibleVehicles()
                    busOverlay.lineColor = { id -> busColor[id] ?: "#888888" }
                    busOverlay.lineLabel = { id -> lines.shortNameOf(id) }
                    // With a filter on, or a bus selected, the dots are few enough to label even
                    // when zoomed out — that is the case where the number actually matters.
                    busOverlay.forceLabels = filter != null || selected != null
                    map.overlays.add(busOverlay)

                    // After the clear, or the attribution would be gone from the first frame.
                    map.overlays.add(copyrightOverlay)

                    map.invalidate()
                }
            )

            Column(
                Modifier.align(Alignment.TopEnd).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.End
            ) {
                // Elevated rather than a flat background: these float over map tiles and were
                // previously hard to separate from a route polyline of a similar colour.
                MapControl(
                    onClick = {
                        if (filter == null) mapView.fitTo(fitTargets, SAVED_STOPS_ZOOM)
                        else mapView.fitTo(fitTargets, null)
                    },
                    enabled = canFit,
                    contentDescription = if (filter == null) "Ver as minhas paragens" else "Ver autocarros da linha"
                ) {
                    Icon(
                        Icons.Default.CenterFocusStrong,
                        contentDescription = null,
                        tint = if (canFit) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    )
                }
                if (hasLocationPermission) {
                    MapControl(
                        onClick = {
                            // null until the provider has a first fix, which used to make this
                            // button do nothing at all.
                            val fix = myLocationOverlay.myLocation
                            if (fix == null) {
                                Toast.makeText(context, LOCATION_PENDING, Toast.LENGTH_SHORT).show()
                            } else {
                                mapView.controller.animateTo(fix)
                                mapView.controller.setZoom(15.0)
                            }
                        },
                        enabled = true,
                        contentDescription = "A minha localização"
                    ) {
                        Icon(
                            Icons.Default.MyLocation,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Stacked rather than both pinned to BottomCenter: selecting a bus without location
            // permission used to draw the two cards on top of each other.
            Column(
                Modifier.align(Alignment.BottomCenter).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!hasLocationPermission) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Text(
                            "Ative a localização para se ver no mapa",
                            Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                selected?.let { bus ->
                    VehicleCard(
                        vehicle = bus,
                        lineColor = lines.find { it.id == bus.lineId }?.color ?: "#888888",
                        lineLabel = lines.shortNameOf(bus.lineId),
                        headsign = state.headsignOf(bus),
                        hint = state.hintFor(bus),
                        now = now,
                        onClose = { selected = null }
                    )
                }
            }
        }
}

/**
 * The map tab's frame: the filter row on top, the map filling everything else.
 *
 * Split out of [MapScreen] so the layout itself can be measured in a test. 1.3.0 shipped a
 * layout bug here — the chip row claimed the full available height and left the map a strip —
 * that all 34 unit tests passed straight through.
 *
 * `map` is a [BoxScope] receiver so callers can `align` controls over the map.
 */
@Composable
internal fun MapLayout(
    padding: PaddingValues,
    filterRow: @Composable () -> Unit,
    map: @Composable BoxScope.(Modifier) -> Unit
) {
    Column(Modifier.fillMaxSize().padding(padding)) {
        filterRow()
        Box(Modifier.weight(1f).clipToBounds()) {
            map(Modifier.fillMaxSize())
        }
    }
}

/** Test tag on the line filter row, so its measured height can be asserted. */
internal const val FILTER_ROW_TAG = "filterRow"

/**
 * Horizontal line filter, one chip per rider-facing line.
 *
 * Kept separate from [MapLayout] so a test can render the row on its own and assert it stays
 * chip-sized rather than growing to fill its parent.
 */
@Composable
internal fun LineFilterRow(
    lines: List<LineInfo>,
    busCount: Map<String, Int>,
    filter: String?,
    onFilter: (String?) -> Unit
) {
    val filterLines = remember(lines) { lines.distinctBy { it.shortName } }

    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .testTag(FILTER_ROW_TAG),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilterChip(selected = filter == null, onClick = { onFilter(null) }, label = { Text("Todas") })

        var lastProvider: String? = null
        filterLines.forEach { line ->
            // Carris Metropolitana and CARRIS lines are different networks; without a break
            // between them the chip row reads as one arbitrary list.
            val provider = line.id.substringBefore(':')
            if (lastProvider != null && provider != lastProvider) {
                // A fixed height, deliberately not fillMaxHeight: inside a Row that asks for the
                // full available height, which made the chip row itself full-screen and left
                // the map a strip underneath it.
                Box(
                    Modifier
                        .height(CHIP_DIVIDER_HEIGHT)
                        .width(1.dp)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f))
                )
            }
            lastProvider = provider

            val count = busCount[line.shortName] ?: 0
            Box(Modifier.alpha(if (count == 0) 0.45f else 1f)) {
                FilterChip(
                    selected = filter == line.shortName,
                    onClick = { onFilter(if (filter == line.shortName) null else line.shortName) },
                    label = { Text(if (count == 0) line.shortName else "${line.shortName} · $count") },
                    leadingIcon = {
                        Box(
                            Modifier.size(12.dp).background(
                                Color(AndroidColor.parseColor(line.color)),
                                RoundedCornerShape(4.dp)
                            )
                        )
                    }
                )
            }
        }
    }
}

/**
 * The name of the stop a bus is heading to, for drawing under its dot, or null.
 *
 * `stop_id` in both feeds is exactly that: the stop the bus is at when stopped, and the one it
 * is travelling towards otherwise.
 *
 * Pure and separate from [BusOverlay] so the decision can be tested without a Canvas. Returns
 * null rather than a blank when there is no stop id, no name for it, the map is not zoomed in
 * far enough for text to fit, or the catalog has not loaded yet.
 */
fun busSubtitle(
    stopId: String?,
    stopNames: Map<String, String>,
    zoom: Double
): String? {
    if (zoom < STOP_NAME_MIN_ZOOM) return null
    val name = stopId?.let { stopNames[it] }?.trim().orEmpty()
    if (name.isEmpty()) return null
    return if (name.length <= MAX_STOP_NAME_CHARS) name
    else name.take(MAX_STOP_NAME_CHARS - 1).trimEnd() + "…"
}

/** A raised map control button. */
@Composable
private fun MapControl(
    onClick: () -> Unit,
    enabled: Boolean,
    contentDescription: String,
    content: @Composable () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 3.dp
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            // On the button rather than the icon: the icon passes null so the button owns the
            // single announcement.
            modifier = Modifier
                .size(44.dp)
                .semantics { this.contentDescription = contentDescription }
        ) {
            content()
        }
    }
}

/**
 * Frame [points] on screen.
 *
 * [fixedZoom] pins the zoom instead of fitting the box, for the saved-stops case where the
 * stops are close together and fitting them would zoom in absurdly far.
 */
private fun MapView.fitTo(points: List<GeoPoint>, fixedZoom: Double?): Boolean {
    if (points.isEmpty()) return false
    if (fixedZoom != null) {
        controller.animateTo(points.first(), fixedZoom, null)
        return true
    }
    val box = BoundingBox(
        points.maxOf { it.latitude },
        points.maxOf { it.longitude },
        points.minOf { it.latitude },
        points.minOf { it.longitude }
    )
    zoomToBoundingBox(box, false, 64)
    return true
}

private fun Vehicle.statusLabel(): String = when (status) {
    "STOPPED_AT" -> "parado na paragem"
    "INCOMING_AT" -> "a chegar"
    "IN_TRANSIT_TO" -> "em circulação"
    else -> status.lowercase()
}

@Composable
private fun VehicleCard(
    vehicle: Vehicle,
    lineColor: String,
    lineLabel: String,
    headsign: String?,
    hint: BusStopHint,
    now: Long,
    modifier: Modifier = Modifier,
    onClose: () -> Unit
) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(width = 46.dp, height = 26.dp)
                    .background(Color(AndroidColor.parseColor(lineColor)), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(lineLabel, color = Color.White, fontWeight = FontWeight.Black, fontSize = 12.sp)
            }
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                // The destination is the header, always: it is what identifies the bus, and it
                // is the one thing that differs between two buses of the same line. Anything
                // else is a detail underneath.
                Text(
                    headsign ?: vehicle.statusLabel(),
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                when (hint) {
                    is BusStopHint.Arriving -> Text(
                        "${hint.stopName} · ${Schedule.countdown(now + hint.seconds, now)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    BusStopHint.None -> Unit
                }

                // Carris Metropolitana reports a speed; CARRIS publishes no speed field, so its
                // number is derived from successive positions and is labelled as such rather
                // than presented as a reading. It is absent until two polls have been seen, and
                // below 8 m of movement, which is honest rather than showing a stale guess.
                // The vehicle number (6128 and friends) used to sit here too — it is the AVL
                // id, which matters to operators and to complaints, and to nobody waiting at
                // a stop. It stays on the model, where MotionTracker keys its windows.
                if (vehicle.speed > 0) {
                    val reported = vehicle.lineId.startsWith("$PROVIDER_CM:")
                    Text(
                        if (reported) "${vehicle.speed} km/h" else "${vehicle.speed} km/h · estimado",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Fechar", tint = Color.Gray)
            }
        }
    }
}

/**
 * Draws every bus as a line-coloured dot with its line number on top, so a stop can be picked
 * by reading the map instead of tapping blind. One overlay instead of N osmdroid Markers: no
 * per-frame bitmap churn, and text stays crisp at every zoom.
 */
private class BusOverlay(
    context: android.content.Context,
    private val onTap: (Vehicle) -> Unit
) : Overlay() {

    private val density = context.resources.displayMetrics.density
    private val radius = BUS_DOT_DP * density
    private val slop = BUS_TAP_SLOP_DP * density

    private val fill = Paint().apply { isAntiAlias = true }
    private val ring = Paint().apply {
        style = Paint.Style.STROKE
        color = AndroidColor.WHITE
        strokeWidth = 2f * density
    }
    private val label = Paint().apply {
        isAntiAlias = true
        color = AndroidColor.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        textSize = radius * 0.95f
    }

    /**
     * Stop name under the dot, with a dark halo.
     *
     * White text alone is unreadable over pale map tiles, and the overlay has no way to know
     * what is underneath, so it is drawn with a shadow rather than a background plate that
     * would cover the route line.
     */
    private val subtitle = Paint().apply {
        isAntiAlias = true
        color = AndroidColor.WHITE
        textAlign = Paint.Align.CENTER
        textSize = radius * 0.7f
        setShadowLayer(3f * density, 0f, 0f, AndroidColor.argb(210, 0, 0, 0))
    }

    var vehicles: List<Vehicle> = emptyList()
    var lineColor: (String) -> String = { "#888888" }

    /** Short name to draw on the dot; ids are namespaced and would be unreadable. */
    var lineLabel: (String) -> String = { it }

    /**
     * Draw line numbers even when zoomed out, because the on-screen set is small enough.
     *
     * Set when a line filter is active or a bus is selected — the cases where the number on
     * the dot is what the user is actually reading the map for.
     */
    var forceLabels: Boolean = false

    /** Stop id to stop name, for the label under the dot. Empty until the catalog loads. */
    var stopNames: Map<String, String> = emptyMap()

    private val drawn = mutableListOf<Pair<Vehicle, PointF>>()
    private val pixel = Point()

    override fun draw(canvas: Canvas, projection: Projection) {
        drawn.clear()
        // Read zoom from the projection rather than tracking zoom in Compose: this runs per
        // frame, and a stale value here would mean labels lag a pinch behind.
        val showLabels = forceLabels || projection.zoomLevel >= LABEL_MIN_ZOOM
        for (v in vehicles) {
            projection.toPixels(GeoPoint(v.lat, v.lon), pixel)
            val p = PointF(pixel.x.toFloat(), pixel.y.toFloat())
            drawn += v to p
            fill.color = runCatching { AndroidColor.parseColor(lineColor(v.lineId)) }.getOrDefault(AndroidColor.GRAY)
            canvas.drawCircle(p.x, p.y, radius, fill)
            canvas.drawCircle(p.x, p.y, radius, ring)
            if (showLabels) {
                canvas.drawText(lineLabel(v.lineId), p.x, p.y + label.textSize * 0.35f, label)
            }
            busSubtitle(v.stopId, stopNames, projection.zoomLevel)?.let { name ->
                canvas.drawText(name, p.x, p.y + radius + subtitle.textSize * 1.15f, subtitle)
            }
        }
    }

    override fun onSingleTapConfirmed(e: MotionEvent, mapView: MapView): Boolean {
        val hit = drawn.minByOrNull { (_, p) -> hypot(p.x - e.x, p.y - e.y) }
        if (hit != null && hypot(hit.second.x - e.x, hit.second.y - e.y) <= slop) {
            onTap(hit.first)
            return true
        }
        return false
    }
}

/**
 * The user's own stops, drawn as a bus stop sign.
 *
 * They were not drawn at all before 2.0.2. The only stop text on the map was [busSubtitle] --
 * the name under each *bus* dot, of the stop that bus is heading to -- which reads at a glance
 * like a saved stop whenever a bus happens to be near one, and is not one.
 *
 * A round blob was the first attempt and was wrong for the same reason the bus dots are not
 * enough: it looked like another bus. A sign with a pointer reads as a place, points at the
 * exact coordinate, and carries a bus pictogram so what it marks is unambiguous. Drawn above
 * the route lines and below the buses, so a bus is never hidden behind a stop.
 */
private class SavedStopOverlay(
    context: android.content.Context,
    private val color: Int
) : Overlay() {

    private val density = context.resources.displayMetrics.density

    /** Half the sign's width, and half its height above the pointer. */
    private val halfW = 13f * density
    private val halfH = 11f * density
    private val pointerH = 7f * density
    private val pointerW = 10f * density

    private val sign = Paint().apply { isAntiAlias = true; color = color }
    private val edge = Paint().apply {
        style = Paint.Style.STROKE
        color = AndroidColor.WHITE
        strokeWidth = 2f * density
    }
    private val glyph = Paint().apply { isAntiAlias = true; color = AndroidColor.WHITE }

    /** Stop name under the marker, always shown -- there are only ever a handful of them. */
    private val caption = Paint().apply {
        isAntiAlias = true
        color = AndroidColor.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        textSize = 9f * density
        setShadowLayer(3f * density, 0f, 0f, AndroidColor.argb(210, 0, 0, 0))
    }

    var stops: List<SavedStop> = emptyList()

    private val pixel = Point()
    private val pointer = android.graphics.Path()
    private val panel = android.graphics.RectF()

    override fun draw(canvas: Canvas, projection: Projection) {
        for (s in stops) {
            val lat = s.lat
            val lon = s.lon
            // Stops saved before coordinates were recorded have none until the catalog backfills.
            if (lat == null || lon == null) continue
            projection.toPixels(GeoPoint(lat, lon), pixel)
            val x = pixel.x.toFloat()
            val y = pixel.y.toFloat()

            val signBottom = y - pointerH
            val signTop = signBottom - halfH * 2

            // Pointer first, so the panel's bottom edge covers its base.
            pointer.reset()
            pointer.moveTo(x, y)
            pointer.lineTo(x - pointerW / 2, signBottom + density)
            pointer.lineTo(x + pointerW / 2, signBottom + density)
            pointer.close()
            canvas.drawPath(pointer, sign)

            panel.set(x - halfW, signTop, x + halfW, signBottom)
            val corner = 3f * density
            canvas.drawRoundRect(panel, corner, corner, sign)
            canvas.drawRoundRect(panel, corner, corner, edge)

            // A bus, front on: body, windscreen, two lights. Legible at 26 dp without detail.
            val bodyW = halfW * 1.05f
            val bodyH = halfH * 1.15f
            val bodyLeft = x - bodyW / 2
            val bodyTop = signTop + halfH * 0.35f
            canvas.drawRoundRect(
                bodyLeft,
                bodyTop,
                bodyLeft + bodyW,
                bodyTop + bodyH,
                1.5f * density,
                1.5f * density,
                glyph
            )
            canvas.drawRect(
                bodyLeft + bodyW * 0.16f,
                bodyTop + bodyH * 0.14f,
                bodyLeft + bodyW * 0.84f,
                bodyTop + bodyH * 0.46f,
                sign
            )
            val lightR = bodyW * 0.13f
            val lightY = bodyTop + bodyH * 0.70f
            canvas.drawCircle(bodyLeft + bodyW * 0.26f, lightY, lightR, sign)
            canvas.drawCircle(bodyLeft + bodyW * 0.74f, lightY, lightR, sign)

            canvas.drawText(truncate(s.name), x, y + caption.textSize * 1.25f, caption)
        }
    }

    /** Keeps a long stop name from sprawling across the map. */
    private fun truncate(name: String): String {
        val trimmed = name.trim()
        return if (trimmed.length <= MAX_STOP_NAME_CHARS) trimmed
        else trimmed.take(MAX_STOP_NAME_CHARS - 1).trimEnd() + "."
    }
}
