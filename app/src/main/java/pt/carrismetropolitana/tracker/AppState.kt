package pt.carrismetropolitana.tracker

import android.app.Application
import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/**
 * A stop the user saved.
 *
 * [lat]/[lon] are null for entries stored before they were recorded, and are backfilled from
 * the stop catalog the next time it loads. The map needs them to frame the user's stops when
 * no line filter is active.
 */
data class SavedStop(
    val id: String,
    val name: String,
    val lat: Double? = null,
    val lon: Double? = null
)

data class NearbyStop(val stop: StopInfo, val meters: Long)

/** How far a prediction may sit from its scheduled run before they stop being the same bus. */
private const val MATCH_WINDOW_SECONDS = 900L

/** How long a cached stop catalog stays usable before it is refetched. */
private const val CATALOG_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

/**
 * Map refresh for Carris Metropolitana, measured against the feed rather than guessed.
 *
 * It republishes positions every ~6 s. 9 s therefore always lands on fresh data while skipping
 * roughly one publish in three, which is the cheaper half of the trade: 109 KB gzipped per
 * poll is ~44 MB/hour, against ~95 MB/hour at 4 s. Going under ~6 s cannot show anything the
 * feed has not published yet.
 */
private const val CM_VEHICLE_POLL_MS = 9_000L

/**
 * Map refresh for CARRIS.
 *
 * Measured live, its positions only change every 12–14 s, so 15 s is the first interval that
 * reliably lands on new data. The gateway offers no compression, so each poll is ~48 KB.
 */
private const val CARRIS_VEHICLE_POLL_MS = 15_000L

/** Timetables and ETAs. These move in minutes, so there is nothing to gain from polling faster. */
private const val ARRIVAL_POLL_MS = 20_000L

/** Drives the countdown text, which only resolves to whole minutes. */
private const val CLOCK_TICK_MS = 10_000L

/** One cached stop: `id \t name \t lat \t lon \t line,ids`. Null when the line is malformed. */
fun parseCatalogLine(line: String): StopInfo? {
    val p = line.split('\t')
    if (p.size < 5) return null
    // "NaN" parses as a real Double, so an explicit finite check is needed.
    val lat = p[2].toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
    val lon = p[3].toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
    return StopInfo(
        id = p[0],
        name = p[1],
        lat = lat,
        lon = lon,
        lineIds = p[4].split(',').filter { it.isNotEmpty() }
    )
}

/**
 * Lets the network polls wait instead of running against an app nobody is looking at.
 *
 * Standalone rather than a pair of methods on [AppState] so it can be tested without constructing
 * the ViewModel, whose constructor immediately starts three polling loops that hit the network.
 *
 * Default is foreground, so a poll loop started before the first lifecycle callback - or any test
 * or preview - proceeds rather than waiting forever.
 */
class ForegroundGate {
    private val state = MutableStateFlow(true)

    /** Returns once the app is on screen. Suspends indefinitely while it is not. */
    suspend fun await() {
        state.first { it }
    }

    fun set(foreground: Boolean) {
        state.value = foreground
    }

    val isForeground: Boolean get() = state.value
}

class AppState(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("carris_tracker", Context.MODE_PRIVATE)

    private val _lines = MutableStateFlow<List<LineInfo>>(emptyList())
    val lines: StateFlow<List<LineInfo>> = _lines.asStateFlow()

private val _cmVehicles = MutableStateFlow<List<Vehicle>>(emptyList())
private val _carrisVehicles = MutableStateFlow<List<Vehicle>>(emptyList())

/**
 * Both providers merged, for the map.
 *
 * Kept as two source flows combined rather than one list written by both poll loops, so the
 * loops can run at different speeds without racing on a read-modify-write.
 *
 * Both feeds are network-wide — Carris Metropolitana serves ~1 000 vehicles and CARRIS ~560, for
 * every line on each network. Only the tracked ones are kept, which is what makes "Todas" mean
 * *our* six lines rather than every bus in Lisbon.
 */
val vehicles: StateFlow<List<Vehicle>> =
    combine(_cmVehicles, _carrisVehicles) { cm, carris -> cm + carris }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _stops = MutableStateFlow(loadStops())
    val stops: StateFlow<List<SavedStop>> = _stops.asStateFlow()

    /**
     * The lines the app tracks, as namespaced ids. Starts as [TRACKED_LINE_IDS] and is changed
     * from the Settings tab.
     *
     * This replaces the constant in every filter it used to drive: the vehicles the map draws,
     * which lines get a chip, which arrivals reach the Paragens tab, which stops the picker
     * offers, and which lines are considered "nearby". Read through [tracked] rather than
     * captured, so a change takes effect without restarting the app.
     */
    private val _selection = MutableStateFlow(loadSelection())
    val selection: StateFlow<Set<String>> = _selection.asStateFlow()
    val tracked: Set<String> get() = _selection.value

    /** Every line either provider offers, for the Settings picker. */
    private val _allLines = MutableStateFlow<List<LineInfo>>(emptyList())
    val allLines: StateFlow<List<LineInfo>> = _allLines.asStateFlow()

    private val _arrivals = MutableStateFlow<Map<String, List<Arrival>>>(emptyMap())
    val arrivals: StateFlow<Map<String, List<Arrival>>> = _arrivals.asStateFlow()

    /** Pattern metadata (stop order + km) for the tracked lines, fetched once per app run. */
    private val patterns = mutableMapOf<String, PatternInfo>()

    /** Sliding window of fixes per vehicle, for feeds that report no speed. */
    private val motionTracker = MotionTracker()

    /** One-shot catalogue load, shared by the two refresh loops. */
    private val catalogueLock = Mutex()

    /** Route geometry per line id, for the map. */
    private val _shapes = MutableStateFlow<Map<String, List<Pair<Double, Double>>>>(emptyMap())
    val shapes: StateFlow<Map<String, List<Pair<Double, Double>>>> = _shapes.asStateFlow()

    private val _allStops = MutableStateFlow<List<StopInfo>?>(null)
    val allStops: StateFlow<List<StopInfo>?> = _allStops.asStateFlow()

    private val _now = MutableStateFlow(System.currentTimeMillis() / 1000)
    val now: StateFlow<Long> = _now.asStateFlow()

    private val _lastUpdated = MutableStateFlow<Long?>(null)
    val lastUpdated: StateFlow<Long?> = _lastUpdated.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _update = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val update: StateFlow<UpdateStatus> = _update.asStateFlow()

    /** Whether to look for a new build when the app opens. Off by default until asked for. */
    var autoUpdate: Boolean
        get() = prefs.getBoolean("autoUpdate", false)
        set(v) = prefs.edit().putBoolean("autoUpdate", v).apply()

    private val _foreground = MutableStateFlow(true)

    /**
     * Whether anything is on screen.
     *
     * The polls below used to run for as long as the process lived, including while the app sat
     * backgrounded. Nobody is looking at a map that is off screen, and the cost of the vehicle
     * polls is ~44 MB/hour from Carris Metropolitana alone - so the loops now wait here instead of
     * sleeping, and resume the moment the app is opened again.
     */
    private val foreground = ForegroundGate()

    suspend fun awaitForeground() = foreground.await()

    fun setForeground(foreground: Boolean) = this.foreground.set(foreground)

    init {
        viewModelScope.launch {
            // Must precede the refresh loop: the CARRIS asset supplies its lines and patterns.
            CarrisGtfs.ensureLoaded(getApplication())
        }
        viewModelScope.launch {
            // Checked once at startup, a few seconds in so it does not compete with the asset
            // load and the first vehicle poll. A failure here is silent: updates are a
            // convenience, not something the app depends on.
            delay(5_000)
            if (autoUpdate) checkForUpdate()
        }
        viewModelScope.launch {
            // Two clocks, because the two feeds publish at different rates. Measured live:
            // Carris Metropolitana republishes every ~4-6 s, CARRIS every ~12-14 s.
            while (true) {
                awaitForeground()
                refreshCmVehicles()
                delay(CM_VEHICLE_POLL_MS)
            }
        }
        viewModelScope.launch {
            while (true) {
                awaitForeground()
                refreshCarrisVehicles()
                delay(CARRIS_VEHICLE_POLL_MS)
            }
        }
        viewModelScope.launch {
            while (true) {
                awaitForeground()
                refresh()
                delay(ARRIVAL_POLL_MS)
            }
        }
        viewModelScope.launch {
            while (true) {
                _now.value = System.currentTimeMillis() / 1000
                delay(CLOCK_TICK_MS)
            }
        }
    }

    /**
 * Drops the buses this app does not track.
 *
 * Both feeds are network-wide — Carris Metropolitana serves ~1 000 vehicles and CARRIS ~560,
 * across every line on each network — so nothing is filtered for us. Doing it here, where the
 * data enters, is what makes "Todas" mean *our* six lines instead of every bus in Lisbon, and
 * it is the only place that knows both providers are mixed together downstream.
 */
private fun keepTracked(vehicles: List<Vehicle>): List<Vehicle> =
    vehicles.filter { it.lineId in tracked }

/** Live positions from Carris Metropolitana, for the map. */
    private suspend fun refreshCmVehicles() {
        try {
            loadCatalogue()
            _cmVehicles.value = withMotion(keepTracked(cmVehicles()))
            _lastUpdated.value = System.currentTimeMillis() / 1000
            _error.value = null
        } catch (e: Exception) {
            _error.value = e.message ?: "falha de rede"
        }
    }

    /** Live positions from CARRIS. Same map, separate clock. */
    private suspend fun refreshCarrisVehicles() {
        try {
            loadCatalogue()
            _carrisVehicles.value = withMotion(keepTracked(CarrisGtfs.vehicles()))
            _lastUpdated.value = System.currentTimeMillis() / 1000
            _error.value = null
        } catch (e: Exception) {
            _error.value = e.message ?: "falha de rede"
        }
    }

    /**
     * Loads lines, patterns and shapes once. Guarded because the vehicles and arrival loops both
     * start cold and would otherwise race into two copies of the same fetch.
     */
    private suspend fun loadCatalogue() = catalogueLock.withLock {
        if (_lines.value.isNotEmpty()) return@withLock
        CarrisGtfs.ensureLoaded(getApplication())
        _lines.value = selectedLines()
        loadPatterns()
        loadShapes()
    }

    /**
     * Rebuilds lines, patterns and shapes after the selection changed.
     *
     * Patterns and shapes are keyed by line, so they have to be dropped too - leaving them keeps
     * a deselected line's geometry on the map, and its buses with it.
     */
    private suspend fun reloadCatalogue() = catalogueLock.withLock {
        CarrisGtfs.ensureLoaded(getApplication())
        patterns.clear()
        _shapes.value = emptyMap()
        _lines.value = selectedLines()
        loadPatterns()
        loadShapes()
    }

    /** Tracked lines from both providers, in one stable order. */
    private suspend fun selectedLines(): List<LineInfo> {
        val chosen = tracked
        return (cmAllLines() + CarrisGtfs.allRoutes())
            .filter { it.id in chosen }
            .sortedWith(compareBy({ it.id.substringBefore(':') }, { it.shortName }))
    }

    private suspend fun cmAllLines(): List<LineInfo> =
        Api.allLines().map {
            it.copy(
                id = nsId(PROVIDER_CM, it.id),
                patternIds = it.patternIds.map { p -> nsId(PROVIDER_CM, p) }
            )
        }

    private suspend fun refresh() {
        try {
            loadCatalogue()
            if (_stops.value.isNotEmpty()) {
                _arrivals.value = coroutineScope {
                    _stops.value
                        .map { s -> async { s.id to withPredictions(s.id, scheduledArrivals(s.id)) } }
                        .awaitAll()
                        .toMap()
                }
            }
            _lastUpdated.value = System.currentTimeMillis() / 1000
            _error.value = null
        } catch (e: Exception) {
            _error.value = e.message ?: "falha de rede"
        }
    }

    /**
     * Scheduled arrivals, from whichever provider serves the stop.
     *
     * Carris Metropolitana is asked for every stop, including CARRIS ones it does not know,
     * which it answers empty for. That is deliberate: a stop can serve both networks, and it
     * is one request per saved stop per refresh, where users typically keep two or three.
     *
     * CARRIS has no arrivals endpoint and publishes no tripupdates, so its timetable comes
     * from the bundled asset instead. Without it a CARRIS stop showed nothing at all unless a
     * bus happened to be circulating.
     */
    private suspend fun scheduledArrivals(stopId: String): List<Arrival> {
        val now = System.currentTimeMillis() / 1000
        val cmLive = runCatching {
            Api.arrivalsByStop(stopId).map {
                it.copy(
                    lineId = nsId(PROVIDER_CM, it.lineId),
                    patternId = nsId(PROVIDER_CM, it.patternId)
                )
            }
        }.getOrDefault(emptyList())

        // The rolling window above is empty overnight, so fall back to the timetable carried by
        // the patterns already loaded for this run.
        val cmSchedule = Timetable.arrivalsFromPatterns(
            patterns = patterns.values,
            stopId = stopId,
            nowUnix = now,
            date = Timetable.today(now)
        )

        return Timetable.merge(
            listOf(
                cmLive,
                cmSchedule,
                CarrisGtfs.scheduledArrivals(getApplication(), stopId, now)
            )
        )
    }

    private suspend fun cmVehicles(): List<Vehicle> =
        Api.vehicles().map {
            it.copy(
                lineId = nsId(PROVIDER_CM, it.lineId),
                patternId = nsId(PROVIDER_CM, it.patternId)
            )
        }

    /** Stop sequence + cumulative km for every tracked pattern, from whichever provider owns it. */
    private suspend fun loadPatterns() {
        coroutineScope {
            _lines.value.flatMap { it.patternIds }
                .map { pid -> async { runCatching { loadPattern(pid) }.getOrNull() } }
                .awaitAll()
                .forEach { if (it != null) patterns[it.id] = it }
        }
    }

    private suspend fun loadPattern(namespaced: String): PatternInfo? {
        val bare = bareOf(namespaced)
        val info = when {
            namespaced.startsWith("$PROVIDER_CM:") -> Api.pattern(bare)
            namespaced.startsWith("$PROVIDER_CARRIS:") -> CarrisGtfs.pattern(bare)
            else -> null
        }
        return info?.copy(id = namespaced)
    }

    private suspend fun loadShapes() {
        try {
            _shapes.value = coroutineScope {
                _lines.value.map { line ->
                    async {
                        val shapeId = line.patternIds
                            .mapNotNull { pid -> patterns[pid] }
                            // The fullest pattern, not the first one. A line's patterns are
                            // variants of the same corridor, and they are not interchangeable:
                            // 1721 lists `1721_2_2` first, a 14-stop 2.9 km stub of a route that
                            // is 8.4 km on its other variants, so taking the first drew a fifth
                            // of the line. Most stops tracks the longest variant closely enough
                            // on every tracked line, and costs nothing — the stop list is already
                            // parsed, whereas comparing shapes would mean fetching all of them.
                            .maxByOrNull { it.stops.size }
                            ?.shapeId
                        val points = when {
                            shapeId == null -> emptyList()
                            line.id.startsWith("$PROVIDER_CARRIS:") -> CarrisGtfs.shape(getApplication(), shapeId)
                            else -> Api.shape(shapeId)
                        }
                        line.id to points
                    }
                }.awaitAll().toMap()
            }
        } catch (_: Exception) {
            // map still shows vehicles without polylines
        }
    }

    /**
     * Replace scheduled times with times predicted from the live vehicle feed.
     *
     * The API only serves `scheduled_arrival_unix`, so each circulating vehicle of a matching
     * pattern is projected onto the stop and its ETA becomes the arrival. Each prediction
     * consumes the closest scheduled run of the same pattern (kept as `scheduledUnix`, so the
     * UI can show "previsto 07:04" next to the live countdown); the rest of the timetable
     * stays as fallback for when no bus is close enough to see.
     */
    private fun withPredictions(stopId: String, scheduled: List<Arrival>): List<Arrival> {
        val now = System.currentTimeMillis() / 1000
        val raw = predictedArrivals(stopId, now)
        if (raw.isEmpty()) return scheduled

        val pool = scheduled.toMutableList()
        val predicted = raw.sortedBy { it.scheduledUnix }.map { p ->
            val i = pool.indices
                .filter { pool[it].patternId == p.patternId }
                .minByOrNull { abs(pool[it].scheduledUnix - p.scheduledUnix) }
                ?.takeIf { abs(pool[it].scheduledUnix - p.scheduledUnix) <= MATCH_WINDOW_SECONDS }
            if (i == null) p else pool.removeAt(i).let { run ->
                p.copy(scheduledUnix = run.scheduledUnix, estimatedUnix = p.scheduledUnix)
            }
        }
        return pool + predicted
    }

    /** ETA of every live tracked vehicle that will still call at [stopId]. */
    private fun predictedArrivals(stopId: String, now: Long): List<Arrival> {
        val out = mutableListOf<Arrival>()
        for (v in vehicles.value) {
            if (v.lineId !in tracked || Eta.isStale(v, now)) continue
            val pattern = patterns[v.patternId] ?: continue
            val vehicleStop = Eta.vehicleStop(pattern, v) ?: continue
            val target = Eta.stopAhead(pattern, vehicleStop.sequence, stopId) ?: continue
            val seconds = Eta.secondsToStop(
                pattern = pattern,
                stopSequence = vehicleStop.sequence,
                status = v.status,
                speedKmh = v.speed,
                targetSequence = target.sequence,
                targetKm = target.km
            ) ?: continue
            out += Arrival(
                lineId = v.lineId,
                headsign = pattern.headsign,
                patternId = pattern.id,
                scheduledUnix = now + seconds,
                estimatedUnix = null,
                observedUnix = null,
                predicted = true
            )
        }
        return out
    }

    /**
     * Fills in speed for vehicles whose feed omits it.
     *
     * Carris Metropolitana reports it, so those pass through untouched; CARRIS reports none and
     * is measured instead — see [MotionTracker].
     */
    private fun withMotion(vehicles: List<Vehicle>): List<Vehicle> {
        motionTracker.retainFresh(System.currentTimeMillis())
        return vehicles.map(motionTracker::enrich)
    }

    /**
     * Next passages at a stop: [perLine] departures for every tracked line, soonest first.
     *
     * Grouped by short name rather than route id, so 746's two variants share one allowance
     * instead of showing four rows badged 746.
     */
    fun upcoming(stopId: String, perLine: Int = 2): List<Arrival> {
        val now = _now.value
        val known = _lines.value
        val candidates = (_arrivals.value[stopId] ?: emptyList())
            .filter { it.lineId in tracked && it.effectiveUnix >= now - 30 }
        return Timetable.select(candidates, perLine) { known.shortNameOf(it.lineId) }
    }

    /**
     * How a tapped bus relates to the user's saved stops: heading to one, passing one the other
     * way, or neither. See [busStopHint].
     */
    fun hintFor(vehicle: Vehicle): BusStopHint =
        busStopHint(vehicle, patterns.values, _stops.value, System.currentTimeMillis() / 1000)

    /**
     * Where the vehicle terminates, from its own pattern.
     *
     * This is the answer to "which way is it headed": the feed names the pattern, and a pattern
     * is one direction of one line, so it is exact — 716 reads `Alameda D. A. Henriques` one way
     * and `Benfica - Al. Pe. Álvaro Proença` the other. It is also what makes the two 746
     * variants, or the two halves of any route, distinguishable at a glance.
     */
    fun headsignOf(vehicle: Vehicle): String? = patterns[vehicle.patternId]?.headsign

    /**
     * Adds or removes a line from the tracked selection, then reloads what depends on it.
     *
     * Reloading rather than filtering in place is because lines own their patterns, and patterns
     * own the stop sequences and distances the ETA projects a bus onto - a deselected line has to
     * take its pattern with it, or its buses would still be placed on the map.
     */
    fun toggleLine(id: String) {
        val next = if (id in _selection.value) _selection.value - id else _selection.value + id
        if (next.isEmpty()) {
            _error.value = "Escolha pelo menos uma linha"
            return
        }
        _selection.value = next
        persistSelection(next)
        // Reloading fetches patterns and shapes, so it cannot run on the caller's thread.
        viewModelScope.launch {
            runCatching {
                reloadCatalogue()
                refresh()
            }.onFailure { _error.value = it.message ?: "falha ao mudar de linha" }
        }
    }

        /** Every line either provider has, for the Settings picker. Cheap: 39 KB gzipped. */
    suspend fun loadAllLines() {
        if (_allLines.value.isNotEmpty()) return
        _allLines.value = cmAllLines() + CarrisGtfs.allRoutes()
    }

    /**
     * Looks for a newer published build.
     *
     * A version the user already dismissed is not offered again, so this is safe to call on every
     * launch without nagging. [manual] is the "check now" button, which ignores that suppression
     * and reports "up to date" rather than staying silent.
     */
    fun checkForUpdate(manual: Boolean = false) {
        if (_update.value is UpdateStatus.Checking || _update.value is UpdateStatus.Downloading) return
        viewModelScope.launch {
            _update.value = UpdateStatus.Checking
            // The bare versionName, not the "2.3.0 (21)" display string: that one only parses as a version
            // by accident, because the digit scanner strips the parenthesised code.
            val current = versionName(getApplication())
            when (val found = Update.latest(current)) {
                is UpdateLookup.Found ->
                    if (!manual && found.update.version == prefs.getString("declinedUpdate", null)) {
                        // Already turned down once; not worth saying again on every launch.
                        _update.value = UpdateStatus.Idle
                    } else {
                        _update.value = UpdateStatus.Available(found.update)
                    }
                // Only surfaced on a manual check: a background failure is not worth a message.
                is UpdateLookup.Failed ->
                    _update.value = if (manual) UpdateStatus.Failed(found.reason) else UpdateStatus.Idle
                is UpdateLookup.UpToDate ->
                    _update.value = if (manual) UpdateStatus.Failed(null) else UpdateStatus.Idle
            }
        }
    }

    /** Downloads the offered build and refuses it unless it carries our signing certificate. */
    fun downloadUpdate() {
        val offered = (_update.value as? UpdateStatus.Available)?.update ?: return
        viewModelScope.launch {
            _update.value = UpdateStatus.Downloading(0, 0)
            runCatching {
                val apk = Update.download(getApplication(), offered) { done, total ->
                    _update.value = UpdateStatus.Downloading(done, total)
                }
                // The check that matters. Everything above is just plumbing; this is what makes
                // installing someone else's file impossible rather than merely unlikely.
                if (!Update.signedByUs(getApplication(), apk)) {
                    Update.discard(getApplication())
                    error("o ficheiro não está assinado com a nossa chave")
                }
                apk
            }.onSuccess { _update.value = UpdateStatus.Ready(it) }
                .onFailure { _update.value = UpdateStatus.Failed(it.message ?: "falha ao descarregar") }
        }
    }

    /** Records that the user does not want this version now, and drops any downloaded copy. */
    fun declineUpdate() {
        val offered = (_update.value as? UpdateStatus.Available)?.update
        if (offered != null) prefs.edit().putString("declinedUpdate", offered.version).apply()
        Update.discard(getApplication())
        _update.value = UpdateStatus.Idle
    }

    /** Clears a failure or a finished install so the row returns to its resting state. */
    fun resetUpdate() {
        _update.value = UpdateStatus.Idle
    }

    /**
     * Bare route ids the feed ships but whose distance data is unusable.
     *
     * The Settings picker greys these out and says why, rather than offering a line whose
     * predictions would be nonsense.
     */
    fun excludedLineIds(): Set<String> = CarrisGtfs.excluded()

    private fun persistSelection(lines: Set<String>) {
        prefs.edit().putString("lines", lines.joinToString(",")).apply()
    }

    private fun loadSelection(): Set<String> {
        val raw = prefs.getString("lines", null) ?: return TRACKED_LINE_IDS.toSet()
        val saved = raw.split(',').filter { it.isNotEmpty() }.toSet()
        return if (saved.isEmpty()) TRACKED_LINE_IDS.toSet() else saved
    }

    fun addStop(stop: StopInfo) {
        if (_stops.value.any { it.id == stop.id }) return
        _stops.value = _stops.value + SavedStop(stop.id, stop.name, stop.lat, stop.lon)
        persistStops()
        viewModelScope.launch { refresh() }
    }

    fun removeStop(id: String) {
        _stops.value = _stops.value.filterNot { it.id == id }
        persistStops()
    }

    /**
     * Moves a stop [delta] places up or down the list. Out-of-range moves are ignored rather
     * than clamped, so the first card's "up" and the last card's "down" are no-ops.
     *
     * The order is the list's own order and `persistStops` writes it verbatim, so no schema
     * change is needed to make it survive a restart.
     */
    fun moveStop(id: String, delta: Int) {
        val from = _stops.value.indexOfFirst { it.id == id }
        if (from < 0) return
        val to = from + delta
        if (to !in _stops.value.indices) return
        _stops.value = _stops.value.toMutableList().apply {
            add(to, removeAt(from))
        }
        persistStops()
    }

    /**
     * Load the full stop catalog, from disk cache if fresh.
     *
     * The on-disk catalog stays Carris Metropolitana only; the 49 CARRIS stops are merged in
     * memory from the 180 KB asset instead, which keeps the cache format and rebuild cost
     * unchanged.
     *
     * Namespacing happens here, at the one point the two catalogs meet. `Api.allStops()` and
     * the cache file both carry bare Carris Metropolitana ids ('1707') while `CarrisGtfs`
     * produces namespaced ones ('carris:164_0'), and everything downstream compares against
     * namespaced `TRACKED_LINE_IDS`. Doing it on read as well as on write means a catalog
     * cached by an older build fixes itself instead of needing a 7-day wait to expire.
     */
    fun ensureAllStops() {
        if (_allStops.value != null) return
        viewModelScope.launch {
            try {
                CarrisGtfs.ensureLoaded(getApplication())
                val cm = (readCatalog() ?: Api.allStops().also { writeCatalog(it) })
                    .map { it.copy(lineIds = it.lineIds.map { id -> nsId(PROVIDER_CM, id) }) }
                // Deduplicated *within* each network, not across them. Both operators number
                // their stops independently and the two id spaces do overlap - `7822` exists on
                // both - so a global `distinctBy { it.id }` silently dropped whichever came
                // second from the picker, making that stop unfindable. Showing both is the honest
                // outcome; the ids are not namespaced because a saved stop's id is handed
                // straight to `Api.arrivalsByStop` and looked up in the CARRIS timetable, so
                // decorating it here would break both.
                val all = cm.distinctBy { it.id } + CarrisGtfs.stops().distinctBy { it.id }
                _allStops.value = all
                backfillStopCoords(all)
            } catch (e: Exception) {
                _error.value = e.message ?: "falha de rede"
            }
        }
    }

    /**
     * The catalog is 6.5 MB of JSON — refetching it on every cold start is the picker's whole
     * delay. Cache it as a flat text file instead.
     *
     * `ponytail:` cacheDir, rebuilt after [CATALOG_MAX_AGE_MS]. Upgrade path: server-side
     * search on the API, which would remove the catalog download entirely.
     */
    private suspend fun readCatalog(): List<StopInfo>? = withContext(Dispatchers.IO) {
        val file = catalogFile()
        if (!file.exists() || System.currentTimeMillis() - file.lastModified() > CATALOG_MAX_AGE_MS) {
            return@withContext null
        }
        runCatching {
            file.bufferedReader().useLines { lines ->
                lines.mapNotNull(::parseCatalogLine).toList()
            }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    private suspend fun writeCatalog(stops: List<StopInfo>) = withContext(Dispatchers.IO) {
        runCatching {
            catalogFile().bufferedWriter().use { w ->
                stops.forEach {
                    w.write("${it.id}\t${it.name.replace('\t', ' ').replace('\n', ' ')}\t${it.lat}\t${it.lon}\t${it.lineIds.joinToString(",")}\n")
                }
            }
        }
    }

    private fun catalogFile() = File(getApplication<Application>().cacheDir, "stops-catalog.txt")

    /** Closest stops served by tracked lines. Empty when location or catalog unavailable. */
    suspend fun nearestStops(limit: Int = 5): List<NearbyStop> {
        val loc = lastKnownLocation() ?: return emptyList()
        val catalog = _allStops.value ?: return emptyList()
        return withContext(Dispatchers.Default) {
            catalog
                .filter { stop -> stop.lineIds.any { it in tracked } }
                .map { NearbyStop(it, Eta.distanceMeters(loc.latitude, loc.longitude, it.lat, it.lon).toLong()) }
                .sortedBy { it.meters }
                .distinctBy { it.stop.name }
                .take(limit)
        }
    }

    private fun lastKnownLocation(): Location? {
        val lm = getApplication<Application>().getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val fallback = "passive"
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, fallback)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    private fun loadStops(): List<SavedStop> {
        val raw = prefs.getString("stops", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                SavedStop(o.getString("id"), o.getString("name"))
            }
        }.getOrDefault(emptyList())
    }

    private fun persistStops() {
        val arr = JSONArray(_stops.value.map { s ->
            JSONObject().put("id", s.id).put("name", s.name).apply {
                if (s.lat != null && s.lon != null) {
                    put("lat", s.lat)
                    put("lon", s.lon)
                }
            }
        })
        prefs.edit().putString("stops", arr.toString()).apply()
    }

    /**
     * Fills in coordinates for stops saved before they were recorded.
     *
     * Cheap and idempotent: only runs when something is actually missing, and persists once.
     */
    private fun backfillStopCoords(catalog: List<StopInfo>) {
        if (_stops.value.all { it.lat != null && it.lon != null }) return
        val byId = catalog.associateBy(StopInfo::id)
        val filled = _stops.value.map { s ->
            if (s.lat != null && s.lon != null) s
            else byId[s.id]?.let { s.copy(lat = it.lat, lon = it.lon) } ?: s
        }
        if (filled != _stops.value) {
            _stops.value = filled
            persistStops()
        }
    }
}
