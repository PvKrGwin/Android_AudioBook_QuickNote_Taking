package com.praveen.ridernotes.desktop

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopSearchTest {
    @Test
    fun `wildcard search is case insensitive and spans intervening text`() {
        assertTrue(wildcardMatches("hello*praveen", "Hello, I am Praveen"))
        assertTrue(wildcardMatches("AUTOSAR", "Autosar Architecture"))
        assertFalse(wildcardMatches("praveen*hello", "Hello, I am Praveen"))
    }
}
