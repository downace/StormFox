package com.github.downace.stormfox

/** A release version safe to use in Mago documentation and schema URLs. */
internal data class MagoVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<MagoVersion> {
    override fun compareTo(other: MagoVersion): Int =
        compareValuesBy(this, other, MagoVersion::major, MagoVersion::minor, MagoVersion::patch)

    override fun toString(): String = "$major.$minor.$patch"

    val isTested: Boolean get() = this >= MagoVersion(1, 59, 0) && this <= MagoVersion(1, 60, 1)

    companion object {
        private val VERSION = Regex("""(?i)\bmago\s+v?(\d+)\.(\d+)\.(\d+)(?![\w.+-])""")

        fun parse(output: String): MagoVersion? {
            val match = VERSION.find(output) ?: return null
            val numbers = match.groupValues.drop(1).map { it.toIntOrNull() ?: return null }
            return MagoVersion(numbers[0], numbers[1], numbers[2])
        }
    }
}

internal fun versionedMagoDocumentation(html: String, version: MagoVersion?): String =
    html.replace("https://mago.carthage.software/latest/", "https://mago.carthage.software/${version ?: "latest"}/")
