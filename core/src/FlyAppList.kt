package dev.ggoggam.hangar.core

/**
 * The apps pinned to the top of the app list. They're kept on the device, so this holds one-way
 * fingerprints of their names, as [FlyWatchState] does, and never the names themselves. App names
 * are unique across Fly.io, so a pin carries over between accounts only to the same app.
 */
class FlyPins private constructor(
    private val keys: Set<String>,
) {
    val isEmpty: Boolean get() = keys.isEmpty()

    operator fun contains(app: String): Boolean = fingerprint(app) in keys

    /** These pins with [app] pinned, or unpinned if it was already. */
    fun toggle(app: String): FlyPins {
        val key = fingerprint(app)
        return FlyPins(if (key in keys) keys - key else keys + key)
    }

    /** [apps] split into the pinned ones and the rest, each in the order given. */
    fun split(apps: List<FlyApp>): Pair<List<FlyApp>, List<FlyApp>> = apps.partition { it.name in this }

    fun encode(): String = keys.sorted().joinToString(",")

    override fun equals(other: Any?): Boolean = other is FlyPins && other.keys == keys

    override fun hashCode(): Int = keys.hashCode()

    companion object {
        val NONE = FlyPins(emptySet())

        private val KEY = Regex("[0-9a-f]{16}")

        /** The pins [encode] wrote, leaving out anything else; none for null. */
        fun decode(text: String?): FlyPins =
            FlyPins(
                text
                    .orEmpty()
                    .split(',')
                    .filter { KEY.matches(it) }
                    .toSet(),
            )
    }
}

/**
 * [apps] with [query] in their name, or in their organization's slug or name, ignoring case and
 * the spaces around it; all of them for a blank query.
 */
fun searchApps(
    apps: List<FlyApp>,
    query: String,
): List<FlyApp> {
    val q = query.trim()
    if (q.isEmpty()) return apps
    return apps.filter { app ->
        listOfNotNull(app.name, app.organization.slug, app.organization.name).any { it.contains(q, ignoreCase = true) }
    }
}
