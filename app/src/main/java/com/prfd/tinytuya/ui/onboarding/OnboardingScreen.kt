package com.prfd.tinytuya.ui.onboarding

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.prfd.tinytuya.data.python.CloudImportResult
import com.prfd.tinytuya.data.python.TuyaCloudRegion
import com.prfd.tinytuya.ui.components.BrandMark
import com.prfd.tinytuya.ui.theme.TinytuyaTheme

private const val TUYA_SETUP_GUIDE_URL =
    "https://developer.tuya.com/en/docs/developer/apply-cloud-api-key?id=Kff30z8sv62ah"

private sealed interface OnboardingDestination {
    data object Welcome : OnboardingDestination
    data object SetupGuide : OnboardingDestination
    data object Credentials : OnboardingDestination
    data object Importing : OnboardingDestination

    data class Error(val error: CloudImportUiState.Error) : OnboardingDestination

    data class Success(val result: CloudImportResult) : OnboardingDestination
}

@Composable
fun OnboardingRoute(
    viewModel: OnboardingViewModel,
    onOpenInventory: () -> Unit,
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()

    SecureWindow(
        enabled = !state.clientId.isBlank ||
            !state.clientSecret.isBlank ||
            !state.sampleDeviceId.isBlank,
    )

    val destination = state.destination()
    BackHandler(enabled = destination != OnboardingDestination.Welcome) {
        viewModel.goBack()
    }

    OnboardingScreen(
        state = state,
        onStartSetup = viewModel::showSetupGuide,
        onSkipGuide = viewModel::showCredentials,
        onBack = viewModel::goBack,
        onOpenOfficialGuide = {
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, TUYA_SETUP_GUIDE_URL.toUri())
                )
            }
        },
        onRegionChanged = viewModel::updateRegion,
        onClientIdChanged = viewModel::updateClientId,
        onClientSecretChanged = viewModel::updateClientSecret,
        onSampleDeviceIdChanged = viewModel::updateSampleDeviceId,
        onToggleAdvanced = viewModel::toggleAdvanced,
        onImport = viewModel::importDevices,
        onDismissError = viewModel::dismissError,
        onReturnToCredentials = viewModel::returnToCredentials,
        onReviewSetup = viewModel::showSetupGuide,
        onOpenInventory = onOpenInventory,
    )
}

@Composable
private fun SecureWindow(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, enabled) {
        val window = view.context.findActivity()?.window
        if (enabled) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }

        onDispose {
            if (enabled) {
                window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun OnboardingScreen(
    state: OnboardingUiState,
    onStartSetup: () -> Unit,
    onSkipGuide: () -> Unit,
    onBack: () -> Unit,
    onOpenOfficialGuide: () -> Unit,
    onRegionChanged: (TuyaCloudRegion) -> Unit,
    onClientIdChanged: (String) -> Unit,
    onClientSecretChanged: (String) -> Unit,
    onSampleDeviceIdChanged: (String) -> Unit,
    onToggleAdvanced: () -> Unit,
    onImport: () -> Unit,
    onDismissError: () -> Unit,
    onReturnToCredentials: () -> Unit,
    onReviewSetup: () -> Unit,
    onOpenInventory: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            AnimatedContent(
                targetState = state.destination(),
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "onboarding destination",
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth()
                    .widthIn(max = 680.dp),
            ) { destination ->
                when (destination) {
                    OnboardingDestination.Welcome -> WelcomeScreen(
                        onStartSetup = onStartSetup,
                        onSkipGuide = onSkipGuide,
                    )

                    OnboardingDestination.SetupGuide -> SetupGuideScreen(
                        onBack = onBack,
                        onOpenOfficialGuide = onOpenOfficialGuide,
                        onContinue = onSkipGuide,
                    )

                    OnboardingDestination.Credentials -> CredentialsScreen(
                        state = state,
                        onBack = onBack,
                        onRegionChanged = onRegionChanged,
                        onClientIdChanged = onClientIdChanged,
                        onClientSecretChanged = onClientSecretChanged,
                        onSampleDeviceIdChanged = onSampleDeviceIdChanged,
                        onToggleAdvanced = onToggleAdvanced,
                        onImport = onImport,
                    )

                    OnboardingDestination.Importing -> ImportingScreen()
                    is OnboardingDestination.Error -> ErrorScreen(
                        error = destination.error,
                        onRetry = onDismissError,
                        onReviewSetup = onReviewSetup,
                    )

                    is OnboardingDestination.Success -> SuccessScreen(
                        result = destination.result,
                        onImportAgain = onReturnToCredentials,
                        onOpenInventory = onOpenInventory,
                    )
                }
            }
        }
    }
}

private fun OnboardingUiState.destination(): OnboardingDestination = when (cloudImport) {
    CloudImportUiState.Loading -> OnboardingDestination.Importing
    is CloudImportUiState.Error -> OnboardingDestination.Error(cloudImport)
    is CloudImportUiState.Success -> OnboardingDestination.Success(cloudImport.result)
    CloudImportUiState.Idle -> when (page) {
        OnboardingPage.WELCOME -> OnboardingDestination.Welcome
        OnboardingPage.SETUP_GUIDE -> OnboardingDestination.SetupGuide
        OnboardingPage.CREDENTIALS -> OnboardingDestination.Credentials
    }
}

@Composable
private fun WelcomeScreen(
    onStartSetup: () -> Unit,
    onSkipGuide: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
    ) {
        Wordmark()
        Spacer(Modifier.height(28.dp))
        HomeNetworkIllustration()
        Spacer(Modifier.height(32.dp))
        Text(
            text = "Your devices.\nYour network.",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = "A focused controller powered by TinyTuya. Import once from Tuya, then keep everyday control on your local network.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        PrivacyPoint(
            mark = "01",
            title = "Local by default",
            body = "Device discovery and control stay on your Wi-Fi.",
        )
        PrivacyPoint(
            mark = "02",
            title = "No surveillance stack",
            body = "No ads, analytics, tracking SDKs, or background cloud polling.",
        )
        PrivacyPoint(
            mark = "03",
            title = "You hold the keys",
            body = "Cloud credentials are used only when you explicitly import or sync.",
        )
        Spacer(Modifier.height(28.dp))
        PrimaryActionButton(
            text = "Set up TinyTuya",
            onClick = onStartSetup,
        )
        TextButton(
            onClick = onSkipGuide,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            Text("I already have cloud credentials")
        }
        Text(
            text = "Independent open-source software · Not affiliated with Tuya",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 12.dp),
        )
    }
}

@Composable
private fun PrivacyPoint(
    mark: String,
    title: String,
    body: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Box(
                modifier = Modifier.size(42.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = mark,
                    style = MaterialTheme.typography.labelLarge,
                    fontSize = 12.sp,
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun SetupGuideScreen(
    onBack: () -> Unit,
    onOpenOfficialGuide: () -> Unit,
    onContinue: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        OnboardingHeader(onBack = onBack, step = 1)
        Spacer(Modifier.height(28.dp))
        Eyebrow("ONE-TIME SETUP")
        Text(
            text = "Create your cloud handshake",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = "Tuya's API gives TinyTuya the device IDs and local keys needed for direct LAN control. After import, normal use does not need the cloud.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp, bottom = 24.dp),
        )
        SetupStep(
            number = "1",
            title = "Pair devices in Smart Life",
            body = "Add each device to the Smart Life app and confirm it responds there. Use the same Smart Life account in the linking step below.",
        )
        SetupStep(
            number = "2",
            title = "Create a Smart Home project",
            body = "On the Tuya Developer Platform, create a Cloud project with Development Method set to Smart Home. Select the data center that matches your Smart Life account.",
            accent = true,
        )
        SecondScreenNote()
        SetupStep(
            number = "3",
            title = "Link your app account",
            body = "Open Devices › Link App Account › Add App Account. Scan the QR code with Smart Life and keep Automatic Link selected.",
        )
        SetupStep(
            number = "4",
            title = "Copy the authorization key",
            body = "From the project's Overview, copy the Client ID and Client Secret. Tuya may require an active IoT Core plan or trial; its availability and terms can change.",
        )
        OutlinedButton(
            onClick = onOpenOfficialGuide,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
        ) {
            Text("Open Tuya's official guide  ↗")
        }
        Spacer(Modifier.height(14.dp))
        PrimaryActionButton(
            text = "I have my credentials",
            onClick = onContinue,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SetupStep(
    number: String,
    title: String,
    body: String,
    accent: Boolean = false,
) {
    val container = if (accent) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val content = if (accent) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Surface(
        color = container,
        contentColor = content,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
    ) {
        Row(modifier = Modifier.padding(18.dp)) {
            Surface(
                color = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                contentColor = if (accent) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                shape = CircleShape,
            ) {
                Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                    Text(number, style = MaterialTheme.typography.labelLarge)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = content.copy(alpha = 0.78f),
                    modifier = Modifier.padding(top = 5.dp),
                )
            }
        }
    }
}

@Composable
private fun SecondScreenNote() {
    OutlinedCard(
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
        ),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.35f),
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
    ) {
        Row(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "QR",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.padding(top = 2.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    text = "Keep a second screen nearby",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Text(
                    text = "You will display a QR code in the developer portal and scan it with Smart Life on this phone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.78f),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CredentialsScreen(
    state: OnboardingUiState,
    onBack: () -> Unit,
    onRegionChanged: (TuyaCloudRegion) -> Unit,
    onClientIdChanged: (String) -> Unit,
    onClientSecretChanged: (String) -> Unit,
    onSampleDeviceIdChanged: (String) -> Unit,
    onToggleAdvanced: () -> Unit,
    onImport: () -> Unit,
) {
    var regionExpanded by remember { mutableStateOf(false) }
    var secretVisible by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        OnboardingHeader(onBack = onBack, step = 2)
        Spacer(Modifier.height(28.dp))
        Eyebrow("SECURE IMPORT")
        Text(
            text = if (state.isCredentialUpdate) {
                "Update cloud credentials"
            } else {
                "Connect your project"
            },
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = if (state.isCredentialUpdate) {
                "Enter the complete replacement authorization key. The saved secret is never revealed or prefilled."
            } else {
                "Enter the authorization key shown on your Tuya Cloud project Overview."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 10.dp, bottom = 24.dp),
        )

        Text(
            text = "Cloud data center",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(start = 4.dp, bottom = 7.dp),
        )
        ExposedDropdownMenuBox(
            expanded = regionExpanded,
            onExpandedChange = { regionExpanded = it },
        ) {
            OutlinedTextField(
                value = state.region.displayName,
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = regionExpanded)
                },
                modifier = Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true)
                    .fillMaxWidth(),
            )
            ExposedDropdownMenu(
                expanded = regionExpanded,
                onDismissRequest = { regionExpanded = false },
            ) {
                TuyaCloudRegion.entries.forEach { region ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(region.displayName)
                                Text(
                                    text = region.apiCode,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        onClick = {
                            onRegionChanged(region)
                            regionExpanded = false
                        },
                    )
                }
            }
        }
        Text(
            text = "This must match the data center selected for your Tuya project.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 18.dp),
        )

        OutlinedTextField(
            value = state.clientId.reveal(),
            onValueChange = onClientIdChanged,
            label = { Text("Client ID / Access ID") },
            placeholder = { Text("Enter your project Client ID") },
            singleLine = true,
            isError = state.validationAttempted && state.clientId.isBlank,
            supportingText = if (state.validationAttempted && state.clientId.isBlank) {
                { Text("Client ID is required") }
            } else {
                null
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = state.clientSecret.reveal(),
            onValueChange = onClientSecretChanged,
            label = { Text("Client Secret / Access Secret") },
            placeholder = { Text("Enter your project secret") },
            singleLine = true,
            isError = state.validationAttempted && state.clientSecret.isBlank,
            supportingText = if (state.validationAttempted && state.clientSecret.isBlank) {
                { Text("Client Secret is required") }
            } else {
                null
            },
            visualTransformation = if (secretVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                TextButton(
                    onClick = { secretVisible = !secretVisible },
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Text(if (secretVisible) "Hide" else "Show")
                }
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        TextButton(
            onClick = onToggleAdvanced,
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
        ) {
            Text(if (state.showAdvanced) "Hide advanced field  −" else "Advanced: sample Device ID  +")
        }
        if (state.showAdvanced) {
            OutlinedTextField(
                value = state.sampleDeviceId.reveal(),
                onValueChange = onSampleDeviceIdChanged,
                label = { Text("Sample Device ID (optional)") },
                supportingText = {
                    Text("Useful for older projects when automatic device lookup is incomplete.")
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        CredentialPrivacyNote()
        PrimaryActionButton(
            text = if (state.isCredentialUpdate) {
                "Verify, save, and sync"
            } else {
                "Connect and import devices"
            },
            onClick = onImport,
            modifier = Modifier.padding(top = 18.dp),
        )
        Text(
            text = "Only credentials accepted by Tuya are saved. They use a separate encrypted Android Keystore vault and are never used for automatic local refresh.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp, bottom = 24.dp),
        )
    }
}

@Composable
private fun CredentialPrivacyNote() {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
    ) {
        Row(modifier = Modifier.padding(16.dp)) {
            LockMark(Modifier.size(30.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Encrypted after verification", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "This form stays only in memory while Tuya verifies it. After success, the region, Client ID, and secret are encrypted on this device and the form is cleared.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun ImportingScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Box(Modifier.size(112.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.size(54.dp),
                    strokeWidth = 5.dp,
                )
            }
        }
        Text(
            text = "Talking to your Tuya project…",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 30.dp),
        )
        Text(
            text = "Fetching linked devices, local keys, and capability mappings. This request is time-bounded and may take a few seconds.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun ErrorScreen(
    error: CloudImportUiState.Error,
    onRetry: () -> Unit,
    onReviewSetup: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(28.dp))
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ) {
            Box(Modifier.size(86.dp), contentAlignment = Alignment.Center) {
                Text("!", style = MaterialTheme.typography.displaySmall)
            }
        }
        Text(
            text = errorTitle(error.code),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 26.dp),
        )
        Text(
            text = error.message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.padding(top = 18.dp),
        ) {
            Text(
                text = "Reference · ${error.code}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            )
        }
        Spacer(Modifier.height(34.dp))
        PrimaryActionButton(text = "Check credentials and retry", onClick = onRetry)
        OutlinedButton(
            onClick = onReviewSetup,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .padding(top = 8.dp),
        ) {
            Text("Review setup guide")
        }
        Text(
            text = "New values are not saved unless Tuya accepts them. If this sync used the saved account, choose the button above to enter a complete replacement.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 18.dp),
        )
    }
}

private fun errorTitle(code: String): String = when (code) {
    "CLOUD_CREDENTIALS_INVALID" -> "Credentials not accepted"
    "CLOUD_PERMISSION_DENIED" -> "Project access is missing"
    "CLOUD_QUOTA_EXHAUSTED" -> "Cloud quota reached"
    "CLOUD_SUBSCRIPTION_INACTIVE" -> "Cloud service is inactive"
    "CLOUD_TIMEOUT" -> "Tuya took too long"
    "CLOUD_NETWORK_ERROR" -> "Could not reach Tuya"
    "CATALOG_KEY_CREATE_FAILED", "CATALOG_KEY_UNAVAILABLE" -> "Secure key unavailable"
    "CATALOG_WRITE_FAILED", "CATALOG_ENCRYPT_FAILED" -> "Could not secure devices"
    "CATALOG_READ_FAILED", "CATALOG_DECRYPT_FAILED", "CATALOG_INVALID" -> "Local catalog needs attention"
    "CREDENTIAL_VAULT_KEY_CREATE_FAILED", "CREDENTIAL_VAULT_KEY_UNAVAILABLE" ->
        "Credential key unavailable"
    "CREDENTIAL_VAULT_WRITE_FAILED", "CREDENTIAL_VAULT_ENCRYPT_FAILED" ->
        "Could not save credentials"
    "CREDENTIAL_VAULT_READ_FAILED", "CREDENTIAL_VAULT_DECRYPT_FAILED",
    "CREDENTIAL_VAULT_INVALID" -> "Saved credentials need attention"
    else -> "Import did not complete"
}

@Composable
private fun SuccessScreen(
    result: CloudImportResult,
    onImportAgain: () -> Unit,
    onOpenInventory: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 28.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                    Text("✓", style = MaterialTheme.typography.headlineMedium)
                }
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Eyebrow("CLOUD IMPORT COMPLETE")
                Text(
                    text = when (result.deviceCount) {
                        0 -> "No linked devices yet"
                        1 -> "1 device found"
                        else -> "${result.deviceCount} devices found"
                    },
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        Text(
            text = if (result.deviceCount == 0) {
                "The project connected successfully and its credentials are encrypted on this device, but Tuya returned no linked devices. Review account linking or add devices in Smart Life."
            } else {
                "The cloud handshake worked. The credential form was cleared, and both your cloud account and device catalog are encrypted locally with separate Android Keystore keys."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 22.dp, bottom = 18.dp),
        )

        if (result.missingLocalKeyCount > 0) {
            WarningCard(
                text = "${result.missingLocalKeyCount} ${if (result.missingLocalKeyCount == 1) "device is" else "devices are"} missing a local key and cannot be controlled locally yet."
            )
        }
        result.warnings.forEach { warning -> WarningCard(text = warning) }

        if (result.devices.isNotEmpty()) {
            Text(
                text = "IMPORTED INVENTORY",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp, bottom = 10.dp),
            )
            result.devices.take(12).forEach { device ->
                ImportedDeviceRow(
                    name = device.name.ifBlank { "Unnamed Tuya device" },
                    category = device.category.ifBlank { "Unknown category" },
                    hasLocalKey = !device.localKey.isBlank,
                )
            }
            if (result.devices.size > 12) {
                Text(
                    text = "+ ${result.devices.size - 12} more devices",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
        ) {
            Column(Modifier.padding(18.dp)) {
                Text("Next: open your local home", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "Open the device list to find devices on Wi-Fi, refresh their current status, and control supported devices locally.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 5.dp),
                )
            }
        }
        if (result.devices.isNotEmpty()) {
            PrimaryActionButton(
                text = "Open device list",
                onClick = onOpenInventory,
                modifier = Modifier.padding(top = 12.dp),
            )
            TextButton(
                onClick = onImportAgain,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Text("Import another project")
            }
        } else {
            PrimaryActionButton(
                text = "Review setup and try again",
                onClick = onImportAgain,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun WarningCard(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp),
    ) {
        Row(Modifier.padding(15.dp)) {
            Text("!", fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ImportedDeviceRow(
    name: String,
    category: String,
    hasLocalKey: Boolean,
) {
    OutlinedCard(
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 9.dp),
    ) {
        Row(
            modifier = Modifier.padding(15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    Text(category.take(2).uppercase().ifBlank { "TU" }, style = MaterialTheme.typography.labelLarge)
                }
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = category,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = if (hasLocalKey) "Ready" else "No key",
                style = MaterialTheme.typography.labelLarge,
                color = if (hasLocalKey) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun OnboardingHeader(
    onBack: () -> Unit,
    step: Int,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = onBack,
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
        ) {
            Text("←  Back")
        }
        Spacer(Modifier.weight(1f))
        StepIndicator(step = step)
    }
}

@Composable
private fun StepIndicator(step: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        repeat(2) { index ->
            Box(
                modifier = Modifier
                    .width(if (index + 1 == step) 28.dp else 8.dp)
                    .height(8.dp)
                    .clip(CircleShape)
                    .background(
                        if (index + 1 <= step) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        }
                    )
            )
        }
    }
}

@Composable
private fun PrimaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = MaterialTheme.shapes.medium,
        contentPadding = PaddingValues(horizontal = 20.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun Wordmark() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BrandMark(Modifier.size(42.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = "TinyTuya",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "LOCAL HOME",
                style = MaterialTheme.typography.labelLarge,
                fontSize = 10.sp,
                letterSpacing = 1.7.sp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun Eyebrow(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontSize = 11.sp,
        letterSpacing = 1.5.sp,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun LockMark(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier = modifier.semantics { contentDescription = "Private" }) {
        val stroke = size.width * 0.09f
        drawRoundRect(
            color = color,
            topLeft = Offset(size.width * 0.25f, size.height * 0.03f),
            size = Size(size.width * 0.50f, size.height * 0.62f),
            cornerRadius = CornerRadius(size.width * 0.24f),
            style = Stroke(width = stroke),
        )
        drawRoundRect(
            color = color,
            topLeft = Offset(size.width * 0.12f, size.height * 0.38f),
            size = Size(size.width * 0.76f, size.height * 0.58f),
            cornerRadius = CornerRadius(size.width * 0.13f),
        )
    }
}

@Composable
private fun HomeNetworkIllustration() {
    val primary = MaterialTheme.colorScheme.primary
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    val tertiary = MaterialTheme.colorScheme.tertiary
    val surface = MaterialTheme.colorScheme.surface
    val outline = MaterialTheme.colorScheme.outlineVariant
    val onSurface = MaterialTheme.colorScheme.onSurface

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(246.dp)
            .clip(MaterialTheme.shapes.extraLarge)
            .background(primaryContainer.copy(alpha = 0.65f))
            .semantics {
                contentDescription = "A private home connected to nearby devices"
            },
    ) {
        Box(
            Modifier
                .size(130.dp)
                .offset(x = (-35).dp, y = (-26).dp)
                .clip(CircleShape)
                .background(tertiary.copy(alpha = 0.13f))
        )
        Box(
            Modifier
                .size(115.dp)
                .align(Alignment.BottomEnd)
                .offset(x = 28.dp, y = 30.dp)
                .clip(CircleShape)
                .background(primary.copy(alpha = 0.10f))
        )

        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width * 0.50f, size.height * 0.55f)
            val deviceLeft = Offset(size.width * 0.14f, size.height * 0.68f)
            val deviceRight = Offset(size.width * 0.86f, size.height * 0.35f)
            val deviceTop = Offset(size.width * 0.67f, size.height * 0.13f)
            listOf(deviceLeft, deviceRight, deviceTop).forEach { node ->
                drawLine(
                    color = primary.copy(alpha = 0.36f),
                    start = center,
                    end = node,
                    strokeWidth = 3.dp.toPx(),
                )
            }

            fun drawNode(at: Offset, radius: Float) {
                drawCircle(surface, radius, at)
                drawCircle(outline, radius, at, style = Stroke(width = 1.dp.toPx()))
                drawCircle(primary, radius * 0.22f, at)
            }
            drawNode(deviceLeft, 24.dp.toPx())
            drawNode(deviceRight, 24.dp.toPx())
            drawNode(deviceTop, 20.dp.toPx())

            val houseWidth = 116.dp.toPx()
            val houseHeight = 92.dp.toPx()
            val houseLeft = center.x - houseWidth / 2
            val houseTop = center.y - houseHeight * 0.30f
            val roof = Path().apply {
                moveTo(houseLeft - 2.dp.toPx(), houseTop + 5.dp.toPx())
                lineTo(center.x, houseTop - 48.dp.toPx())
                lineTo(houseLeft + houseWidth + 2.dp.toPx(), houseTop + 5.dp.toPx())
                close()
            }
            drawPath(roof, primary)
            drawRoundRect(
                color = surface,
                topLeft = Offset(houseLeft, houseTop),
                size = Size(houseWidth, houseHeight),
                cornerRadius = CornerRadius(14.dp.toPx()),
            )
            drawRoundRect(
                color = onSurface,
                topLeft = Offset(center.x - 12.dp.toPx(), houseTop + 47.dp.toPx()),
                size = Size(24.dp.toPx(), 45.dp.toPx()),
                cornerRadius = CornerRadius(7.dp.toPx(), 7.dp.toPx()),
            )
            drawCircle(tertiary, 2.dp.toPx(), Offset(center.x + 6.dp.toPx(), houseTop + 69.dp.toPx()))
        }

        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = CircleShape,
            shadowElevation = 5.dp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(y = (-12).dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(primary)
                )
                Spacer(Modifier.width(8.dp))
                Text("LAN control ready", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

private val TuyaCloudRegion.displayName: String
    get() = when (this) {
        TuyaCloudRegion.CHINA -> "Mainland China"
        TuyaCloudRegion.WESTERN_AMERICA -> "Western America"
        TuyaCloudRegion.EASTERN_AMERICA -> "Eastern America"
        TuyaCloudRegion.CENTRAL_EUROPE -> "Central Europe"
        TuyaCloudRegion.WESTERN_EUROPE -> "Western Europe"
        TuyaCloudRegion.INDIA -> "India"
        TuyaCloudRegion.SINGAPORE -> "Singapore"
    }

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun WelcomePreview() {
    TinytuyaTheme(darkTheme = false) {
        OnboardingScreen(
            state = OnboardingUiState(),
            onStartSetup = {},
            onSkipGuide = {},
            onBack = {},
            onOpenOfficialGuide = {},
            onRegionChanged = {},
            onClientIdChanged = {},
            onClientSecretChanged = {},
            onSampleDeviceIdChanged = {},
            onToggleAdvanced = {},
            onImport = {},
            onDismissError = {},
            onReturnToCredentials = {},
            onReviewSetup = {},
            onOpenInventory = {},
        )
    }
}

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun CredentialsPreview() {
    TinytuyaTheme(darkTheme = true) {
        OnboardingScreen(
            state = OnboardingUiState(page = OnboardingPage.CREDENTIALS),
            onStartSetup = {},
            onSkipGuide = {},
            onBack = {},
            onOpenOfficialGuide = {},
            onRegionChanged = {},
            onClientIdChanged = {},
            onClientSecretChanged = {},
            onSampleDeviceIdChanged = {},
            onToggleAdvanced = {},
            onImport = {},
            onDismissError = {},
            onReturnToCredentials = {},
            onReviewSetup = {},
            onOpenInventory = {},
        )
    }
}
