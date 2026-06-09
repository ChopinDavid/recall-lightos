package com.thelightphone.authenticator

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.delay

@Composable
fun TotpCodeDisplay(
    issuer: String,
    secret: String,
    digits: Int,
    period: Int,
    algorithm: String,
    modifier: Modifier = Modifier,
) {
    var currentUnixTime by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }

    LaunchedEffect(Unit) {
        while (true) {
            currentUnixTime = System.currentTimeMillis() / 1000
            delay(1000)
        }
    }

    val totp = remember(currentUnixTime, secret, digits, period, algorithm) {
        TotpGenerator.generate(
            secret = secret,
            digits = digits,
            period = period,
            algorithm = algorithm,
            currentUnixTime = currentUnixTime,
        )
    }

    Column(modifier = modifier) {
        LightText(
            text = issuer.ifBlank { "Account" },
            variant = LightTextVariant.Subtitle,
            modifier = Modifier.padding(top = 1.5f.gridUnitsAsDp()),
        )
        LightText(
            text = totp.code,
            variant = LightTextVariant.Title,
            modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
        )
        LightText(
            text = "Expires in: ${totp.remainingSeconds}s",
            variant = LightTextVariant.Copy,
            modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
        )
    }
}
