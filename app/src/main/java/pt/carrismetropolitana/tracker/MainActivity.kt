package pt.carrismetropolitana.tracker

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import java.text.Normalizer

val CarrisRed = Color(0xFFC61D23)

private val LightColors = lightColorScheme(
    primary = CarrisRed,
    onPrimary = Color.White,
    secondary = Color(0xFF4A4A4A),
    background = Color(0xFFF7F7F9),
    surface = Color.White
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFF6B63),
    background = Color(0xFF121212),
    surface = Color(0xFF1C1C1F)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            CarrisTheme {
                App()
            }
        }
    }
}

@Composable
fun CarrisTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (androidx.compose.foundation.isSystemInDarkTheme()) DarkColors else LightColors,
        content = content
    )
}

@Composable
fun App(state: AppState = viewModel()) {
    var tab by remember { mutableIntStateOf(0) }
    var showPicker by remember { mutableStateOf(false) }

    // The network polls stop when the app leaves the screen and resume when it comes back. Without
    // this they ran for the life of the process, spending ~44 MB/hour on a map nobody was looking
    // at. Kept here rather than in the Activity so it survives a configuration change: the
    // ViewModel outlives that, and ON_STOP fires either way.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> state.setForeground(true)
                Lifecycle.Event.ON_STOP -> state.setForeground(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (showPicker) {
        StopPickerScreen(state = state, onBack = { showPicker = false })
    } else {
        Scaffold(
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    NavigationBarItem(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        icon = { Icon(Icons.Default.Place, contentDescription = null) },
                        label = { Text("Paragens") }
                    )
                    NavigationBarItem(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        icon = { Icon(Icons.Default.Map, contentDescription = null) },
                        label = { Text("Mapa") }
                    )
                    NavigationBarItem(
                        selected = tab == 2,
                        onClick = { tab = 2 },
                        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        label = { Text("Definições") }
                    )
                }
            },
            containerColor = MaterialTheme.colorScheme.background
        ) { padding ->
            when (tab) {
                0 -> StopsScreen(state, padding, onAddStop = { showPicker = true })
                1 -> MapScreen(state, padding)
                else -> SettingsScreen(state, padding)
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/* My stops                                                            */
/* ------------------------------------------------------------------ */

@Composable
fun StopsScreen(state: AppState, padding: PaddingValues, onAddStop: () -> Unit) {
    val stops by state.stops.collectAsState()
    val lines by state.lines.collectAsState()
    val now by state.now.collectAsState()
    val lastUpdated by state.lastUpdated.collectAsState()
    val error by state.error.collectAsState()
    val context = LocalContext.current

    var hint by remember { mutableStateOf<String?>(null) }
    var collapsed by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var editing by rememberSaveable { mutableStateOf(false) }
    val allCollapsed = stops.isNotEmpty() && collapsed.size == stops.size
    val scope = rememberCoroutineScope()

    // Nothing to reorder with one stop, and the mode would have nothing to do.
    val canReorder = stops.size > 1
    LaunchedEffect(canReorder) { if (!canReorder) editing = false }

    fun addNearest() {
        scope.launch {
            state.ensureAllStops()
            val nearest = state.nearestStops()
            if (nearest.isEmpty()) {
                hint = "Não encontrei paragens suas por perto (sem sinal GPS ou catálogo ainda a carregar)"
            } else {
                state.addStop(nearest.first().stop)
                hint = null
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.any { it }) addNearest() else hint = "Preciso da localização para encontrar a paragem mais próxima"
    }

    fun nearestWithPermission() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (granted) addNearest()
        else permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    LazyColumn(
        modifier = Modifier.padding(padding).fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("As minhas paragens", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        lastUpdated?.let { "atualizado às ${Schedule.hhMm(it)}" } ?: "a ligar…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                    )
                }
                // One contextual button, not two: collapsing and re-ordering are both list-wide
                // actions and only one of them is ever relevant at a time.
                if (canReorder) {
                    TextButton(onClick = {
                        if (editing) editing = false
                        else collapsed = if (allCollapsed) arrayListOf() else ArrayList(stops.map { it.id })
                    }) {
                        Text(
                            when {
                                editing -> "Concluir"
                                allCollapsed -> "Expandir"
                                else -> "Recolher"
                            }
                        )
                    }
                }
                IconButton(onClick = onAddStop) {
                    Icon(Icons.Default.Add, contentDescription = "Adicionar paragem")
                }
            }
        }

        if (error != null) {
            item {
                Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(error!!, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (hint != null) {
            item {
                Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Text(hint!!, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSecondaryContainer, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (stops.isEmpty()) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(top = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        Icons.Default.Place,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp)
                    )
                    Text("Sem paragens ainda", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Adicione 2–3 paragens para ver quanto falta para o próximo autocarro das linhas ${lines.map { it.shortName }.distinct().joinToString(", ")}.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                    )
                    FilledTonalButton(onClick = onAddStop) {
                        Icon(Icons.Default.Search, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Procurar paragem")
                    }
                    FilledTonalButton(onClick = { nearestWithPermission() }) {
                        Icon(Icons.Default.MyLocation, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Usar a paragem mais próxima")
                    }
                }
            }
        }

        items(stops, key = { it.id }) { stop ->
            val index = stops.indexOf(stop)
            StopCard(
                stop = stop,
                arrivals = state.upcoming(stop.id),
                lines = lines,
                now = now,
                collapsed = stop.id in collapsed,
                editing = editing,
                canMoveUp = index > 0,
                canMoveDown = index < stops.lastIndex,
                onToggle = {
                    val next = ArrayList(collapsed)
                    if (!next.remove(stop.id)) next.add(stop.id)
                    collapsed = next
                },
                onEdit = { editing = true },
                onMoveUp = { state.moveStop(stop.id, -1) },
                onMoveDown = { state.moveStop(stop.id, 1) },
                onRemove = { state.removeStop(stop.id) }
            )
        }
    }
}

/** One-line "1707 3 min · 1717 12 min" shown while the card is collapsed. */
private fun arrivalsSummary(arrivals: List<Arrival>, lines: List<LineInfo>, now: Long): String {
    if (arrivals.isEmpty()) return "Sem passagens previstas"
    return arrivals.take(2).joinToString("  ·  ") { a ->
        val name = lines.find { it.id == a.lineId }?.shortName ?: a.lineId
        val eta = if (a.realtime) Schedule.countdown(a.effectiveUnix, now) else Schedule.hhMm(a.effectiveUnix)
        "$name $eta"
    }
}

/**
 * Up/down arrows instead of drag-and-drop.
 *
 * Reordering by dragging inside a LazyColumn needs hand-rolled pointer input against the list
 * layout, and breaks the moment a card animates its height. With two or three stops, two
 * buttons that grey out at the ends do the same job in a fraction of the code.
 *
 * They are only on screen while reordering, which is entered by long-pressing a card. Keeping
 * them permanently was the alternative, and four buttons in a card header was clutter for a
 * control used rarely.
 */
@Composable
private fun ReorderButtons(
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    val enabled = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    val disabled = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
    Row {
        IconButton(onClick = onMoveUp, enabled = canMoveUp, modifier = Modifier.size(28.dp)) {
            Icon(
                Icons.Default.KeyboardArrowUp,
                contentDescription = "Mover para cima",
                tint = if (canMoveUp) enabled else disabled,
                modifier = Modifier.size(20.dp)
            )
        }
        IconButton(onClick = onMoveDown, enabled = canMoveDown, modifier = Modifier.size(28.dp)) {
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = "Mover para baixo",
                tint = if (canMoveDown) enabled else disabled,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun StopCard(
    stop: SavedStop,
    arrivals: List<Arrival>,
    lines: List<LineInfo>,
    now: Long,
    collapsed: Boolean,
    editing: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit
) {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().animateContentSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    Modifier.weight(1f).combinedClickable(
                        enabled = !editing,
                        onClick = onToggle,
                        onLongClick = onEdit
                    )
                ) {
                    Text(stop.name, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (collapsed) {
                        Text(
                            arrivalsSummary(arrivals, lines, now),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                // Expanding and re-ordering are different jobs, so only one set of controls is
                // ever on screen.
                if (editing) {
                    ReorderButtons(canMoveUp, canMoveDown, onMoveUp, onMoveDown)
                } else {
                    Icon(
                        if (collapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                        contentDescription = if (collapsed) "Expandir paragem" else "Recolher paragem",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.size(22.dp).clickable(onClick = onToggle)
                    )
                }
                IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Remover paragem", tint = Color.Gray)
                }
            }
            if (collapsed) return@Column

            Spacer(Modifier.height(8.dp))
            if (arrivals.isEmpty()) {
                Text(
                    "Sem passagens previstas",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
            arrivals.forEach { arrival ->
                val line = lines.find { it.id == arrival.lineId }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(width = 46.dp, height = 26.dp)
                            .background(
                                Color(android.graphics.Color.parseColor(line?.color ?: "#888888")),
                                RoundedCornerShape(8.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            line?.shortName ?: arrival.lineId,
                            color = Color.White,
                            fontWeight = FontWeight.Black,
                            fontSize = 12.sp
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(arrival.headsign, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (arrival.realtime) {
                        Column(horizontalAlignment = Alignment.End) {
                            Box(
                                Modifier.background(MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    Schedule.countdown(arrival.effectiveUnix, now),
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            }
                            // Only worth showing when the bus is actually off schedule.
                            if (kotlin.math.abs(arrival.scheduledUnix - arrival.effectiveUnix) >= 60) {
                                Text(
                                    "previsto ${Schedule.hhMm(arrival.scheduledUnix)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                                )
                            }
                        }
                    } else {
                        Column(horizontalAlignment = Alignment.End) {
                            Text(Schedule.hhMm(arrival.effectiveUnix), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text(
                                "horário",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                            )
                        }
                    }
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/* Stop picker (search + nearest)                                      */
/* ------------------------------------------------------------------ */

/** Hoisted: building this Regex per call was 12 752 allocations on every keystroke. */
internal val Diacritics = Regex("\\p{M}")

internal fun normalize(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFD).replace(Diacritics, "").lowercase()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StopPickerScreen(state: AppState, onBack: () -> Unit) {
    val allStops by state.allStops.collectAsState()
    val lines by state.lines.collectAsState()
    val context = LocalContext.current

    var query by remember { mutableStateOf("") }
    var nearby by remember { mutableStateOf<List<NearbyStop>>(emptyList()) }
    val scope = rememberCoroutineScope()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.any { it }) scope.launch { nearby = state.nearestStops() }
    }

    LaunchedEffect(Unit) {
        state.ensureAllStops()
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (granted) nearby = state.nearestStops()
        else permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    // Normalised once per catalog, not once per keystroke: this used to be 12 752
    // NFD + regex passes on the main thread for every character typed.
    val index = remember(allStops) { allStops.orEmpty().map { normalize(it.name) to it } }

    val results = remember(query, index) {
        if (query.isBlank()) emptyList()
        else {
            val q = normalize(query.trim())
            index.asSequence().filter { it.first.contains(q) }.take(200).map { it.second }.toList()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Adicionar paragem", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                placeholder = { Text("Procurar por nome…") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp)
            )

            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (allStops == null) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }

                if (query.isBlank() && nearby.isNotEmpty()) {
                    item {
                        Text("Mais próximas de si", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                    }
                    items(nearby, key = { "near-" + it.stop.id }) { near ->
                        StopPickRow(name = near.stop.name, detail = "${near.meters} m") {
                            state.addStop(near.stop)
                            onBack()
                        }
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }

                if (query.isBlank() && nearby.isEmpty() && allStops != null) {
                    item {
                        Text(
                            "Escreva o nome da paragem para a procurar.",
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                        )
                    }
                }

                items(results, key = { it.id }) { stop ->
                    StopPickRow(
                        name = stop.name,
                        detail = stop.lineIds
                            .filter { it in TRACKED_LINE_IDS }
                            .map { lines.shortNameOf(it) }
                            .sorted()
                            .joinToString(" · ")
                    ) {
                        state.addStop(stop)
                        onBack()
                    }
                }
            }
        }
    }
}

@Composable
private fun StopPickRow(name: String, detail: String, onClick: () -> Unit) {
    Card(onClick = onClick, shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (detail.isNotEmpty()) {
                    Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}
