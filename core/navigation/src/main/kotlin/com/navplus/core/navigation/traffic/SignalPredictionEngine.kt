package com.navplus.core.navigation.traffic

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SignalPredictionEngine @Inject constructor() {
    fun predict(input: SignalPredictionInput): SignalPrediction {
        if (input.cycleLengthSeconds <= 0) {
            return SignalPrediction(SignalState.UNKNOWN, null, 0f)
        }
        val cycleSecond = (((input.nowEpochMs / 1_000) + input.phaseOffsetSeconds) %
            input.cycleLengthSeconds).toInt()
        val isGreen = if (input.greenStartSecond <= input.greenEndSecond) {
            cycleSecond in input.greenStartSecond until input.greenEndSecond
        } else {
            cycleSecond >= input.greenStartSecond || cycleSecond < input.greenEndSecond
        }
        val nextChangeSecond = if (isGreen) input.greenEndSecond else input.greenStartSecond
        val secondsUntilChange = ((nextChangeSecond - cycleSecond + input.cycleLengthSeconds) %
            input.cycleLengthSeconds).takeIf { it != 0 } ?: input.cycleLengthSeconds

        return SignalPrediction(
            state = if (isGreen) SignalState.GREEN else SignalState.RED,
            predictedChangeEpochMs = input.nowEpochMs + secondsUntilChange * 1_000L,
            confidence = (input.providerReliability * 0.7f).coerceIn(0f, 0.85f),
        )
    }
}
