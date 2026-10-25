package pt.carrismetropolitana.tracker

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Settings: about, which lines to track, and a placeholder for auto-update.
 */
@Composable
fun SettingsScreen(state: AppState, padding: PaddingValues) {
    val allLines by state.allLines.collectAsState()
    val selection by state.selection.collectAsState()
    val error by state.error.collectAsState()

    // 717 Carris Metropolitana lines plus 176 CARRIS routes, in one 39 KB request.
    LaunchedEffect(Unit) { state.loadAllLines() }

    // Hoisted out of the list: the search field is an item, the filter below it is not, and a
    // `remember` inside the item lambda would not be visible out here.
    var query by remember { mutableStateOf("") }

    // Route ids whose distance data the feed gets wrong; they cannot produce honest ETAs.
    val excluded = remember(allLines) { state.excludedLineIds() }
    val visible = remember(allLines, query) {
        val q = normalize(query.trim())
        if (q.isEmpty()) allLines
        else allLines.filter {
            normalize(it.shortName).contains(q) || normalize(it.longName).contains(q)
        }
    }

    LazyColumn(
        modifier = Modifier.padding(padding).fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                "Definições",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.headlineSmall
            )
        }

        if (error != null) {
            item {
                SectionCard {
                    Text(error!!, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        item {
            SectionCard {
                SettingsHeader(Icons.Default.Info, "Sobre")
                Text(
                    "O Carris Tracker mostra as partidas previstas nas paragens que guardar, e " +
                        "onde está cada autocarro das linhas que escolher. As previsões de " +
                        "chegada saem da posição real de cada veículo e não só do horário, por " +
                        "isso podem divergir alguns minutos do que está afixado no painel.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                )
                Spacer(Modifier.height(4.dp))
                AboutRow("Versão", appVersion(LocalContext.current))
                AboutRow("Linhas seguidas", if (allLines.isEmpty()) "…" else "${selection.size} de ${allLines.size}")
                Spacer(Modifier.height(4.dp))
                Text(
                    "Dados: Carris Metropolitana (API pública) e CARRIS (GTFS e GTFS-RT). " +
                        "Mapa © OpenStreetMap contributors.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }
        }

        item {
            UpdateCard(state)
        }

        item {
            SectionCard {
                SettingsHeader(Icons.Default.DirectionsBus, "Linhas")
                Text(
                    "Escolha as linhas que quer seguir. Os autocarros no mapa, as chegadas nas " +
                        "paragens e os filtros passam a mostrar só estas.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                )
            }
        }

        if (allLines.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            return@LazyColumn
        }

        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Procurar linha…") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp)
            )
        }

        if (visible.isEmpty()) {
            item {
                Text(
                    "Nenhuma linha encontrada.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }

        items(visible, key = { it.id }) { line ->
            val checked = line.id in selection
            val broken = bareOf(line.id) in excluded
            val dim = if (broken) 0.35f else 1f
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !broken) { state.toggleLine(line.id) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = checked,
                    onCheckedChange = { state.toggleLine(line.id) },
                    enabled = !broken
                )
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(
                        line.shortName,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = dim)
                    )
                    Text(
                        // Saying why a line cannot be picked beats a greyed-out row with no
                        // explanation.
                        if (broken) "Dados incorretos na fonte" else line.longName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = dim * 0.65f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Box(
                    Modifier
                        .size(12.dp)
                        .background(
                            runCatching { Color(android.graphics.Color.parseColor(line.color)) }
                                .getOrDefault(Color.Gray),
                            RoundedCornerShape(4.dp)
                        )
                )
            }
        }
    }
}

/**
 * The update row: a toggle, a status line, and whatever the current stage needs.
 *
 * Every stage is reachable from here, so a failed check or a downloaded-but-uninstalled build can
 * be cleared without killing the app.
 */
@Composable
private fun UpdateCard(state: AppState) {
    val update by state.update.collectAsState()
    var auto by remember { mutableStateOf(state.autoUpdate) }
    val context = LocalContext.current
    val busy = update is UpdateStatus.Checking || update is UpdateStatus.Downloading

    SectionCard {
        SettingsHeader(Icons.Default.SystemUpdate, "Actualização automática")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Procurar actualizações ao abrir a app", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Procurado no GitHub. Nada é instalado sem a tua confirmação.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }
            Switch(checked = auto, onCheckedChange = {
                auto = it
                state.autoUpdate = it
                if (it) state.checkForUpdate(manual = true) else state.resetUpdate()
            })
        }

        when (val u = update) {
            is UpdateStatus.Idle -> Unit

            is UpdateStatus.Checking ->
                StatusLine("A procurar…")

            is UpdateStatus.Available -> {
                StatusLine("Versão ${u.update.version} disponível")
                if (u.update.notes.isNotBlank()) {
                    Text(
                        u.update.notes.take(400),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { state.downloadUpdate() }) { Text("Transferir") }
                    TextButton(onClick = { state.declineUpdate() }) { Text("Agora não") }
                }
            }

            is UpdateStatus.Downloading -> {
                val pct = if (u.total > 0) "${u.done * 100 / u.total}%" else ""
                StatusLine("A transferir… $pct")
                if (u.total > 0) {
                    LinearProgressIndicator(
                        progress = { u.done.toFloat() / u.total },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            is UpdateStatus.Ready -> {
                StatusLine("Transferido e verificado")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        // Keep the file: the installer reads it through the URI after startActivity
                        // returns, so deleting it here surfaces as "problem parsing the package".
                        // The next download clears any leftover.
                        runCatching {
                            context.startActivity(Update.installIntent(context, u.file))
                        }
                        state.resetUpdate()
                    }) { Text("Instalar") }
                    TextButton(onClick = {
                        Update.discard(context)
                        state.resetUpdate()
                    }) { Text("Cancelar") }
                }
            }

            // null reason is the manual "check now" that found nothing.
            is UpdateStatus.Failed ->
                Column {
                    StatusLine(u.reason ?: "Já está actualizada.")
                    TextButton(onClick = { state.resetUpdate() }) { Text("Fechar") }
                }
        }

        if (update is UpdateStatus.Idle) {
            TextButton(onClick = { state.checkForUpdate(manual = true) }, enabled = !busy) {
                Text("Procurar agora")
            }
        }
    }
}

@Composable
private fun StatusLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            content()
        }
    }
}

@Composable
private fun SettingsHeader(icon: ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(
            title,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
        )
    }
}

/** `versionName` on its own, e.g. `2.3.0`. This is what the update check compares against. */
fun versionName(context: Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "" }
        .getOrDefault("")

/**
 * `versionCode` on its own, e.g. `21`.
 *
 * Read through [android.content.pm.PackageInfoCompat] rather than the deprecated
 * `versionCode`, so it is right on API 24 and not only on 28+.
 */
fun versionCode(context: Context): Long = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode
    else {
        @Suppress("DEPRECATION") info.versionCode.toLong()
    }
}.getOrDefault(0L)

/** For display only: `2.3.0 (21)`. Never feed this to a comparison - it is not a version. */
fun appVersion(context: Context): String {
    val name = versionName(context).ifBlank { "?" }
    return "$name (${versionCode(context)})"
}