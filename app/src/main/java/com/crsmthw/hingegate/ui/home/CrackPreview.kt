package com.crsmthw.hingegate.ui.home

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.crsmthw.hingegate.domain.CrackAppearance
import com.crsmthw.hingegate.domain.CrackSpec
import com.crsmthw.hingegate.domain.HingeInference
import com.crsmthw.hingegate.render.CrackPalette
import com.crsmthw.hingegate.render.CrackRenderer

/**
 * A scaled-down inner screen with a crack drawn by the real [CrackRenderer], so what the preview shows is what an
 * unfold will show. [panelWidthPx] × [panelHeightPx] is the inner panel in its natural orientation; the crack's
 * dp sizes are scaled by the same factor as the panel, so the proportions match the phone.
 *
 * [flickerKey] replays the appear flicker when it changes (the Shuffle button); 0 shows the crack steady.
 */
@Composable
fun CrackPreview(
    spec: CrackSpec,
    palette: CrackPalette,
    panelWidthPx: Int,
    panelHeightPx: Int,
    coverAspect: Float?,
    image: Bitmap?,
    flickerKey: Int,
    description: String,
    modifier: Modifier = Modifier,
) {
    val renderer = remember(palette) { CrackRenderer(palette) }
    val aspect = if (panelWidthPx > 0 && panelHeightPx > 0) panelWidthPx.toFloat() / panelHeightPx else DEFAULT_ASPECT
    var appearAlpha by remember { mutableFloatStateOf(1f) }

    // Finite: one flicker per shuffle, then steady.
    LaunchedEffect(flickerKey) {
        if (flickerKey == 0) return@LaunchedEffect
        val start = withFrameMillis { it }
        var elapsed = 0L
        while (elapsed < CrackAppearance.DURATION_MS) {
            elapsed = withFrameMillis { it } - start
            appearAlpha = CrackAppearance.alpha(elapsed)
        }
        appearAlpha = 1f
    }

    val shape = MaterialTheme.shapes.large
    val background = Brush.linearGradient(
        listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.tertiaryContainer),
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(aspect)
            .clip(shape)
            .background(background)
            .semantics { contentDescription = description },
    ) {
        if (image != null) {
            val bitmap = remember(image) { image.asImageBitmap() }
            Image(bitmap = bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width.toInt()
            val h = size.height.toInt()
            if (w <= 0 || h <= 0) return@Canvas
            val hinge = HingeInference.estimate(w, h, coverAspect)
            // The panel's dp, scaled down to this frame: the crack keeps its real proportions.
            val panelWidth = if (panelWidthPx > 0) panelWidthPx.toFloat() else w.toFloat()
            val previewDensity = density * w / panelWidth
            drawIntoCanvas { canvas ->
                renderer.draw(canvas.nativeCanvas, w, h, 0, hinge, spec, previewDensity, appearAlpha)
            }
        }
    }
}

private const val DEFAULT_ASPECT = 4f / 3f
