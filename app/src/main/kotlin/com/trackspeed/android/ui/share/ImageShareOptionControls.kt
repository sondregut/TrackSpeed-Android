package com.trackspeed.android.ui.share

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.trackspeed.android.R
import com.trackspeed.android.videooverlay.OverlaySpeedUnit

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RunImageOptionControls(
    options: RunImageOptions,
    hasSplits: Boolean,
    hasPhotos: Boolean,
    hasStartType: Boolean,
    hasName: Boolean,
    onChange: (RunImageOptions) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RunImageFormat.entries.forEach { format ->
                FilterChip(selected = options.format == format, onClick = { onChange(options.copy(format = format)) },
                    label = { Text(stringResource(if (format == RunImageFormat.STORY) R.string.image_share_story_format else R.string.image_share_feed_format)) },
                    modifier = Modifier.testTag("image-format-${format.name}"))
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (hasSplits) FilterChip(selected = options.displaysSplits(hasPhotos), enabled = !options.feedHidesSplits(hasPhotos),
                onClick = { onChange(options.copy(showSplits = !options.showSplits)) },
                label = { Text(stringResource(R.string.race_splits)) }, modifier = Modifier.testTag("image-option-splits"))
            FilterChip(selected = options.showPhotos, onClick = { onChange(options.copy(showPhotos = !options.showPhotos)) },
                label = { Text(stringResource(R.string.image_share_photos)) }, modifier = Modifier.testTag("image-option-photos"))
            if (hasStartType) FilterChip(selected = options.showStartType, onClick = { onChange(options.copy(showStartType = !options.showStartType)) },
                label = { Text(stringResource(R.string.setup_start_type)) }, modifier = Modifier.testTag("image-option-start"))
            if (hasName) FilterChip(selected = options.showName, onClick = { onChange(options.copy(showName = !options.showName)) },
                label = { Text(stringResource(R.string.image_share_name)) }, modifier = Modifier.testTag("image-option-name"))
        }
        if (hasSplits && options.feedHidesSplits(hasPhotos)) {
            Text(stringResource(R.string.image_share_feed_splits_hint), modifier = Modifier.testTag("image-feed-hint"))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SessionImageOptionControls(
    options: SessionImageOptions,
    hasSplits: Boolean,
    hasName: Boolean,
    onChange: (SessionImageOptions) -> Unit,
    modifier: Modifier = Modifier
) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (hasSplits) FilterChip(selected = options.showSplits, onClick = { onChange(options.copy(showSplits = !options.showSplits)) },
            label = { Text(stringResource(R.string.race_splits)) }, modifier = Modifier.testTag("image-option-splits"))
        FilterChip(selected = options.showPhotos, onClick = { onChange(options.copy(showPhotos = !options.showPhotos)) },
            label = { Text(stringResource(R.string.image_share_photos)) }, modifier = Modifier.testTag("image-option-photos"))
        if (hasName) FilterChip(selected = options.showName, onClick = { onChange(options.copy(showName = !options.showName)) },
            label = { Text(stringResource(R.string.image_share_name)) }, modifier = Modifier.testTag("image-option-name"))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RunImageSpeedUnitControls(unit: OverlaySpeedUnit, onChange: (OverlaySpeedUnit) -> Unit,
    modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(stringResource(R.string.overlay_speed_units))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OverlaySpeedUnit.entries.forEach { choice ->
                FilterChip(selected = unit == choice, onClick = { onChange(choice) },
                    label = { Text(choice.label) }, modifier = Modifier.testTag("image-speed-unit-${choice.name}"))
            }
        }
    }
}
