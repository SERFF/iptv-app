package nl.vanvrouwerff.iptv.ui.profilepicker

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import nl.vanvrouwerff.iptv.R
import nl.vanvrouwerff.iptv.data.db.ProfileEntity
import nl.vanvrouwerff.iptv.ui.theme.IptvPalette

@Composable
fun ProfilePickerScreen(
    onPicked: () -> Unit,
    onManageProfiles: () -> Unit = {},
    vm: ProfilePickerViewModel = viewModel(),
) {
    val profiles by vm.profiles.collectAsState()
    val app = nl.vanvrouwerff.iptv.IptvApp.get()
    val pin by app.settings.parentalPin.collectAsState(initial = "")
    val activeId by app.activeProfileId.collectAsState()
    val kids by app.kidsMode.collectAsState()
    var pendingProfile by remember { mutableStateOf<ProfileEntity?>(null) }
    var pinError by remember { mutableStateOf<String?>(null) }
    val wrongPin = stringResource(R.string.pin_wrong)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(IptvPalette.BackgroundDeep),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.profile_picker_title),
                fontSize = 44.sp,
                fontWeight = FontWeight.ExtraBold,
                color = IptvPalette.TextPrimary,
            )
            Spacer(Modifier.height(48.dp))
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(horizontal = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(48.dp),
                verticalArrangement = Arrangement.spacedBy(36.dp),
            ) {
                items(profiles, key = { it.id }) { profile ->
                    ProfileTile(
                        profile = profile,
                        onClick = {
                            if (kids && pin.isNotEmpty() && profile.id != activeId) {
                                pinError = null
                                pendingProfile = profile
                            } else {
                                vm.onProfileSelected(profile, onPicked)
                            }
                        },
                    )
                }
            }
            Spacer(Modifier.height(36.dp))
            androidx.tv.material3.Button(onClick = onManageProfiles) {
                androidx.tv.material3.Text(
                    text = stringResource(R.string.profiles_manage),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
        pendingProfile?.let { target ->
            nl.vanvrouwerff.iptv.ui.parental.PinPad(
                title = stringResource(R.string.pin_enter),
                error = pinError,
                onComplete = { entered ->
                    if (entered == pin) {
                        pendingProfile = null
                        vm.onProfileSelected(target, onPicked)
                    } else {
                        pinError = wrongPin
                    }
                },
                onCancel = { pendingProfile = null },
            )
        }
    }
}

@Composable
private fun ProfileTile(profile: ProfileEntity, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.10f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "profile-tile-scale",
    )
    val focusRequester = remember { FocusRequester() }
    // First profile auto-focuses so the D-pad has a clear landing spot.
    LaunchedEffect(profile.id) {
        if (profile.sortIndex == 0) runCatching { focusRequester.requestFocus() }
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .scale(scale)
            .padding(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(160.dp)
                .clip(CircleShape)
                .background(Color(profile.colorArgb))
                .focusRequester(focusRequester)
                .onFocusChanged { focused = it.isFocused }
                .focusable()
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (profile.avatarEmoji != null) {
                Text(profile.avatarEmoji, fontSize = 72.sp)
            } else {
                Text(
                    text = profile.name.take(1).uppercase(),
                    fontSize = 64.sp,
                    fontWeight = FontWeight.Black,
                    color = Color.White.copy(alpha = 0.92f),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            profile.name,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (focused) IptvPalette.TextPrimary else IptvPalette.TextSecondary,
        )
    }
}
