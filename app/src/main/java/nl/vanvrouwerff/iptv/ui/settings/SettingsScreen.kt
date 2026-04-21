package nl.vanvrouwerff.iptv.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import nl.vanvrouwerff.iptv.R
import nl.vanvrouwerff.iptv.ui.theme.IptvPalette

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

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(48.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineLarge)

            Text(stringResource(R.string.settings_source_type), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { vm.setType(SourceType.M3u) },
                ) { Text(stringResource(R.string.settings_source_m3u) + (if (state.type == SourceType.M3u) "  \u2713" else "")) }
                Button(
                    onClick = { vm.setType(SourceType.Xtream) },
                ) { Text(stringResource(R.string.settings_source_xtream) + (if (state.type == SourceType.Xtream) "  \u2713" else "")) }
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
                        modifier = Modifier.width(720.dp),
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 16.dp)) {
                Button(onClick = { vm.save(onSaved) }) { Text(stringResource(R.string.settings_save)) }
                // Label matches BackHandler above — "Terug" is unambiguous about whether
                // edits are saved (they're not), where "Kanalen" reads as a navigation shortcut
                // that silently committed.
                Button(onClick = onBack) { Text(stringResource(R.string.detail_back)) }
            }

            Spacer(Modifier.height(16.dp))

            // Manual catalogue refresh. Runs the same pipeline as the auto-refresh on
            // cold-start but on the user's explicit request — covers the case where a
            // provider has just added/removed channels and the auto-refresh window
            // hasn't expired yet.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.settings_refresh_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(R.string.settings_refresh_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = IptvPalette.TextSecondary,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = { vm.refreshNow() },
                    ) {
                        Text(
                            if (state.refreshing)
                                stringResource(R.string.status_refreshing)
                            else
                                stringResource(R.string.settings_refresh_now),
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
            }

            Spacer(Modifier.height(16.dp))

            // Nightly auto-refresh. Kept a simple on/off + hour stepper — a full time picker
            // doesn't map well to D-pad navigation on the Formuler remote.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.settings_auto_refresh_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(R.string.settings_auto_refresh_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = IptvPalette.TextSecondary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { vm.setAutoRefreshEnabled(true) }) {
                        Text(
                            stringResource(R.string.settings_auto_refresh_on) +
                                (if (state.autoRefreshEnabled) "  \u2713" else ""),
                        )
                    }
                    Button(onClick = { vm.setAutoRefreshEnabled(false) }) {
                        Text(
                            stringResource(R.string.settings_auto_refresh_off) +
                                (if (!state.autoRefreshEnabled) "  \u2713" else ""),
                        )
                    }
                }
                if (state.autoRefreshEnabled) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Button(onClick = { vm.bumpAutoRefreshHour(-1) }) {
                            Text(stringResource(R.string.settings_auto_refresh_hour_prev))
                        }
                        Text(
                            stringResource(R.string.settings_auto_refresh_hour, state.autoRefreshHour),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Button(onClick = { vm.bumpAutoRefreshHour(1) }) {
                            Text(stringResource(R.string.settings_auto_refresh_hour_next))
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Profile management. Kept visually separate from the source form so the user
            // doesn't confuse "mijn profiel" with "mijn provider".
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.profiles_title),
                    style = MaterialTheme.typography.titleMedium,
                )
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
        }
    }
}
