package com.prfd.tinytuya

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import com.prfd.tinytuya.ui.onboarding.OnboardingViewModel
import com.prfd.tinytuya.ui.onboarding.OnboardingRoute
import com.prfd.tinytuya.ui.theme.TinytuyaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val onboardingViewModel = ViewModelProvider(
            this,
            OnboardingViewModel.factory(this),
        )[OnboardingViewModel::class.java]
        setContent {
            TinytuyaTheme {
                OnboardingRoute(viewModel = onboardingViewModel)
            }
        }
    }
}
