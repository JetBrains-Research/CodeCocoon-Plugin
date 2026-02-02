package com.github.pderakhshanfar.codecocoonplugin.transformation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ConfigParsingTest {
    // ========== Success Cases ==========

    @Test
    fun `require extracts String value successfully`() {
        val config = mapOf("name" to "John Doe")
        val result: String = config.require("name")
        assertEquals("John Doe", result)
    }

    @Test
    fun `require extracts Int value successfully`() {
        val config = mapOf("count" to 42)
        val result: Int = config.require("count")
        assertEquals(42, result)
    }

    @Test
    fun `require extracts Boolean value successfully`() {
        val config = mapOf("enabled" to true)
        val result: Boolean = config.require("enabled")
        assertEquals(true, result)
    }

    @Test
    fun `require extracts Double value successfully`() {
        val config = mapOf("price" to 19.99)
        val result: Double = config.require("price")
        assertEquals(19.99, result)
    }

    @Test
    fun `require extracts List value successfully`() {
        val config = mapOf("items" to listOf("a", "b", "c"))
        val result: List<String> = config.require("items")
        assertEquals(listOf("a", "b", "c"), result)
    }

    @Test
    fun `require extracts Map value successfully`() {
        val nestedMap = mapOf("nested" to "value")
        val config = mapOf("data" to nestedMap)
        val result: Map<String, String> = config.require("data")
        assertEquals(nestedMap, result)
    }

    @Test
    fun `require extracts Any value successfully`() {
        val config = mapOf("value" to "anything")
        val result: Any = config.require("value")
        assertEquals("anything", result)
    }

    // ========== Failure Cases: Missing Key ==========

    @Test
    fun `require throws IllegalArgumentException when key is missing`() {
        val config = mapOf("existing" to "value")
        val exception = assertFailsWith<IllegalArgumentException> {
            config.require<String>("missing")
        }
        assertTrue(exception.message!!.contains("Missing required config parameter 'missing'"))
        assertTrue(exception.message!!.contains("String"))
    }

    @Test
    fun `require throws IllegalArgumentException when key is missing with correct type in message`() {
        val config = emptyMap<String, Any>()
        val exception = assertFailsWith<IllegalArgumentException> {
            config.require<Int>("count")
        }
        assertTrue(exception.message!!.contains("Missing required config parameter 'count'"))
        assertTrue(exception.message!!.contains("Int"))
    }

    // ========== Failure Cases: Type Mismatch ==========

    @Test
    fun `require throws IllegalArgumentException when expecting String but got Int`() {
        val config = mapOf("value" to 42)
        val exception = assertFailsWith<IllegalArgumentException> {
            config.require<String>("value")
        }
        assertTrue(exception.message!!.contains("Expected config parameter 'value' to be of type String"))
    }

    @Test
    fun `require throws IllegalArgumentException when expecting Int but got String`() {
        val config = mapOf("value" to "not a number")
        val exception = assertFailsWith<IllegalArgumentException> {
            config.require<Int>("value")
        }
        assertTrue(exception.message!!.contains("Expected config parameter 'value' to be of type Int"))
    }

    @Test
    fun `require throws IllegalArgumentException when expecting Boolean but got String`() {
        val config = mapOf("flag" to "true")
        val exception = assertFailsWith<IllegalArgumentException> {
            config.require<Boolean>("flag")
        }
        assertTrue(exception.message!!.contains("Expected config parameter 'flag' to be of type Boolean"))
    }

    @Test
    fun `require throws IllegalArgumentException when expecting List but got String`() {
        val config = mapOf("items" to "not a list")
        val exception = assertFailsWith<IllegalArgumentException> {
            config.require<List<String>>("items")
        }
        assertTrue(exception.message!!.contains("Expected config parameter 'items' to be of type List"))
    }

    @Test
    fun `require throws IllegalArgumentException when expecting Map but got String`() {
        val config = mapOf("data" to "not a map")
        val exception = assertFailsWith<IllegalArgumentException> {
            config.require<Map<String, Any>>("data")
        }
        assertTrue(exception.message!!.contains("Expected config parameter 'data' to be of type Map"))
    }

    // ========== Edge Cases ==========

    @Test
    fun `require works with empty string value`() {
        val config = mapOf("empty" to "")
        val result: String = config.require("empty")
        assertEquals("", result)
    }

    @Test
    fun `require works with zero value`() {
        val config = mapOf("zero" to 0)
        val result: Int = config.require("zero")
        assertEquals(0, result)
    }

    @Test
    fun `require works with false boolean value`() {
        val config = mapOf("disabled" to false)
        val result: Boolean = config.require("disabled")
        assertEquals(false, result)
    }

    @Test
    fun `require works with empty list`() {
        val config = mapOf("items" to emptyList<String>())
        val result: List<String> = config.require("items")
        assertEquals(emptyList(), result)
    }

    @Test
    fun `require works with empty map`() {
        val config = mapOf("data" to emptyMap<String, Any>())
        val result: Map<String, Any> = config.require("data")
        assertEquals(emptyMap(), result)
    }

    @Test
    fun `require works with keys containing special characters`() {
        val config = mapOf("key-with-dashes" to "value")
        val result: String = config.require("key-with-dashes")
        assertEquals("value", result)
    }

    @Test
    fun `require works with keys containing dots`() {
        val config = mapOf("nested.key.path" to 123)
        val result: Int = config.require("nested.key.path")
        assertEquals(123, result)
    }

    @Test
    fun `require fails gracefully on empty config map`() {
        val config = emptyMap<String, Any>()
        val exception = assertFailsWith<IllegalArgumentException> {
            config.require<String>("anything")
        }
        assertTrue(exception.message!!.contains("Missing required config parameter 'anything'"))
    }
}