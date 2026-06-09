package com.thelightphone.authenticator

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightQrCodeScanner
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class AuthenticatorQrScannerViewModel : LightViewModel()

class AuthenticatorQrScannerScreen(sealedActivity: SealedLightActivity) :
    LightScreen<AuthenticatorQrScannerViewModel>(sealedActivity) {

    private val repository = TotpAccountRepository.getInstance(
        databaseFile = File(filesDir, TotpAccountRepository.DATABASE_FILE_NAME),
    )

    override val viewModelClass: Class<AuthenticatorQrScannerViewModel>
        get() = AuthenticatorQrScannerViewModel::class.java

    override val showBackBar: Boolean = false

    override fun createViewModel() = AuthenticatorQrScannerViewModel()

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        var pendingScan by remember { mutableStateOf<String?>(null) }

        LightTheme(colors = themeColors) {
            LightQrCodeScanner(
                title = "Scan QR Code",
                onScanned = { pendingScan = it },
                onBack = { goBack() },
                modifier = Modifier.background(LightThemeTokens.colors.background),
            )
        }

        LaunchedEffect(pendingScan) {
            val value = pendingScan ?: return@LaunchedEffect

            try {
                OtpAuthUriParser.parse(value).fold(
                    onSuccess = { account ->
                        val stored = withContext(Dispatchers.IO) {
                            repository.addAccount(account)
                        }
                        AuthenticatorQrNavigation.setResult(stored)
                    },
                    onFailure = { error ->
                        AuthenticatorQrNavigation.setError(error.message ?: "Invalid QR code")
                    },
                )
            } catch (error: Exception) {
                AuthenticatorQrNavigation.setError(error.message ?: "Failed to save account")
            }

            goBack()
            navigateTo(::AuthenticatorAccountScreen)
            pendingScan = null
        }
    }
}
