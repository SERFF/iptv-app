package nl.vanvrouwerff.iptv.ui.reminders

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import nl.vanvrouwerff.iptv.IptvApp
import nl.vanvrouwerff.iptv.R
import nl.vanvrouwerff.iptv.data.DisplayNames
import nl.vanvrouwerff.iptv.data.db.ReminderEntity
import nl.vanvrouwerff.iptv.ui.theme.IptvPalette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Shows the due reminder (if any) over the current screen. */
@Composable
fun ReminderHost(onWatch: (channelId: String) -> Unit) {
    val reminders = IptvApp.get().reminders
    val due by reminders.due.collectAsState()
    val reminder = due ?: return
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
        ReminderPrompt(
            reminder = reminder,
            onWatch = {
                reminders.dismiss(reminder)
                onWatch(reminder.channelId)
            },
            onDismiss = { reminders.dismiss(reminder) },
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ReminderPrompt(
    reminder: ReminderEntity,
    onWatch: () -> Unit,
    onDismiss: () -> Unit,
) {
    val watchFocus = remember { FocusRequester() }
    LaunchedEffect(reminder) { runCatching { watchFocus.requestFocus() } }
    BackHandler(onBack = onDismiss)
    val time = remember(reminder.startMs) { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(reminder.startMs)) }
    val starting = reminder.startMs <= System.currentTimeMillis()
    Column(
        modifier = Modifier
            .padding(40.dp)
            .width(460.dp)
            .background(IptvPalette.SurfaceElevated, RoundedCornerShape(18.dp))
            .padding(22.dp),
    ) {
        Text(
            text = stringResource(if (starting) R.string.reminder_now else R.string.reminder_soon, time),
            style = MaterialTheme.typography.labelLarge,
            color = IptvPalette.AccentSoft,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = reminder.title,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = IptvPalette.TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = DisplayNames.clean(reminder.channelName),
            style = MaterialTheme.typography.bodyMedium,
            color = IptvPalette.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(16.dp))
        Row {
            Button(
                onClick = onWatch,
                modifier = Modifier.focusRequester(watchFocus),
                colors = ButtonDefaults.colors(
                    containerColor = IptvPalette.Accent,
                    contentColor = Color.White,
                    focusedContainerColor = Color.White,
                    focusedContentColor = IptvPalette.BackgroundDeep,
                ),
            ) { Text(stringResource(R.string.reminder_watch)) }
            Spacer(Modifier.width(12.dp))
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.colors(
                    containerColor = IptvPalette.SurfaceLift,
                    contentColor = IptvPalette.TextPrimary,
                    focusedContainerColor = Color.White,
                    focusedContentColor = IptvPalette.BackgroundDeep,
                ),
            ) { Text(stringResource(R.string.reminder_close)) }
        }
    }
}
