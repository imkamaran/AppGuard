package io.github.imkamaran.appguard.policy

/**
 * A validated synthetic location. The Android-specific [android.location.Location] is built from
 * this inside the hooked process; keeping the numbers here keeps them unit-testable on the JVM.
 */
data class LocationSpec(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val altitudeMeters: Double?,
) {
    companion object {
        const val DEFAULT_ACCURACY_M = 10f
        const val MIN_ACCURACY_M = 0.1f
        const val MAX_ACCURACY_M = 10_000f
        const val MIN_ALTITUDE_M = -500.0
        const val MAX_ALTITUDE_M = 10_000.0

        /** @return the spec, or the list of problems that make the input unusable */
        fun from(policy: LocationPolicy): Validated<LocationSpec> {
            val errors = validate(policy.latitude, policy.longitude, policy.accuracyMeters, policy.altitudeMeters)
            if (errors.isNotEmpty()) return Validated.Invalid(errors)
            return Validated.Valid(
                LocationSpec(
                    latitude = policy.latitude,
                    longitude = policy.longitude,
                    accuracyMeters = policy.accuracyMeters ?: DEFAULT_ACCURACY_M,
                    altitudeMeters = policy.altitudeMeters,
                ),
            )
        }

        fun validate(latitude: Double, longitude: Double, accuracy: Float?, altitude: Double?): List<String> {
            val errors = mutableListOf<String>()
            if (!latitude.isFinite() || latitude < -90.0 || latitude > 90.0) {
                errors += "Latitude must be between -90 and 90."
            }
            if (!longitude.isFinite() || longitude < -180.0 || longitude > 180.0) {
                errors += "Longitude must be between -180 and 180."
            }
            if (accuracy != null && (!accuracy.isFinite() || accuracy < MIN_ACCURACY_M || accuracy > MAX_ACCURACY_M)) {
                errors += "Accuracy must be between $MIN_ACCURACY_M and ${MAX_ACCURACY_M.toInt()} meters."
            }
            if (altitude != null && (!altitude.isFinite() || altitude < MIN_ALTITUDE_M || altitude > MAX_ALTITUDE_M)) {
                errors += "Altitude must be between ${MIN_ALTITUDE_M.toInt()} and ${MAX_ALTITUDE_M.toInt()} meters."
            }
            return errors
        }

        /** Parses user text such as "51.5007, -0.1246"; returns null when it is not two numbers. */
        fun parsePair(text: String): Pair<Double, Double>? {
            val parts = text.split(',', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.size != 2) return null
            val lat = parts[0].toDoubleOrNull() ?: return null
            val lon = parts[1].toDoubleOrNull() ?: return null
            return lat to lon
        }
    }
}

sealed interface Validated<out T> {
    data class Valid<T>(val value: T) : Validated<T>
    data class Invalid(val errors: List<String>) : Validated<Nothing>
}
