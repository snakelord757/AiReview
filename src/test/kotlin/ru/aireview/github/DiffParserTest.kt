package ru.aireview.github

import ru.aireview.review.DiffParser
import ru.aireview.review.AddedLine
import kotlin.test.Test
import kotlin.test.assertEquals

class DiffParserTest {
    @Test
    fun `maps additions and context to right side line numbers`() {
        val patch = """
            @@ -10,4 +10,5 @@ fun sample() {
             context
            -old
            +new
            +extra
             tail
        """.trimIndent()

        val parsed = DiffParser.parse(patch)

        assertEquals(setOf(11, 12), parsed.changedRightLines)
        assertEquals(setOf(10, 13), parsed.contextRightLines)
        assertEquals(listOf(AddedLine(11, "new"), AddedLine(12, "extra")), parsed.addedLines)
        assertEquals(listOf("new", "extra"), parsed.addedTextLines)
    }
}
