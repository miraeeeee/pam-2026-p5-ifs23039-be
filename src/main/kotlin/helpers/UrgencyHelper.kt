package org.delcom.helpers

/**
 * Urgency is stored as Int in DB for proper SQL ORDER BY:
 *   1 = low
 *   2 = medium  (default)
 *   3 = high
 *
 * API (request/response) uses lowercase string: "low", "medium", "high"
 */
object UrgencyHelper {

    private val STRING_TO_INT = mapOf(
        "low"    to 1,
        "medium" to 2,
        "high"   to 3
    )

    private val INT_TO_STRING = mapOf(
        1 to "low",
        2 to "medium",
        3 to "high"
    )

    /** Convert string → int, default 2 (medium) if unknown */
    fun toInt(urgency: String?): Int =
        STRING_TO_INT[urgency?.lowercase()] ?: 2

    /** Convert int → string, default "medium" if unknown */
    fun toString(urgency: Int): String =
        INT_TO_STRING[urgency] ?: "medium"

    /** Validate that the string value is one of the allowed values */
    fun isValid(urgency: String?): Boolean =
        urgency?.lowercase() in STRING_TO_INT.keys
}