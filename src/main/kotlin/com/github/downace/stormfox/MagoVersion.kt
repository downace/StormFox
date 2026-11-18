package com.github.downace.stormfox

/** A release version safe to use in Mago documentation and schema URLs. */
internal data class MagoVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<MagoVersion> {
    override fun compareTo(other: MagoVersion): Int =
        compareValuesBy(this, other, MagoVersion::major, MagoVersion::minor, MagoVersion::patch)

    override fun toString(): String = "$major.$minor.$patch"

    /** Versions before 1.18.0 do not support reading unsaved buffers for diagnostics. */
    val isSupported: Boolean get() = this >= MINIMUM_SUPPORTED

    /** Filepath-aware formatter excludes are available starting with Mago 1.25.0. */
    val supportsFilepathAwareExcludes: Boolean get() = this >= FILEPATH_AWARE_EXCLUDES

    /** Twig stdin formatting and linting were introduced together in Mago 1.60.0. */
    val supportsTwig: Boolean get() = this >= MagoVersion(1, 60, 0)

    companion object {
        private val MINIMUM_SUPPORTED = MagoVersion(1, 18, 0)
        private val FILEPATH_AWARE_EXCLUDES = MagoVersion(1, 25, 0)

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
