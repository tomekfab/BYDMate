package com.bydmate.app.navdata

/** Complete app-owned guidance read. Non-Yandex reads never merge with another read. */
data class NavRead(
    val maneuverGaode: Int = 0,
    val distanceMeters: Int = 0,
    val road: String = "",
    val etaSeconds: Int = 0,
    val totalDistMeters: Int = 0,
    val speedLimit: Int = 0,
)
