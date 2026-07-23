package ch.lkmc.blipbird.ui.settings

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.blipbird.R
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import ch.lkmc.blipbird.core.datastore.Accent
import ch.lkmc.blipbird.core.datastore.AppIcon
import ch.lkmc.blipbird.core.datastore.ThemeMode

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
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            // ---- Appearance ---------------------------------------------
            SectionTitle(stringResource(R.string.settings_appearance))
            AppearanceSection(state, viewModel)

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
                onClear = { viewModel.clearAdbKey() },
            )
            Spacer(Modifier.height(10.dp))
            KeyField(
                label = stringResource(R.string.settings_aeroapi_key),
                configured = state.hasAeroApiKey,
                onSave = { viewModel.saveAeroApiKey(it) },
                onClear = { viewModel.clearAeroApiKey() },
            )

            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.settings_opensky_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.settings_opensky_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            KeyField(
                label = stringResource(R.string.settings_opensky_client_id),
                configured = state.hasOpenSkyId,
                onSave = { viewModel.saveOpenSkyId(it) },
                onClear = { viewModel.clearOpenSkyId() },
                optional = true,
            )
            Spacer(Modifier.height(10.dp))
            KeyField(
                label = stringResource(R.string.settings_opensky_client_secret),
                configured = state.hasOpenSkySecret,
                onSave = { viewModel.saveOpenSkySecret(it) },
                onClear = { viewModel.clearOpenSkySecret() },
                optional = true,
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
            // Re-check when the user comes back from the system settings screen
            // (the grant happens there, so an eager re-read right after
            // startActivity always saw the old state and left the label stale).
            LifecycleResumeEffect(Unit) {
                if (exactSupported) exactGranted = alarmManager.canScheduleExactAlarms()
                onPauseOrDispose { }
            }
            Button(
                enabled = !exactGranted,
                onClick = {
                    if (exactSupported) {
                        context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
                    }
                },
            ) {
                Text(
                    if (exactGranted) stringResource(R.string.settings_granted)
                    else stringResource(R.string.settings_allow_precise)
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 16.dp))

            // ---- Quota --------------------------------------------------
            SectionTitle(stringResource(R.string.settings_quota))
            state.quota.forEach { (provider, used, allowance) ->
                // TalkBack reads "∞" poorly (or not at all); speak "unlimited".
                val spoken = stringResource(
                    R.string.quota_spoken, provider, used, allowance?.toString()
                        ?: stringResource(R.string.quota_unlimited),
                )
                Text(
                    "$provider: $used / ${allowance ?: "∞"}",
                    modifier = Modifier.semantics { contentDescription = spoken },
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
    Text(
        text,
        modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(8.dp))
}

/** Curated accent seeds; the swatch shows the raw seed, schemes are derived. */
private val ACCENT_PRESETS = listOf(
    R.string.accent_sky to Accent.BRAND_SEED,
    R.string.accent_teal to 0xFF00897B,
    R.string.accent_aurora to 0xFF2E9E62,
    R.string.accent_amber to 0xFFED9B00,
    R.string.accent_sunset to 0xFFE8590C,
    R.string.accent_rose to 0xFFD6336C,
    R.string.accent_orchid to 0xFF8E5AD4,
    R.string.accent_slate to 0xFF5C6B7A,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppearanceSection(state: SettingsUiState, viewModel: SettingsViewModel) {
    val spec = state.spec

    // Light / dark / follow-system
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        val modes = listOf(
            ThemeMode.SYSTEM to R.string.theme_mode_system,
            ThemeMode.LIGHT to R.string.theme_mode_light,
            ThemeMode.DARK to R.string.theme_mode_dark,
        )
        modes.forEachIndexed { i, (mode, label) ->
            SegmentedButton(
                selected = spec.mode == mode,
                onClick = { viewModel.setThemeMode(mode) },
                shape = SegmentedButtonDefaults.itemShape(index = i, count = modes.size),
            ) { Text(stringResource(label)) }
        }
    }

    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.settings_accent), style = MaterialTheme.typography.bodyLarge)
    Text(
        stringResource(R.string.settings_accent_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(10.dp))

    var showPicker by remember { mutableStateOf(false) }
    val presetSeeds = ACCENT_PRESETS.map { it.second }
    val customSeed = (spec.accent as? Accent.Seed)?.argb?.takeIf { it !in presetSeeds }

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Wallpaper-derived palette (Material You), API 31+ only
        if (Build.VERSION.SDK_INT >= 31) {
            val context = LocalContext.current
            val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
            val dynamicPrimary = remember(systemDark) {
                runCatching {
                    if (systemDark) dynamicDarkColorScheme(context).primary
                    else dynamicLightColorScheme(context).primary
                }.getOrDefault(Color(Accent.BRAND_SEED))
            }
            AccentSwatch(
                label = stringResource(R.string.accent_dynamic),
                color = dynamicPrimary,
                selected = spec.accent == Accent.Dynamic,
                onClick = { viewModel.setAccent(Accent.Dynamic) },
            )
        }
        ACCENT_PRESETS.forEach { (label, seed) ->
            AccentSwatch(
                label = stringResource(label),
                color = Color(seed),
                selected = spec.accent == Accent.Seed(seed),
                onClick = { viewModel.setAccent(Accent.Seed(seed)) },
            )
        }
        // Cockpit: full curated avionics scheme, not just an accent
        AccentSwatch(
            label = stringResource(R.string.theme_cockpit),
            color = Color(0xFF050807),
            selected = spec.accent == Accent.Cockpit,
            onClick = { viewModel.setAccent(Accent.Cockpit) },
            ring = Color(0xFF53F2A0),
        )
        AccentSwatch(
            label = stringResource(R.string.accent_custom),
            color = customSeed?.let { Color(it) } ?: Color.Unspecified,
            selected = customSeed != null,
            onClick = { showPicker = true },
            rainbow = customSeed == null,
        )
    }

    Spacer(Modifier.height(12.dp))
    ToggleRow(stringResource(R.string.settings_high_contrast), spec.highContrast) {
        viewModel.setHighContrast(it)
    }
    Text(
        stringResource(R.string.settings_high_contrast_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.settings_app_icon), style = MaterialTheme.typography.bodyLarge)
    Text(
        stringResource(R.string.settings_app_icon_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AppIconChoice(
            label = stringResource(R.string.app_icon_regular),
            // The *_bg mipmaps are plain full-bleed PNGs (the adaptive XMLs
            // shadow ic_launcher itself, which painterResource can't decode).
            image = R.mipmap.ic_launcher_bg,
            selected = state.appIcon == AppIcon.REGULAR,
            onClick = { viewModel.setAppIcon(AppIcon.REGULAR) },
        )
        AppIconChoice(
            label = stringResource(R.string.app_icon_fun),
            image = R.mipmap.ic_launcher_fun_bg,
            selected = state.appIcon == AppIcon.FUN,
            onClick = { viewModel.setAppIcon(AppIcon.FUN) },
        )
    }

    if (showPicker) {
        ColorPickerDialog(
            initial = Color(customSeed ?: (spec.accent as? Accent.Seed)?.argb ?: Accent.BRAND_SEED),
            onDismiss = { showPicker = false },
            onPick = { color ->
                showPicker = false
                viewModel.setAccent(Accent.Seed(color.toSeedArgb()))
            },
        )
    }
}

@Composable
private fun AppIconChoice(
    label: String,
    image: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(6.dp),
    ) {
        Image(
            painterResource(image),
            contentDescription = label,
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .border(
                    width = if (selected) 3.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant,
                    shape = CircleShape,
                ),
        )
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun AccentSwatch(
    label: String,
    color: Color,
    selected: Boolean,
    onClick: () -> Unit,
    ring: Color? = null,
    rainbow: Boolean = false,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(60.dp)
            .clip(RoundedCornerShape(10.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(vertical = 4.dp),
    ) {
        val outline = MaterialTheme.colorScheme.outlineVariant
        val selectedRing = MaterialTheme.colorScheme.onSurface
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .then(
                    if (rainbow) Modifier.background(
                        Brush.sweepGradient(
                            listOf(
                                Color(0xFFE8590C), Color(0xFFED9B00), Color(0xFF2E9E62),
                                Color(0xFF00897B), Color(0xFF1667D9), Color(0xFF8E5AD4),
                                Color(0xFFD6336C), Color(0xFFE8590C),
                            )
                        )
                    ) else Modifier.background(color)
                )
                .border(
                    width = if (selected) 3.dp else 1.dp,
                    color = when {
                        selected -> selectedRing
                        ring != null -> ring
                        else -> outline
                    },
                    shape = CircleShape,
                ),
        ) {
            if (selected) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface),
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(16.dp),
                    )
                }
            } else if (ring != null) {
                // Cockpit's identity mark: avionics-green dot on near-black
                Box(Modifier.size(10.dp).clip(CircleShape).background(ring))
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

private fun Color.toSeedArgb(): Long {
    val r = (red * 255f + 0.5f).toInt().coerceIn(0, 255).toLong()
    val g = (green * 255f + 0.5f).toInt().coerceIn(0, 255).toLong()
    val b = (blue * 255f + 0.5f).toInt().coerceIn(0, 255).toLong()
    return 0xFF000000L or (r shl 16) or (g shl 8) or b
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(
            checked = checked,
            onCheckedChange = null,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

@Composable
private fun KeyField(
    label: String,
    configured: Boolean,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
    optional: Boolean = false,
) {
    var value by remember { mutableStateOf("") }
    Column {
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            label = { Text(label) },
            placeholder = { Text(stringResource(R.string.settings_key_hint)) },
            supportingText = {
                Text(
                    when {
                        configured -> stringResource(R.string.settings_key_saved)
                        optional -> stringResource(R.string.settings_key_none_optional)
                        else -> stringResource(R.string.settings_key_none)
                    },
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
        if (value.isNotBlank() || configured) {
            Spacer(Modifier.height(4.dp))
            Row {
                if (value.isNotBlank()) {
                    Button(onClick = { onSave(value); value = "" }) {
                        Text(stringResource(R.string.save))
                    }
                    Spacer(Modifier.padding(4.dp))
                }
                if (configured) {
                    TextButton(onClick = onClear) {
                        Text(stringResource(R.string.settings_key_clear))
                    }
                }
            }
        }
    }
}

private const val ATTRIBUTION_TEXT = """Data sources & licenses:
• Flight status: AeroDataBox (your RapidAPI key) · FlightAware AeroAPI (your key, personal use)
• Live positions: adsb.lol (ODbL) · airplanes.live · adsb.fi (non-commercial, attribution)
• Flown path (optional): OpenSky Network trajectory API (your API client; research/non-commercial terms)
• Airports: OurAirports (Public Domain) · timezones from mwgg/Airports (MIT)
• Airlines: OpenTravelData (CC BY 4.0, filtered to active carriers)
• Weather: NOAA aviationweather.gov · Weather data by Open-Meteo.com (CC BY 4.0)
• Solar math: commons-suncalc (Apache-2.0); great-circle formulas after Chris Veness (MIT); terminator math after Leaflet.Terminator (MIT)
• Font: Inter (Rasmus Andersson), SIL Open Font License 1.1

Privacy: No Blipbird account, backend, or analytics. User-authored flights and settings may be included in Android OS backup or device transfer; operational provider data and API keys are excluded. Configured status providers receive flight identifiers, dates, and credentials as needed; a configured OpenSky client sends OpenSky the aircraft address and your credentials. ADS-B, weather, and map hosts receive their respective queries and ordinary request metadata.

Airline names/codes are trademarks of their respective owners, used for identification only.
All flight data is informational and not for navigation or operational use."""
