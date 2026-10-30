package com.example.listea.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The condition's stored grammar: two spellings, one canonical key order, and fail-closed reading.
 *
 * The condition is a permanent part of a Task's declaration and travels in the export, so the
 * bytes are pinned: a reader that accepted a reordered or half-spelled object would make two
 * logically identical conditions distinguishable, and one that ignored an unknown key would draw a
 * rule nobody wrote.
 */
class PartConditionTest {

    private val equalsCondition = PartCondition(
        sourcePartKey = "gate",
        field = "choice",
        match = PartConditionMatch.Equals,
        values = listOf("yes")
    )

    private val inCondition = PartCondition(
        sourcePartKey = "gate",
        field = "ate",
        match = PartConditionMatch.In,
        values = listOf("yes", "maybe")
    )

    @Test
    fun `the canonical object fixes the key order`() {
        assertEquals(
            """{"prompt":"gate","field":"choice","equals":"yes"}""",
            encodePartCondition(equalsCondition)
        )
        assertEquals(
            """{"prompt":"gate","field":"ate","in":["yes","maybe"]}""",
            encodePartCondition(inCondition)
        )
    }

    @Test
    fun `a condition round-trips through its canonical text`() {
        listOf(equalsCondition, inCondition).forEach { condition ->
            val text = encodePartCondition(condition)
            assertEquals(condition, decodePartCondition(text))
            assertEquals(text, encodePartCondition(decodePartCondition(text)!!))
        }
    }

    @Test
    fun `reading is strict about keys and spellings`() {
        val refusals = listOf(
            "not json",
            "[]",
            """{}""",
            """{"prompt":"gate","field":"choice"}""",
            """{"prompt":"gate","field":"choice","equals":"yes","in":["yes"]}""",
            """{"prompt":"gate","field":"choice","equals":"yes","note":"x"}""",
            """{"prompt":"Gate","field":"choice","equals":"yes"}""",
            """{"prompt":"gate","field":"choice id","equals":"yes"}""",
            """{"prompt":"gate","field":"choice","equals":""}""",
            """{"prompt":"gate","field":"choice","equals":1}""",
            """{"prompt":"gate","field":"choice","in":[]}""",
            """{"prompt":"gate","field":"choice","in":["yes",1]}""",
            """{"prompt":"gate","field":"choice","in":[""]}""",
            """{"prompt":"gate","field":"choice","in":[${(1..51).joinToString(",") { "\"v$it\"" }}]}"""
        )
        refusals.forEach { text ->
            assertNull("should have been refused: $text", decodePartCondition(text))
        }
    }

    @Test
    fun `matches is membership and a missing answer never matches`() {
        assertTrue(equalsCondition.matches("yes"))
        assertFalse(equalsCondition.matches("no"))
        assertFalse(equalsCondition.matches(null))
        assertFalse(equalsCondition.matches(""))
        assertTrue(inCondition.matches("maybe"))
        assertFalse(inCondition.matches("no"))
    }
}
