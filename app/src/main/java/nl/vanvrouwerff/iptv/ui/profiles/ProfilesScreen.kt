package nl.vanvrouwerff.iptv.ui.profiles

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.foundation.lazy.list.TvLazyColumn
import androidx.tv.foundation.lazy.list.TvLazyRow
import androidx.tv.foundation.lazy.list.items
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import nl.vanvrouwerff.iptv.R
import nl.vanvrouwerff.iptv.ui.theme.IptvPalette

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ProfilesScreen(
    onBack: () -> Unit,
    onPicked: () -> Unit,
    vm: ProfilesViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()

    Box(modifier = Modifier.fillMaxSize().background(IptvPalette.BackgroundDeep)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 48.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.profiles_title),
                        style = MaterialTheme.typography.headlineLarge.copy(
                            color = IptvPalette.TextPrimary,
                            fontWeight = FontWeight.Black,
                        ),
                    )
                    Spacer(Modifier.weight(1f))
                    Button(onClick = onBack) {
                        Text(
                            text = stringResource(R.string.detail_back),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.profiles_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = IptvPalette.TextSecondary,
                )

                val defaultNameFormat = stringResource(R.string.profiles_default_new_name)
                val editing = state.editing
                if (editing != null) {
                    // Edit mode takes over the body: hiding the TvLazyColumn avoids the
                    // focus tug-of-war where the lazy column kept D-pad focus trapped on
                    // the "Add profile" / "Rename" button that opened the panel, so the
                    // user could never reach Save.
                    BackHandler(enabled = true, onBack = vm::cancelEditing)
                    EditingPanel(
                        editing = editing,
                        onName = vm::updateDraftName,
                        onColor = vm::updateDraftColor,
                        onCancel = vm::cancelEditing,
                        onSave = vm::saveEditing,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                } else {
                    TvLazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentPadding = PaddingValues(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(state.profiles, key = { it.id }) { row ->
                            ProfileRowCard(
                                row = row,
                                onSelect = {
                                    vm.select(row.id)
                                    onPicked()
                                },
                                onEdit = { vm.startEditing(row.id) },
                                onDelete = if (row.isDefault) null else ({ vm.delete(row.id) }),
                            )
                        }
                        item(key = "__new__") {
                            AddProfileButton(onClick = { vm.startCreating(defaultNameFormat) })
                        }
                    }
                }
            }
        }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ProfileRowCard(
    row: ProfileRow,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    // The select surface, Rename, and Delete are siblings in a Row — not nested inside
    // one clickable parent. On Android TV a clickable tv.material3 Surface absorbs D-pad
    // focus as a single unit, which previously made the inline Rename/Delete buttons
    // unreachable: pressing OK on the card always fired onSelect and navigated away.
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            onClick = onSelect,
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = if (row.isActive) IptvPalette.SurfaceLift else IptvPalette.SurfaceElevated,
                contentColor = IptvPalette.TextPrimary,
                focusedContainerColor = IptvPalette.SurfaceLift,
                focusedContentColor = IptvPalette.TextPrimary,
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            modifier = Modifier
                .weight(1f)
                .then(
                    if (row.isActive)
                        Modifier.border(2.dp, IptvPalette.Accent, RoundedCornerShape(14.dp))
                    else Modifier,
                ),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color(row.colorArgb)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = row.name.take(1).uppercase(),
                        style = MaterialTheme.typography.titleLarge.copy(
                            color = Color.White,
                            fontWeight = FontWeight.Black,
                        ),
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = row.name,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (row.isActive) {
                        Text(
                            text = stringResource(R.string.profiles_active),
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = IptvPalette.Accent,
                                fontWeight = FontWeight.Bold,
                            ),
                        )
                    }
                }
            }
        }
        Button(onClick = onEdit) {
            Text(
                stringResource(R.string.profiles_rename),
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        if (onDelete != null) {
            Button(onClick = onDelete) {
                Text(
                    stringResource(R.string.profiles_delete),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AddProfileButton(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = IptvPalette.TextSecondary,
            focusedContainerColor = IptvPalette.SurfaceElevated,
            focusedContentColor = IptvPalette.TextPrimary,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, IptvPalette.SurfaceLift, RoundedCornerShape(14.dp)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(IptvPalette.SurfaceLift),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "+",
                    style = MaterialTheme.typography.titleLarge.copy(
                        color = IptvPalette.TextSecondary,
                        fontWeight = FontWeight.Black,
                    ),
                )
            }
            Spacer(Modifier.width(16.dp))
            Text(
                text = stringResource(R.string.profiles_add),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun EditingPanel(
    editing: EditingState,
    onName: (String) -> Unit,
    onColor: (Int) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Focus Save first (not the text field) for two reasons:
    // 1. The name is already pre-filled with a sensible default, so Save works
    //    immediately — one OK press and the profile is created / renamed.
    // 2. Focusing an OutlinedTextField on Android TV pops the IME over the panel,
    //    which both hides the Save button and confuses users who didn't intend
    //    to type anything.
    // Users who want to rename can D-pad up to reach the text field.
    val saveFocus = remember { FocusRequester() }
    LaunchedEffect(editing.id ?: "__new__") {
        runCatching { saveFocus.requestFocus() }
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(IptvPalette.SurfaceElevated)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = stringResource(
                if (editing.isNew) R.string.profiles_add else R.string.profiles_rename,
            ),
            style = MaterialTheme.typography.titleMedium.copy(
                color = IptvPalette.TextPrimary,
                fontWeight = FontWeight.SemiBold,
            ),
        )
        OutlinedTextField(
            value = editing.name,
            onValueChange = onName,
            singleLine = true,
            label = { androidx.compose.material3.Text(stringResource(R.string.profiles_name_label)) },
            // Done on the on-screen keyboard commits the rename directly. Without this
            // the user has to dismiss the IME (which hides Save), navigate D-pad to the
            // Save button, and click it — an easy flow to lose your edit in.
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSave() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stringResource(R.string.profiles_color_label),
            style = MaterialTheme.typography.labelMedium,
            color = IptvPalette.TextSecondary,
        )
        TvLazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(ProfileColorChoices, key = { it }) { argb ->
                ColorSwatch(
                    argb = argb,
                    selected = argb == editing.colorArgb,
                    onClick = { onColor(argb) },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onSave,
                modifier = Modifier.focusRequester(saveFocus),
            ) {
                Text(
                    stringResource(R.string.settings_save),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                )
            }
            Button(onClick = onCancel) {
                Text(
                    stringResource(R.string.favorites_done),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ColorSwatch(argb: Int, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(999.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color(argb),
            contentColor = Color.White,
            focusedContainerColor = Color(argb),
            focusedContentColor = Color.White,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
        modifier = Modifier
            .size(40.dp)
            .then(
                if (selected)
                    Modifier.border(3.dp, IptvPalette.TextPrimary, RoundedCornerShape(999.dp))
                else Modifier,
            ),
    ) { Box(Modifier.fillMaxSize()) }
}
