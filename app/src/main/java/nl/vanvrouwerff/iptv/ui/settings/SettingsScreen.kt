package nl.vanvrouwerff.iptv.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import nl.vanvrouwerff.iptv.R
import nl.vanvrouwerff.iptv.data.settings.SourceTestResult
import nl.vanvrouwerff.iptv.ui.theme.FocusStyle
import nl.vanvrouwerff.iptv.ui.theme.IptvPalette
import nl.vanvrouwerff.iptv.ui.theme.tvFocus

@Composable
fun SettingsScreen(
    onSaved: () -> Unit,
    onBack: () -> Unit,
    onOpenProfiles: () -> Unit,
    vm: SettingsViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()

    // BACK goes back without saving — matches the "Terug"-labelled button below, so the
    // remote BACK key is no longer a no-op on this screen.
    BackHandler(enabled = true, onBack = onBack)

    Box(modifier = Modifier.fillMaxSize().background(IptvPalette.BackgroundDeep)) {
        // Scrollable: on a 1080p TV (960×540 dp) the lower sections don't fit otherwise.
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 64.dp, vertical = 36.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            Text(
                stringResource(R.string.settings_screen_title),
                style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                color = IptvPalette.TextPrimary,
            )

            SettingsSection(title = stringResource(R.string.settings_section_source)) {
                SourceSection(state = state, vm = vm, onSaved = onSaved, onBack = onBack)
            }

            SettingsSection(title = stringResource(R.string.settings_section_display)) {
                SwitchRow(
                    title = stringResource(R.string.settings_trailers_title),
                    body = stringResource(R.string.settings_trailers_body),
                    checked = state.trailersAutoplay,
                    onToggle = { vm.setTrailersAutoplay(!state.trailersAutoplay) },
                )
                SwitchRow(
                    title = stringResource(R.string.settings_av_sync_title),
                    body = stringResource(R.string.settings_av_sync_body),
                    checked = state.hardwareAvSync,
                    onToggle = { vm.setHardwareAvSync(!state.hardwareAvSync) },
                )
                ChoiceRow(
                    title = stringResource(R.string.settings_aspect_title),
                    value = aspectLabel(state.playerAspect),
                    onClick = vm::cycleAspect,
                )
                ChoiceRow(
                    title = stringResource(R.string.settings_audio_lang_title),
                    value = languageLabel(state.audioLanguage),
                    onClick = vm::cycleAudioLanguage,
                )
                ChoiceRow(
                    title = stringResource(R.string.settings_subtitle_lang_title),
                    value = languageLabel(state.subtitleLanguage),
                    onClick = vm::cycleSubtitleLanguage,
                )
            }

            SettingsSection(title = stringResource(R.string.settings_section_refresh)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(onClick = { vm.refreshNow() }) {
                        Text(
                            if (state.refreshing) stringResource(R.string.status_refreshing)
                            else stringResource(R.string.settings_refresh_now),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }
                    if (state.refreshing) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    if (state.refreshError != null && !state.refreshing) {
                        Text(
                            text = state.refreshError ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = IptvPalette.Accent,
                        )
                    }
                }
                Text(
                    stringResource(R.string.settings_refresh_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = IptvPalette.TextSecondary,
                )
                SwitchRow(
                    title = stringResource(R.string.settings_auto_refresh_title),
                    body = stringResource(R.string.settings_auto_refresh_body),
                    checked = state.autoRefreshEnabled,
                    onToggle = { vm.setAutoRefreshEnabled(!state.autoRefreshEnabled) },
                )
                if (state.autoRefreshEnabled) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(onClick = { vm.bumpAutoRefreshHour(-1) }) {
                            Text(stringResource(R.string.settings_auto_refresh_hour_prev))
                        }
                        Text(
                            stringResource(R.string.settings_auto_refresh_hour, state.autoRefreshHour),
                            style = MaterialTheme.typography.titleMedium,
                            color = IptvPalette.TextPrimary,
                        )
                        Button(onClick = { vm.bumpAutoRefreshHour(1) }) {
                            Text(stringResource(R.string.settings_auto_refresh_hour_next))
                        }
                    }
                }
            }

            SettingsSection(title = stringResource(R.string.profiles_title)) {
                Text(
                    stringResource(R.string.profiles_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = IptvPalette.TextSecondary,
                )
                Button(onClick = onOpenProfiles) {
                    Text(
                        stringResource(R.string.profiles_manage),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }

            // About + required attributions. The TMDB disclaimer wording is dictated by
            // their API terms of use.
            SettingsSection(title = stringResource(R.string.about_title)) {
                listOf(
                    R.string.about_open_source,
                    R.string.about_tmdb_credit,
                    R.string.about_tmdb_disclaimer,
                    R.string.about_formuler_disclaimer,
                ).forEach { res ->
                    Text(
                        stringResource(res),
                        style = MaterialTheme.typography.bodySmall,
                        color = IptvPalette.TextSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceSection(
    state: SettingsUiState,
    vm: SettingsViewModel,
    onSaved: () -> Unit,
    onBack: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SegmentPill(
            label = stringResource(R.string.settings_source_m3u),
            selected = state.type == SourceType.M3u,
            onClick = { vm.setType(SourceType.M3u) },
        )
        SegmentPill(
            label = stringResource(R.string.settings_source_xtream),
            selected = state.type == SourceType.Xtream,
            onClick = { vm.setType(SourceType.Xtream) },
        )
    }
    if (state.filledFromPhone) {
        Text(
            stringResource(R.string.phone_setup_received),
            style = MaterialTheme.typography.bodyMedium,
            color = IptvPalette.AccentSoft,
        )
    }
    when (state.type) {
        SourceType.M3u -> OutlinedTextField(
            value = state.m3uUrl,
            onValueChange = vm::setM3uUrl,
            label = { androidx.compose.material3.Text(stringResource(R.string.settings_m3u_url)) },
            singleLine = true,
            modifier = Modifier.width(720.dp),
        )
        SourceType.Xtream -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = state.host,
                onValueChange = vm::setHost,
                label = { androidx.compose.material3.Text(stringResource(R.string.settings_xtream_host)) },
                singleLine = true,
                modifier = Modifier.width(720.dp),
            )
            OutlinedTextField(
                value = state.username,
                onValueChange = vm::setUsername,
                label = { androidx.compose.material3.Text(stringResource(R.string.settings_xtream_user)) },
                singleLine = true,
                modifier = Modifier.width(720.dp),
            )
            OutlinedTextField(
                value = state.password,
                onValueChange = vm::setPassword,
                label = { androidx.compose.material3.Text(stringResource(R.string.settings_xtream_pass)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.width(720.dp),
            )
            OutlinedTextField(
                value = state.categoryFilter,
                onValueChange = vm::setCategoryFilter,
                label = { androidx.compose.material3.Text(stringResource(R.string.settings_category_filter)) },
                supportingText = {
                    androidx.compose.material3.Text(stringResource(R.string.settings_category_filter_hint))
                },
                singleLine = true,
                modifier = Modifier.width(720.dp),
            )
        }
    }

    state.validationError?.let {
        Text(text = it, style = MaterialTheme.typography.bodyMedium, color = IptvPalette.Accent)
    }

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = { vm.save(onSaved) }) { Text(stringResource(R.string.settings_save)) }
        Button(onClick = vm::testConnection) {
            Text(stringResource(if (state.testing) R.string.settings_testing else R.string.settings_test))
        }
        // Label matches BackHandler above — "Terug" is unambiguous about whether edits are
        // saved (they're not).
        Button(onClick = onBack) { Text(stringResource(R.string.detail_back)) }
        if (state.testing) {
            androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        }
    }
    state.testResult?.let { TestResultLine(it) }

    Spacer(Modifier.height(4.dp))
    PhoneSetupPanel()
}

@Composable
private fun TestResultLine(result: SourceTestResult) {
    val (text, color) = when (result) {
        is SourceTestResult.Ok -> stringResource(
            R.string.settings_test_ok,
            formatCount(result.live),
            formatCount(result.movies),
            formatCount(result.series),
        ) to IptvPalette.TextPrimary
        is SourceTestResult.Failed -> {
            val reason = when (result.reason) {
                SourceTestResult.Reason.BadLogin -> stringResource(R.string.settings_test_bad_login)
                SourceTestResult.Reason.HostUnreachable -> stringResource(R.string.settings_test_unreachable)
                SourceTestResult.Reason.Timeout -> stringResource(R.string.settings_test_timeout)
                SourceTestResult.Reason.NotAPlaylist -> stringResource(R.string.settings_test_not_playlist)
                SourceTestResult.Reason.HttpError,
                SourceTestResult.Reason.Other,
                -> stringResource(R.string.settings_test_other, result.detail ?: "")
            }
            reason to IptvPalette.Accent
        }
    }
    Text(text = text, style = MaterialTheme.typography.bodyMedium, color = color)
}

private fun formatCount(n: Int): String =
    java.text.NumberFormat.getIntegerInstance(java.util.Locale("nl", "NL")).format(n)

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = IptvPalette.TextPrimary,
        )
        Box(
            modifier = Modifier
                .width(56.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(IptvPalette.Accent),
        )
        content()
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SwitchRow(title: String, body: String?, checked: Boolean, onToggle: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Surface(
        onClick = onToggle,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = IptvPalette.SurfaceLift,
            contentColor = IptvPalette.TextPrimary,
            focusedContainerColor = FocusStyle.Fill,
            focusedContentColor = IptvPalette.TextPrimary,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = Modifier
            .width(720.dp)
            .onFocusChanged { focused = it.isFocused }
            .tvFocus(focused, shape),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                body?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = IptvPalette.TextSecondary)
                }
            }
            Spacer(Modifier.width(16.dp))
            SwitchVisual(checked)
        }
    }
}

/** Track + thumb drawn in app colours; the whole row is the focus/click target. */
@Composable
private fun SwitchVisual(checked: Boolean) {
    Box(
        modifier = Modifier
            .size(width = 48.dp, height = 26.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(if (checked) IptvPalette.Accent else IptvPalette.SurfaceElevated)
            .padding(3.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(Color.White),
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ChoiceRow(title: String, value: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = IptvPalette.SurfaceLift,
            contentColor = IptvPalette.TextPrimary,
            focusedContainerColor = FocusStyle.Fill,
            focusedContentColor = IptvPalette.TextPrimary,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = Modifier
            .width(720.dp)
            .onFocusChanged { focused = it.isFocused }
            .tvFocus(focused, shape),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(
                text = "$value  ›",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = IptvPalette.AccentSoft,
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SegmentPill(label: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(999.dp)
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) IptvPalette.Accent else IptvPalette.SurfaceLift,
            contentColor = if (selected) Color.White else IptvPalette.TextSecondary,
            focusedContainerColor = if (selected) IptvPalette.Accent else FocusStyle.Fill,
            focusedContentColor = Color.White,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = Modifier
            .onFocusChanged { focused = it.isFocused }
            .tvFocus(focused, shape, FocusStyle.ChipScale),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun aspectLabel(value: String): String = stringResource(
    when (value) {
        "FILL" -> R.string.player_aspect_fill
        "ZOOM" -> R.string.player_aspect_zoom
        else -> R.string.player_aspect_fit
    },
)

@Composable
private fun languageLabel(code: String): String = when (code) {
    "" -> stringResource(R.string.settings_lang_none)
    "off" -> stringResource(R.string.settings_lang_off)
    else -> java.util.Locale(code).getDisplayLanguage(java.util.Locale("nl", "NL"))
        .replaceFirstChar { it.uppercase() }
}
