package com.prfd.tinytuya

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import com.prfd.tinytuya.data.lan.AndroidLanDiscoveryRadio
import com.prfd.tinytuya.data.lan.AndroidLanNetworkResolver
import com.prfd.tinytuya.data.lan.DefaultLanDiscoveryCoordinator
import com.prfd.tinytuya.data.lan.DefaultKnownDeviceRefreshCoordinator
import com.prfd.tinytuya.data.lan.DefaultLocalControlCoordinator
import com.prfd.tinytuya.data.lan.DefaultLocalStatusCoordinator
import com.prfd.tinytuya.data.local.AndroidAppSettingsStore
import com.prfd.tinytuya.data.local.EncryptedCloudCredentialStore
import com.prfd.tinytuya.data.local.EncryptedDeviceCatalogStore
import com.prfd.tinytuya.data.python.ChaquopyTuyaPythonGateway
import com.prfd.tinytuya.ui.app.AppRoute
import com.prfd.tinytuya.ui.app.AppViewModel
import com.prfd.tinytuya.ui.onboarding.OnboardingViewModel
import com.prfd.tinytuya.ui.theme.TinytuyaTheme

class MainActivity : ComponentActivity() {
    private lateinit var appViewModel: AppViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val catalogStore = EncryptedDeviceCatalogStore(applicationContext)
        val credentialStore = EncryptedCloudCredentialStore(applicationContext)
        val settingsStore = AndroidAppSettingsStore(applicationContext)
        val gateway = ChaquopyTuyaPythonGateway(applicationContext)
        val networkResolver = AndroidLanNetworkResolver(applicationContext)
        val lanDiscoveryCoordinator = DefaultLanDiscoveryCoordinator(
            gateway = gateway,
            catalogStore = catalogStore,
            networkResolver = networkResolver,
            radio = AndroidLanDiscoveryRadio(applicationContext),
        )
        val localStatusCoordinator = DefaultLocalStatusCoordinator(
            gateway = gateway,
            catalogStore = catalogStore,
        )
        val localControlCoordinator = DefaultLocalControlCoordinator(
            gateway = gateway,
            catalogStore = catalogStore,
            networkResolver = networkResolver,
        )
        val knownDeviceRefreshCoordinator = DefaultKnownDeviceRefreshCoordinator(
            networkResolver = networkResolver,
            localStatusCoordinator = localStatusCoordinator,
        )
        appViewModel = ViewModelProvider(
            this,
            AppViewModel.factory(
                catalogStore = catalogStore,
                lanDiscoveryCoordinator = lanDiscoveryCoordinator,
                localStatusCoordinator = localStatusCoordinator,
                localControlCoordinator = localControlCoordinator,
                lanNetworkObserver = networkResolver,
                knownDeviceRefreshCoordinator = knownDeviceRefreshCoordinator,
                settingsStore = settingsStore,
                credentialStore = credentialStore,
            ),
        )[AppViewModel::class.java]
        val onboardingViewModel = ViewModelProvider(
            this,
            OnboardingViewModel.factory(this, catalogStore, credentialStore),
        )[OnboardingViewModel::class.java]
        setContent {
            TinytuyaTheme {
                AppRoute(
                    appViewModel = appViewModel,
                    onboardingViewModel = onboardingViewModel,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        appViewModel.onAppForegrounded()
    }
}
