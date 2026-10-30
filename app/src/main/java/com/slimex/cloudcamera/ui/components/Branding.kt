package com.slimex.cloudcamera.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.slimex.cloudcamera.R
import com.slimex.cloudcamera.core.constants.AppConstants
import com.slimex.cloudcamera.ui.theme.PureBlack
import com.slimex.cloudcamera.ui.theme.SlimePurple
import com.slimex.cloudcamera.ui.theme.TextPrimary
import com.slimex.cloudcamera.ui.theme.TextSecondary
import com.slimex.cloudcamera.ui.theme.TextTertiary

@Composable
fun BrandMark(
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
) {
    Image(
        painter = painterResource(id = R.drawable.ic_logo_slimex),
        contentDescription = "SlimeX Cloud Camera logo",
        modifier = modifier.size(size),
    )
}

@Composable
fun BrandFooter(
    modifier: Modifier = Modifier,
    contentColor: Color = TextTertiary,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(5.dp)
                .background(SlimePurple, CircleShape),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = AppConstants.DEVELOPER_CREDIT,
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
        )
    }
}

/**
 * Branded splash shown while the stored configuration is read.
 * Pure black background, animated brand mark, no white flash.
 */
@Composable
fun BrandedSplash(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "splash-pulse")
    val glow by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.55f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glow",
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(PureBlack),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(280.dp)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(SlimePurple.copy(alpha = glow), Color.Transparent),
                    ),
                    shape = CircleShape,
                ),
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            BrandMark(size = 108.dp)
            Spacer(modifier = Modifier.height(22.dp))
            Text(
                text = "SlimeX Cloud",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                ),
                color = TextPrimary,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "CAMERA  ·  CLOUD  ·  STORAGE",
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
                color = TextSecondary,
                textAlign = TextAlign.Center,
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 56.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(22.dp)
                    .alpha(0.9f),
                color = SlimePurple,
                strokeWidth = 2.dp,
            )
            Spacer(modifier = Modifier.height(20.dp))
            BrandFooter()
        }
    }
}
