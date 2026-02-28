package com.trackspeed.android.ui.screens.tools

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/**
 * Distance unit for conversion.
 */
enum class DistanceUnit(val displayName: String, val toMeters: Double) {
    METERS("Meters", 1.0),
    YARDS("Yards", 0.9144),
    FEET("Feet", 0.3048),
    MILES("Miles", 1609.344),
    KILOMETERS("Kilometers", 1000.0);
}

/**
 * Common preset conversions for quick access.
 */
data class ConversionPreset(
    val label: String,
    val fromValue: Double,
    val fromUnit: DistanceUnit,
    val toUnit: DistanceUnit
)

val PRESETS = listOf(
    ConversionPreset("40yd = 36.576m", 40.0, DistanceUnit.YARDS, DistanceUnit.METERS),
    ConversionPreset("100m = 109.36yd", 100.0, DistanceUnit.METERS, DistanceUnit.YARDS),
    ConversionPreset("60m = 65.62yd", 60.0, DistanceUnit.METERS, DistanceUnit.YARDS)
)

data class DistanceConverterUiState(
    val inputValue: String = "",
    val fromUnit: DistanceUnit = DistanceUnit.METERS,
    val toUnit: DistanceUnit = DistanceUnit.YARDS,
    val result: Double? = null
)

@HiltViewModel
class DistanceConverterViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(DistanceConverterUiState())
    val uiState: StateFlow<DistanceConverterUiState> = _uiState.asStateFlow()

    fun setInputValue(value: String) {
        _uiState.update { it.copy(inputValue = value) }
        recalculate()
    }

    fun setFromUnit(unit: DistanceUnit) {
        _uiState.update { it.copy(fromUnit = unit) }
        recalculate()
    }

    fun setToUnit(unit: DistanceUnit) {
        _uiState.update { it.copy(toUnit = unit) }
        recalculate()
    }

    fun swapUnits() {
        _uiState.update {
            it.copy(fromUnit = it.toUnit, toUnit = it.fromUnit)
        }
        recalculate()
    }

    fun applyPreset(preset: ConversionPreset) {
        _uiState.update {
            it.copy(
                inputValue = if (preset.fromValue == preset.fromValue.toLong().toDouble()) {
                    preset.fromValue.toLong().toString()
                } else {
                    preset.fromValue.toString()
                },
                fromUnit = preset.fromUnit,
                toUnit = preset.toUnit
            )
        }
        recalculate()
    }

    private fun recalculate() {
        val state = _uiState.value
        val input = state.inputValue.toDoubleOrNull()

        if (input == null || input < 0) {
            _uiState.update { it.copy(result = null) }
            return
        }

        val meters = input * state.fromUnit.toMeters
        val result = meters / state.toUnit.toMeters

        _uiState.update { it.copy(result = result) }
    }
}
