package ch.lkmc.blipbird.ui.settings

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.blipbird.R
import ch.lkmc.blipbird.core.datastore.AppTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            // ---- Theme --------------------------------------------------
            SectionTitle(stringResource(R.string.settings_theme))
            Row {
                ThemeChip(stringResource(R.string.theme_daylight_dynamic), state.theme == AppTheme.DAYLIGHT_DYNAMIC) {
                    viewModel.setTheme(AppTheme.DAYLIGHT_DYNAMIC)
                }
                Spacer(Modifier.padding(4.dp))
                ThemeChip(stringResource(R.string.theme_daylight), state.theme == AppTheme.DAYLIGHT) {
                    viewModel.setTheme(AppTheme.DAYLIGHT)
                }
            }
            Row {
                ThemeChip(stringResource(R.string.theme_cockpit), state.theme == AppTheme.COCKPIT) {
                    viewModel.setTheme(AppTheme.COCKPIT)
                }
                Spacer(Modifier.padding(4.dp))
                ThemeChip(stringResource(R.string.theme_high_contrast), state.theme == AppTheme.HIGH_CONTRAST) {
                    viewModel.setTheme(AppTheme.HIGH_CONTRAST)
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 16.dp))

            // ---- Data sources (BYO keys) --------------------------------
            SectionTitle(stringResource(R.string.settings_data_sources))
            Text(
                stringResource(R.string.onboarding_keys_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            KeyField(
                label = stringResource(R.string.settings_aerodatabox_key),
                configured = state.hasAdbKey,
                onSave = { viewModel.saveAdbKey(it) },
            )
            Spacer(Modifier.height(10.dp))
            KeyField(
                label = stringResource(R.string.settings_aeroapi_key),
                configured = state.hasAeroApiKey,
                onSave = { viewModel.saveAeroApiKey(it) },
            )

            HorizontalDivider(Modifier.padding(vertical = 16.dp))

            // ---- Notifications ------------------------------------------
            SectionTitle(stringResource(R.string.settings_notifications))
            ToggleRow(stringResource(R.string.channel_critical), state.notifCritical) { viewModel.setNotifCritical(it) }
            ToggleRow(stringResource(R.string.channel_status), state.notifStatus) { viewModel.setNotifStatus(it) }
            ToggleRow(stringResource(R.string.channel_reminders), state.notifReminders) { viewModel.setNotifReminders(it) }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.settings_precise_alerts), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.settings_precise_alerts_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            // Pre-S exact alarms are always allowed; the API and the settings
            // action to request them exist only on 31+.
            val exactSupported = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
            var exactGranted by remember {
                mutableStateOf(!exactSupported || alarmManager.canScheduleExactAlarms())
            }
            Button(
                enabled = !exactGranted,
                onClick = {
                    if (exactSupported) {
                        context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
                        exactGranted = alarmManager.canScheduleExactAlarms()
                    }
                },
            ) {
                Text(if (exactGranted) "Granted" else "Allow precise alerts")
            }

            HorizontalDivider(Modifier.padding(vertical = 16.dp))

            // ---- Quota --------------------------------------------------
            SectionTitle(stringResource(R.string.settings_quota))
            state.quota.forEach { (provider, used, allowance) ->
                Text(
                    "$provider: $used / ${allowance ?: "∞"}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 16.dp))

            // ---- Diagnostics -------------------------------------------
            val crashFile = java.io.File(context.filesDir, "last_crash.txt")
            if (crashFile.exists()) {
                SectionTitle("Diagnostics")
                var showCrash by remember { mutableStateOf(false) }
                Button(onClick = { showCrash = !showCrash }) {
                    Text(if (showCrash) "Hide last crash log" else "Show last crash log")
                }
                if (showCrash) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        remember { runCatching { crashFile.readText().take(4000) }.getOrDefault("unreadable") },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 16.dp))
            }

            // ---- Attribution -------------------------------------------
            SectionTitle(stringResource(R.string.settings_about))
            Text(ATTRIBUTION_TEXT, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun ThemeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun KeyField(label: String, configured: Boolean, onSave: (String) -> Unit) {
    var value by remember { mutableStateOf("") }
    Column {
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            label = { Text(label) },
            placeholder = { Text(stringResource(R.string.settings_key_hint)) },
            supportingText = {
                Text(
                    if (configured) stringResource(R.string.settings_key_saved)
                    else stringResource(R.string.settings_key_none),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
        if (value.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Button(onClick = { onSave(value); value = "" }) { Text("Save") }
        }
    }
}

private const val ATTRIBUTION_TEXT = """Data sources & licenses:
• Flight status: AeroDataBox (your RapidAPI key) · FlightAware AeroAPI (your key, personal use)
• Live positions: adsb.lol (ODbL) · airplanes.live · adsb.fi (non-commercial, attribution)
• Airports: OurAirports (Public Domain) · timezones from mwgg/Airports (MIT)
• Airlines: OpenTravelData (CC BY 4.0, filtered to active carriers)
• Weather: NOAA aviationweather.gov · Weather data by Open-Meteo.com (CC BY 4.0)
• Solar math: commons-suncalc (Apache-2.0); great-circle formulas after Chris Veness (MIT); terminator math after Leaflet.Terminator (MIT)
Airline names/codes are trademarks of their respective owners, used for identification only.
All flight data is informational and not for navigation or operational use."""
