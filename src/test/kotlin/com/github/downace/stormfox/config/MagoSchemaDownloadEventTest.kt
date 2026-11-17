package com.github.downace.stormfox.config

import junit.framework.TestCase

class MagoSchemaDownloadEventTest : TestCase() {
    fun testStaleCallbacksCannotReportSuccessOrFailure() {
        assertEquals(SchemaDownloadEvent.STALE, schemaDownloadEvent("new", "old", "latest", true))
        assertEquals(SchemaDownloadEvent.STALE, schemaDownloadEvent("new", "old", "latest", false))
    }

    fun testCurrentDownloadResultsDistinguishFallbackAndLatestFailure() {
        assertEquals(SchemaDownloadEvent.SUCCESS, schemaDownloadEvent("version", "version", "latest", true))
        assertEquals(SchemaDownloadEvent.FAILED_VERSION, schemaDownloadEvent("version", "version", "latest", false))
        assertEquals(SchemaDownloadEvent.FAILED_LATEST, schemaDownloadEvent("latest", "latest", "latest", false))
        assertEquals(SchemaDownloadEvent.SUCCESS, schemaDownloadEvent("latest", "latest", "latest", true))
    }
}
