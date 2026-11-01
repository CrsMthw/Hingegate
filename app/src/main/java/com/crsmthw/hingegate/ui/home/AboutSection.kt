package com.crsmthw.hingegate.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.crsmthw.hingegate.BuildConfig
import com.crsmthw.hingegate.R

/** Where the About section's links go: Cris's GitHub profile and his Buy Me a Coffee page, as in his other apps. */
internal object AboutLinks {
    const val GITHUB = "https://github.com/CrsMthw"
    const val BUY_ME_A_COFFEE = "https://buymeacoffee.com/crsmthw"
}

/**
 * The end of the screen, laid out like the About section of Cris's other apps (Lyra's): the app's icon, name,
 * tagline and version, the credits, a GitHub card and the Buy Me a Coffee button. The links open in the browser
 * through [onOpenLink]; Hingegate itself still has no internet access.
 */
@Composable
internal fun AboutSection(onOpenLink: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AboutSpacing),
    ) {
        HorizontalDivider()
        AppIconTile()
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            Text(
                stringResource(R.string.about_tagline),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        HorizontalDivider()
        SectionCard(stringResource(R.string.about_credits)) {
            CreditRow(stringResource(R.string.about_made_by), stringResource(R.string.about_made_by_sub))
        }
        GitHubCard(onClick = { onOpenLink(AboutLinks.GITHUB) })
        // Buy Me a Coffee's own button image, the same file as in the other apps; the whole image is the button.
        Image(
            painter = painterResource(R.drawable.bmc_button),
            contentDescription = stringResource(R.string.cd_buy_me_coffee),
            modifier = Modifier
                .fillMaxWidth(COFFEE_WIDTH_FRACTION)
                .clip(MaterialTheme.shapes.medium)
                .clickable(role = Role.Button) { onOpenLink(AboutLinks.BUY_ME_A_COFFEE) },
        )
    }
}

/**
 * The launcher icon as the launcher draws it: the slate background with the foreground at 1.5 times the tile, so
 * the tile shows the adaptive icon's middle 72 of its 108 dp, like a launcher's mask.
 */
@Composable
private fun AppIconTile() {
    Box(
        modifier = Modifier
            .size(IconTileSize)
            .clip(MaterialTheme.shapes.extraLarge)
            .background(colorResource(R.color.ic_launcher_background)),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.requiredSize(IconTileSize * ADAPTIVE_ICON_ZOOM),
        )
    }
}

/** One credit: a heart, the name over the role. */
@Composable
private fun CreditRow(title: String, subtitle: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // The theme's red: a heart, as in the other apps' credits.
        Icon(Icons.Rounded.Favorite, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(SmallIconSize))
        Column(Modifier.padding(start = AboutSpacing)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun GitHubCard(onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Row(Modifier.fillMaxWidth().padding(AboutCardPadding), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(BadgeSize)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Code, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Column(Modifier.weight(1f).padding(horizontal = AboutSpacing)) {
                Text(stringResource(R.string.about_github), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.about_github_handle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.AutoMirrored.Rounded.OpenInNew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(SmallIconSize),
            )
        }
    }
}

private val AboutSpacing = 12.dp
private val AboutCardPadding = 20.dp
private val IconTileSize = 96.dp
private val BadgeSize = 40.dp
private val SmallIconSize = 20.dp

/** An adaptive icon is 108 dp, of which a launcher shows the middle 72 dp. */
private const val ADAPTIVE_ICON_ZOOM = 108f / 72f

/** The Buy Me a Coffee button's share of the section's width, as in Lyra. */
private const val COFFEE_WIDTH_FRACTION = 0.7f
