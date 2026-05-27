package com.thelightphone.sdk

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.ui.LightColors
import com.thelightphone.sdk.ui.LightColorsPreviewProvider
import com.thelightphone.sdk.ui.LightTheme

@Composable
fun LightBackBar(onBack: () -> Unit) {
    Surface(
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable(onClick = onBack)
                .padding(16.dp)
                .fillMaxWidth(),
        ) {
            Text(
                text = "\u2190 Back",
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}


@Preview(widthDp = 1080/3, heightDp = 1240/3)
@Composable
fun BackButtonPreview(
    @PreviewParameter(LightColorsPreviewProvider ::class) colors: LightColors
) {
    Box(Modifier.fillMaxSize()) {
        LightTheme(colors) {
            LightBackBar {  }
        }
    }
}