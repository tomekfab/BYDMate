package com.bydmate.app.agent

import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * RED API boundary for Step 1. The app-aware test seam is deliberately internal: no production
 * Google Maps or Waze parser/support path is permitted at this milestone.
 */
class AgentToolsNavOwnerTest {

    @Test fun `hub offers an internal owner-aware publication seam for route-info denial tests`() {
        val hub = Class.forName("com.bydmate.app.navdata.NavGuidanceHub")
        val method = hub.methods.singleOrNull { candidate ->
            candidate.name == "publishForTest" && candidate.parameterTypes.firstOrNull()?.name ==
                "com.bydmate.app.navdata.NavAppId"
        }
        assertNotNull(
            "Implement internal publishForTest(owner, read, nowMs); AgentTools tests use it to prove a non-Yandex owner returns no-route-data and no energy fallback.",
            method,
        )
    }
}
