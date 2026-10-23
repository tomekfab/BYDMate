package com.bydmate.app.navdata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** RED contract for the owner registry. Reflection keeps this test compilable before the new types exist. */
class NavSourceSelectorTest {

    private fun selector(): Any = Class.forName("com.bydmate.app.navdata.NavSourceSelector")
        .getField("INSTANCE").get(null)

    private fun ownerFor(packageName: String): String {
        val method = selector().javaClass.getMethod("ownerForPackage", String::class.java)
        return method.invoke(selector(), packageName).toString()
    }

    @Test fun `registry assigns every known package to exactly one owner`() {
        assertEquals("YANDEX", ownerFor("ru.yandex.yandexnavi"))
        assertEquals("YANDEX", ownerFor("ru.yandex.yandexmaps"))
        assertEquals("GOOGLE_MAPS", ownerFor("com.google.android.apps.maps"))
        assertEquals("WAZE", ownerFor("com.waze"))
        assertEquals("NONE", ownerFor("example.unknown.navigator"))
    }

    @Test fun `Yandex parser package set remains the exact historic set`() {
        assertEquals(
            setOf(
                "ru.yandex.yandexnavi", "ru.yandex.yandexnavi.inhouse", "ru.yandex.yandexnavi.rustore",
                "ru.yandex.yandexmaps", "ru.yandex.yandexmaps.beta", "ru.yandex.yandexmaps.inhouse",
                "ru.yandex.yandexmaps.rustore",
            ),
            NavPackages.GUIDANCE_SOURCES,
        )
        assertFalse("com.google.android.apps.maps" in NavPackages.GUIDANCE_SOURCES)
        assertFalse("com.waze" in NavPackages.GUIDANCE_SOURCES)
    }

    @Test fun `owner enum exposes only the approved identities`() {
        val ownerClass = Class.forName("com.bydmate.app.navdata.NavAppId")
        assertEquals(setOf("NONE", "YANDEX", "GOOGLE_MAPS", "WAZE"),
            ownerClass.enumConstants.map { it.toString() }.toSet())
    }
}
