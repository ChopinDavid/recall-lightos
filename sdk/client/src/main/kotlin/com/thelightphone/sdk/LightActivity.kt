package com.thelightphone.sdk

import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.datastore.preferences.preferencesDataStore

class LightActivity internal constructor() : ComponentActivity() {

    private val backStack = mutableListOf<SimpleLightScreen>()
    private val currentScreen = mutableStateOf<SimpleLightScreen?>(null)
    private var contentReady = false
    private val createdAt = android.os.SystemClock.elapsedRealtime()

    internal fun navigateTo(screen: SimpleLightScreen) {
        currentScreen.value?.notifyWillHide()
        backStack.add(screen)
        screen.notifyWillShow()
        currentScreen.value = screen
    }

    internal fun goBack() {
        val popped = currentScreen.value ?: return
        popped.notifyWillHide()
        popped.destroy()
        backStack.removeAt(backStack.lastIndex)
        if (backStack.isEmpty()) {
            finish()
            return
        }
        val previous = backStack.last()
        previous.notifyWillShow()
        currentScreen.value = previous
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen().setKeepOnScreenCondition {
            !contentReady || android.os.SystemClock.elapsedRealtime() - createdAt < 1000
        }
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)

        val factory = LightSdkRegistry.initialScreenFactory
            ?: throw IllegalStateException("No class annotated with @InitialScreen found")

        val initial = factory(SealedLightActivity(this))

        backStack.add(initial)
        currentScreen.value = initial

        setContent {
            androidx.compose.runtime.LaunchedEffect(Unit) { contentReady = true }
            val screen = currentScreen.value
            if (screen != null) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) {
                        val content: @Composable () -> Unit = { screen.Content() }
                        if (screen is ViewModelStoreOwner) {
                            CompositionLocalProvider(
                                LocalViewModelStoreOwner provides screen,
                                content = content,
                            )
                        } else {
                            content()
                        }
                    }
                    if (screen.showBackBar) {
                        LightBackBar(onBack = ::goBack)
                    }
                }
            }
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    goBack()
                }
            }
        )
    }

    override fun onPause() {
        super.onPause()
        currentScreen.value?.notifyAppPause()
    }

    override fun onResume() {
        super.onResume()
        currentScreen.value?.notifyWillShow()
    }
}

/**
 * Wrapper class to pass around an instance of LightActivity without exposing it to
 * user code. Sorry! :)
 */
class SealedLightActivity(internal val activity: LightActivity)

internal val Context.dataStore by preferencesDataStore(
    name = "DEFAULT_DATASTORE"
)