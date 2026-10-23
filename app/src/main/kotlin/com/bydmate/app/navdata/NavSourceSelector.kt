package com.bydmate.app.navdata

/** Package ownership registry. It is broader than Yandex's parser input set. */
object NavSourceSelector {
    private val owners: Map<String, NavAppId> = NavPackages.GUIDANCE_SOURCES
        .associateWith { NavAppId.YANDEX } + mapOf(
        "com.google.android.apps.maps" to NavAppId.GOOGLE_MAPS,
        "com.waze" to NavAppId.WAZE,
    )

    fun ownerForPackage(packageName: String): NavAppId = owners[packageName] ?: NavAppId.NONE
}
