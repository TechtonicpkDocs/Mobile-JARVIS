package pk.techtonic.jarvis.util

/**
 * Unit converter (W3). Mass: kg, maund (1 maund = 40 kg). Length: metre,
 * yard, gaz (1 yard = 1 gaz = 0.9144 m). Mass <-> length returns null
 * (mismatch — the caller shows hisab_conv_mismatch).
 */
object Units {

    private enum class Family { MASS, LENGTH }

    private data class UnitDef(val family: Family, val toBase: Double)

    private val UNITS = mapOf(
        "kg" to UnitDef(Family.MASS, 1.0),
        "maund" to UnitDef(Family.MASS, 40.0),
        "metre" to UnitDef(Family.LENGTH, 1.0),
        "yard" to UnitDef(Family.LENGTH, 0.9144),
        "gaz" to UnitDef(Family.LENGTH, 0.9144)
    )

    /** Convert [value] from [from] to [to]; null on family mismatch or an
        unknown unit name. Same unit returns the value unchanged. */
    fun convert(value: Double, from: String, to: String): Double? {
        val fromDef = UNITS[from] ?: return null
        val toDef = UNITS[to] ?: return null
        if (fromDef.family != toDef.family) return null
        if (from == to) return value
        return value * fromDef.toBase / toDef.toBase
    }
}
