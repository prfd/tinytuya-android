package com.prfd.tinytuya

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import com.prfd.tinytuya.data.local.EncryptedDeviceCatalogStore
import com.prfd.tinytuya.ui.app.AppRoute
import com.prfd.tinytuya.ui.app.AppViewModel
import com.prfd.tinytuya.ui.onboarding.OnboardingViewModel
import com.prfd.tinytuya.ui.theme.TinytuyaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val catalogStore = EncryptedDeviceCatalogStore(applicationContext)
        val appViewModel = ViewModelProvider(
            this,
            AppViewModel.factory(catalogStore),
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
