package com.vocahq.vocaphone.ui

import android.animation.ValueAnimator
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

private data class WelcomeWord(
    val language: String,
    val greeting: String,
    val endX: Float,
    val endY: Float,
)

private val welcomeWords = listOf(
    WelcomeWord("English", "Hello", -86f, -71f),
    WelcomeWord("हिन्दी", "नमस्ते", 82f, -58f),
    WelcomeWord("Español", "Hola", -91f, 27f),
    WelcomeWord("العربية", "مرحبا", 88f, 39f),
    WelcomeWord("日本語", "こんにちは", -68f, 91f),
    WelcomeWord("Français", "Bonjour", 71f, 94f),
    WelcomeWord("ਪੰਜਾਬੀ", "ਸਤ ਸ੍ਰੀ ਅਕਾਲ", -99f, -18f),
    WelcomeWord("Italiano", "Ciao", 99f, -13f),
)

/** An illustration of multilingual speech. It never opens the microphone. */
@Composable
internal fun OnboardingWordFlow(modifier: Modifier = Modifier) {
    val motionEnabled = remember { ValueAnimator.areAnimatorsEnabled() }
    if (motionEnabled) {
        val transition = rememberInfiniteTransition(label = "Welcome words")
        val time by transition.animateFloat(
            initialValue = 0f,
            targetValue = welcomeWords.size.toFloat(),
            animationSpec = infiniteRepeatable(
                tween(durationMillis = welcomeWords.size * 950, easing = LinearEasing),
            ),
            label = "Greeting flow",
        )
        WelcomeWordFlowFrame(time = time, moving = true, modifier = modifier)
    } else {
        WelcomeWordFlowFrame(time = 0f, moving = false, modifier = modifier)
    }
}

@Composable
private fun WelcomeWordFlowFrame(time: Float, moving: Boolean, modifier: Modifier) {
    val accent = Color(0xFF77D0B2)
    val canvas = Color(0xFF111A15)
    val word = welcomeWords[time.toInt().coerceIn(welcomeWords.indices)]
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(296.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(canvas)
            .clearAndSetSemantics {
                contentDescription = "Animated greetings in English, Hindi, Spanish, Arabic, Japanese, French, Punjabi, and Italian"
            },
    ) {
        listOf(220.dp, 170.dp, 122.dp).forEach { diameter ->
            Box(
                Modifier.align(Alignment.Center)
                    .size(diameter)
                    .border(1.dp, accent.copy(alpha = 0.18f), CircleShape),
            )
        }

        // Words begin at the pulse, move out, then fade before reaching the edge.
        if (moving) {
            welcomeWords.forEachIndexed { index, item ->
                val phase = (time - index + welcomeWords.size) % welcomeWords.size
                if (phase < 3f) {
                    val progress = (phase / 3f).coerceIn(0f, 1f)
                    val opacity = (progress * 5f).coerceAtMost(1f) *
                        ((1f - progress) * 3f).coerceAtMost(1f)
                    Text(
                        text = item.greeting,
                        modifier = Modifier.align(Alignment.Center)
                            .offset(x = (item.endX * progress).dp, y = (item.endY * progress).dp)
                            .graphicsLayer { alpha = opacity },
                        color = if (index % 3 == 0) accent else Color(0xFFE8F7EB),
                        fontSize = if (item.greeting.length > 8) 15.sp else 17.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                }
            }
        }

        Box(
            modifier = Modifier.align(Alignment.Center)
                .size(86.dp)
                .background(accent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(7) { index ->
                    val envelope = listOf(11f, 19f, 27f, 33f, 23f, 16f, 10f)[index]
                    val pulse = if (moving) {
                        0.7f + 0.3f * sin(time.toDouble() * 5 + index * PI / 2).toFloat()
                    } else 1f
                    Box(
                        Modifier.size(width = 3.dp, height = (envelope * pulse).dp)
                            .background(Color(0xFF173C29), CircleShape),
                    )
                }
            }
        }

        Text(
            text = word.language.uppercase(Locale.ROOT),
            modifier = Modifier.align(Alignment.TopCenter).offset(y = 18.dp),
            color = accent,
            style = MaterialTheme.typography.labelMedium,
            letterSpacing = 2.sp,
            textAlign = TextAlign.Center,
        )
        Text(
            text = word.greeting,
            modifier = Modifier.align(Alignment.TopCenter).offset(y = 39.dp),
            color = Color(0xFFE8F7EB),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}
