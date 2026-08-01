package com.prfd.tinytuya

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import com.prfd.tinytuya.data.lan.AndroidLanDiscoveryRadio
import com.prfd.tinytuya.data.lan.AndroidLanNetworkResolver
import com.prfd.tinytuya.data.lan.DefaultLanDiscoveryCoordinator
import com.prfd.tinytuya.data.lan.DefaultLocalStatusCoordinator
import com.prfd.tinytuya.data.local.EncryptedDeviceCatalogStore
import com.prfd.tinytuya.data.python.ChaquopyTuyaPythonGateway
import com.prfd.tinytuya.ui.app.AppRoute
import com.prfd.tinytuya.ui.app.AppViewModel
import com.prfd.tinytuya.ui.onboarding.OnboardingViewModel
import com.prfd.tinytuya.ui.theme.TinytuyaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val catalogStore = EncryptedDeviceCatalogStore(applicationContext)
        val gateway = ChaquopyTuyaPythonGateway(applicationContext)
        val lanDiscoveryCoordinator = DefaultLanDiscoveryCoordinator(
            gateway = gateway,
            catalogStore = catalogStore,
            networkResolver = AndroidLanNetworkResolver(applicationContext),
            radio = AndroidLanDiscoveryRadio(applicationContext),
        )
        val localStatusCoordinator = DefaultLocalStatusCoordinator(
            gateway = gateway,
            catalogStore = catalogStore,
        )
        val appViewModel = ViewModelProvider(
            this,
            AppViewModel.factory(
                catalogStore,
                lanDiscoveryCoordinator,
                localStatusCoordinator,
            ),
        )[AppViewModel::class.java]
        val onboardingViewModel = ViewModelProvider(
            this,
            OnboardingViewModel.factory(this, catalogStore),
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
}
