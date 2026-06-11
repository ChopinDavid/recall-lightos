package com.thelightphone.authenticator

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class AuthenticatorCodeViewModel : LightViewModel()

class AuthenticatorCodeScreen(sealedActivity: SealedLightActivity) :
    LightScreen<AuthenticatorCodeViewModel>(sealedActivity) {

    private val repository = TotpAccountRepository.getInstance(
        databaseFile = File(filesDir, TotpAccountRepository.DATABASE_FILE_NAME),
    )

    override val viewModelClass: Class<AuthenticatorCodeViewModel>
        get() = AuthenticatorCodeViewModel::class.java

    override val showBackBar: Boolean = false

    override fun createViewModel() = AuthenticatorCodeViewModel()

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val scope = rememberCoroutineScope()
        val accountId = remember { AuthenticatorCodeNavigation.consumeAccountId() }
        var account by remember { mutableStateOf<StoredAccount?>(null) }
        var secret by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(accountId) {
            val id = accountId ?: return@LaunchedEffect
            withContext(Dispatchers.IO) {
                account = repository.getAccount(id)
                secret = repository.decryptSecret(id)
            }
        }

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        onClick = {
                            AuthenticatorCodeNavigation.clear()
                            goBack()
                        },
                    ),
                    center = LightTopBarCenter.Text(
                        account?.issuer?.takeIf { it.isNotBlank() } ?: "Authenticator",
                    ),
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 1f.gridUnitsAsDp()),
                ) {
                    val loadedAccount = account
                    val loadedSecret = secret
                    when {
                        loadedAccount == null || loadedSecret == null -> {
                            LightText(
                                text = if (accountId == null) "(no account selected)" else "Loading…",
                                variant = LightTextVariant.Copy,
                                modifier = Modifier.padding(vertical = 0.75f.gridUnitsAsDp()),
                            )
                        }
                        else -> {
                            TotpCodeDisplay(
                                issuer = loadedAccount.issuer,
                                label = loadedAccount.label,
                                secret = loadedSecret,
                                digits = loadedAccount.digits,
                                period = loadedAccount.period,
                                algorithm = loadedAccount.algorithm,
                            )
                        }
                    }
                }

                val loadedAccount = account
                if (loadedAccount != null) {
                    LightBottomBar(
                        items = listOf(
                            LightBarButton.Text(
                                text = "REMOVE",
                                onClick = {
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            repository.deleteAccount(loadedAccount.id)
                                        }
                                        AuthenticatorCodeNavigation.clear()
                                        goBack()
                                    }
                                },
                            ),
                        ),
                    )
                }
            }
        }
    }
}
