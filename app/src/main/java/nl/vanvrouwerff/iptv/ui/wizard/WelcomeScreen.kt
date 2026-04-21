package nl.vanvrouwerff.iptv.ui.wizard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import nl.vanvrouwerff.iptv.R
import nl.vanvrouwerff.iptv.ui.theme.IptvPalette

@Composable
fun WelcomeScreen(onConfigure: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 64.dp, vertical = 48.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = stringResource(R.string.app_name).uppercase(),
                style = MaterialTheme.typography.labelMedium.copy(
                    color = IptvPalette.Accent,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 4.sp,
                ),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.wizard_welcome_title),
                style = MaterialTheme.typography.displaySmall.copy(
                    fontWeight = FontWeight.ExtraBold,
                    color = IptvPalette.TextPrimary,
                ),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.wizard_welcome_body),
                modifier = Modifier.widthIn(max = 720.dp),
                style = MaterialTheme.typography.bodyLarge.copy(
                    color = IptvPalette.TextSecondary,
                ),
            )

            Spacer(Modifier.height(32.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                WizardStep(
                    number = "1",
                    title = stringResource(R.string.wizard_step1_title),
                    body = stringResource(R.string.wizard_step1_body),
                )
                WizardStep(
                    number = "2",
                    title = stringResource(R.string.wizard_step2_title),
                    body = stringResource(R.string.wizard_step2_body),
                )
                WizardStep(
                    number = "3",
                    title = stringResource(R.string.wizard_step3_title),
                    body = stringResource(R.string.wizard_step3_body),
                )
            }

            Spacer(Modifier.height(32.dp))

            // Typing on a Formuler D-pad is painful; flag the USB-keyboard fallback up-front
            // so users who have one handy reach for it before entering a 60-char URL.
            Text(
                text = stringResource(R.string.wizard_keyboard_hint),
                style = MaterialTheme.typography.labelMedium.copy(
                    color = IptvPalette.TextTertiary,
                    letterSpacing = 1.sp,
                ),
                modifier = Modifier.widthIn(max = 720.dp),
            )

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = onConfigure,
                modifier = Modifier.width(320.dp),
            ) {
                Text(
                    text = stringResource(R.string.wizard_configure),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun WizardStep(number: String, title: String, body: String) {
    Column(
        modifier = Modifier
            .width(220.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(IptvPalette.SurfaceElevated)
            .padding(18.dp),
        verticalArrangement = Arrangement.Top,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(IptvPalette.Accent),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = number,
                style = MaterialTheme.typography.titleMedium.copy(
                    color = androidx.compose.ui.graphics.Color.White,
                    fontWeight = FontWeight.Black,
                ),
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall.copy(
                color = IptvPalette.TextPrimary,
                fontWeight = FontWeight.Bold,
            ),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall.copy(
                color = IptvPalette.TextSecondary,
            ),
        )
    }
}
