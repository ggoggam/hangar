package dev.ggoggam.hangar.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlyAppListTest {
    private val personal = FlyOrganization("personal")
    private val team = FlyOrganization("acme-team")
    private val apps =
        listOf(
            FlyApp("blog", "deployed", organization = personal),
            FlyApp("api", "deployed", organization = team),
            FlyApp("api-staging", "suspended", organization = team),
            FlyApp("web", "deployed", organization = team),
        )

    @Test
    fun pinsAndUnpins() {
        val pins = FlyPins.NONE.toggle("web").toggle("blog")
        assertTrue("web" in pins)
        assertTrue("blog" in pins)
        assertFalse("api" in pins)
        assertFalse("web" in pins.toggle("web"))
        assertTrue(pins.toggle("web").toggle("blog").isEmpty)
    }

    @Test
    fun splitsPinnedAppsOffInOrder() {
        val (pinned, rest) =
            FlyPins.NONE
                .toggle("web")
                .toggle("blog")
                .split(apps)
        assertEquals(listOf("blog", "web"), pinned.map { it.name })
        assertEquals(listOf("api", "api-staging"), rest.map { it.name })
    }

    @Test
    fun keepsFingerprintsNotNames() {
        val pins = FlyPins.NONE.toggle("api-staging").toggle("blog")
        val encoded = pins.encode()
        assertFalse("api" in encoded || "blog" in encoded, encoded)
        assertEquals(2, encoded.split(',').size)
        assertTrue(encoded.split(',').all { Regex("[0-9a-f]{16}").matches(it) }, encoded)

        val decoded = FlyPins.decode(encoded)
        assertEquals(pins, decoded)
        assertTrue("api-staging" in decoded)
        assertFalse("api" in decoded)
    }

    @Test
    fun decodesNothingUsableAsNoPins() {
        assertTrue(FlyPins.decode(null).isEmpty)
        assertTrue(FlyPins.decode("").isEmpty)
        assertTrue(FlyPins.decode("web,blog,{}").isEmpty)
        val one = FlyPins.NONE.toggle("web").encode()
        assertEquals(FlyPins.NONE.toggle("web"), FlyPins.decode("web,$one,"))
    }

    @Test
    fun searchesNamesAndOrganizations() {
        assertEquals(apps, searchApps(apps, ""))
        assertEquals(apps, searchApps(apps, "  "))
        assertEquals(listOf("api", "api-staging"), searchApps(apps, " API ").map { it.name })
        assertEquals(listOf("api-staging"), searchApps(apps, "stag").map { it.name })
        assertEquals(listOf("api", "api-staging", "web"), searchApps(apps, "acme").map { it.name })
        assertEquals(listOf("blog"), searchApps(apps, "personal").map { it.name })
        assertTrue(searchApps(apps, "nothing").isEmpty())
    }
}
