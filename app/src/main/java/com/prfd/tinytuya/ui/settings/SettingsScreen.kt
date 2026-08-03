package com.prfd.tinytuya.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prfd.tinytuya.data.python.TuyaCloudRegion
import com.prfd.tinytuya.ui.app.AppSettingsUiState
import com.prfd.tinytuya.ui.app.CloudAccountUiState
import com.prfd.tinytuya.ui.components.BrandMark
import com.prfd.tinytuya.ui.theme.TinytuyaTheme

@Composable
fun SettingsScreen(
    state: AppSettingsUiState,
    onRefreshWhenAppOpensChanged: (Boolean) -> Unit,
    onSyncFromCloud: () -> Unit,
    onUpdateCredentials: () -> Unit,
    onForgetCredentials: () -> Unit,
    onDismissError: () -> Unit,
    onBack: () -> Unit,
) {
    var confirmForget by rememberSaveable { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 680.dp)
                    .testTag("settings_list"),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    SettingsHeader(onBack)
                }
                item {
                    Column(Modifier.padding(top = 12.dp, bottom = 4.dp)) {
                        Text(
                            text = "Your TinyTuya setup",
                            style = MaterialTheme.typography.headlineMedium,
                        )
                        Text(
                            text = "Manage the explicit cloud connection and everyday local behavior.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
                item {
                    CloudAccountCard(
                        state = state.cloudAccount,
                        onSync = onSyncFromCloud,
                        onUpdate = onUpdateCredentials,
                        onForget = { confirmForget = true },
                    )
                }
                item {
                    ForegroundRefreshCard(
                        state = state,
                        onChanged = onRefreshWhenAppOpensChanged,
                    )
                }
                item {
                    LocalOnlyCard()
                }
                if (state.errorMessage != null) {
                    item {
                        SettingsErrorCard(
                            code = state.errorCode.orEmpty(),
                            message = state.errorMessage,
                            onDismiss = onDismissError,
                        )
                    }
                }
            }
        }
    }

    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Forget Tuya Cloud credentials?") },
            text = {
                Text(
                    "The saved region, Client ID, and Client Secret will be deleted. " +
                        "Your imported devices and local controls will remain available."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmForget = false
                        onForgetCredentials()
                    },
                    modifier = Modifier.testTag("confirm_forget_credentials"),
                ) {
                    Text("Forget credentials")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmForget = false }) {
                    Text("Keep them")
                }
            },
        )
    }
}

@Composable
private fun SettingsHeader(onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onBack, modifier = Modifier.testTag("settings_back")) {
            Text("Back")
        }
        Spacer(Modifier.width(8.dp))
        BrandMark(Modifier.size(38.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text("Settings", style = MaterialTheme.typography.titleLarge)
            Text(
                text = "APP SETTINGS",
                style = MaterialTheme.typography.labelLarge,
                fontSize = 10.sp,
                letterSpacing = 1.4.sp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun CloudAccountCard(
    state: CloudAccountUiState,
    onSync: () -> Unit,
    onUpdate: () -> Unit,
    onForget: () -> Unit,
) {
    OutlinedCard(
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("cloud_account_card"),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text("Tuya Cloud account", style = MaterialTheme.typography.titleMedium)
            when (state) {
                CloudAccountUiState.Loading -> {
                    Row(
                        modifier = Modifier.padding(top = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                        Text(
                            text = "Opening the encrypted credential vault…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }

                CloudAccountUiState.Missing -> {
                    Text(
                        text = "No cloud credentials are saved yet. Local device control does not need them.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 7.dp),
                    )
                    Button(
                        onClick = onUpdate,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 14.dp)
                            .testTag("save_cloud_credentials"),
                    ) {
                        Text("Save cloud credentials")
                    }
                }

                is CloudAccountUiState.Saved -> {
                    Text(
                        text = "SAVED ON THIS DEVICE",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 5.dp),
                    )
                    Text(
                        text = state.summary.region.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    Text(
                        text = "Client ID · ${state.summary.maskedClientId}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                    Text(
                        text = "The secret stays encrypted and is never shown here. Cloud access happens only when you explicitly sync.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 11.dp),
                    )
                    Button(
                        onClick = onSync,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 14.dp)
                            .testTag("sync_from_tuya"),
                    ) {
                        Text("Sync from Tuya")
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            onClick = onUpdate,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("update_cloud_credentials"),
                        ) {
                            Text("Update credentials")
                        }
                        Spacer(Modifier.width(8.dp))
                        TextButton(
                            onClick = onForget,
                            modifier = Modifier.testTag("forget_cloud_credentials"),
                        ) {
                            Text("Forget")
                        }
                    }
                }

                CloudAccountUiState.Forgetting -> {
                    Row(
                        modifier = Modifier.padding(top = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                        Text(
                            text = "Deleting the credential vault…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }

                is CloudAccountUiState.Recovery -> {
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 7.dp),
                    )
                    Text(
                        text = "Reference · ${state.code}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 7.dp),
                    )
                    OutlinedButton(
                        onClick = onForget,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 14.dp)
                            .testTag("forget_broken_credentials"),
                    ) {
                        Text("Forget and enter again")
                    }
                }
            }
        }
    }
}

@Composable
private fun ForegroundRefreshCard(
    state: AppSettingsUiState,
    onChanged: (Boolean) -> Unit,
) {
    val enabled = state.isLoaded && !state.isSaving
    OutlinedCard(
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Refresh when app opens",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = if (state.refreshWhenAppOpens) "AUTOMATIC" else "MANUAL ONLY",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
                if (state.isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(end = 14.dp)
                            .size(22.dp),
                        strokeWidth = 2.5.dp,
                    )
                }
                Switch(
                    checked = state.refreshWhenAppOpens,
                    onCheckedChange = onChanged,
                    enabled = enabled,
                    modifier = Modifier.testTag("refresh_when_open_switch"),
                )
            }
            Text(
                text = "On the same verified Wi-Fi, TinyTuya goes straight to the saved devices " +
                    "and reads their status. If that network snapshot is no longer trustworthy, " +
                    "it finds the previously matched devices again first.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 13.dp),
            )
            Text(
                text = "Enabled by default. Turn this off to keep both actions manual.",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun LocalOnlyCard() {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text("Local means local", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Automatic refresh uses only UDP discovery when necessary and direct " +
                    "TCP status reads on your Wi-Fi. It never signs in to Tuya Cloud and never " +
                    "runs as a hidden background service.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                modifier = Modifier.padding(top = 7.dp),
            )
        }
    }
}

@Composable
private fun SettingsErrorCard(
    code: String,
    message: String,
    onDismiss: () -> Unit,
) {
    OutlinedCard(
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = "Setting was not saved",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                text = "Reference · $code",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(top = 8.dp),
            )
            TextButton(onClick = onDismiss, modifier = Modifier.padding(top = 4.dp)) {
                Text("Dismiss")
            }
        }
    }
}

@Preview(showBackground = true, heightDp = 780)
@Composable
private fun SettingsPreview() {
    TinytuyaTheme(darkTheme = true) {
        SettingsScreen(
            state = AppSettingsUiState(
                refreshWhenAppOpens = true,
                isLoaded = true,
            ),
            onRefreshWhenAppOpensChanged = {},
            onSyncFromCloud = {},
            onUpdateCredentials = {},
            onForgetCredentials = {},
            onDismissError = {},
            onBack = {},
        )
    }
}

private val TuyaCloudRegion.displayName: String
    get() = when (this) {
        TuyaCloudRegion.CHINA -> "China"
        TuyaCloudRegion.WESTERN_AMERICA -> "Western America"
        TuyaCloudRegion.EASTERN_AMERICA -> "Eastern America"
        TuyaCloudRegion.CENTRAL_EUROPE -> "Central Europe"
        TuyaCloudRegion.WESTERN_EUROPE -> "Western Europe"
        TuyaCloudRegion.INDIA -> "India"
        TuyaCloudRegion.SINGAPORE -> "Singapore"
    }
