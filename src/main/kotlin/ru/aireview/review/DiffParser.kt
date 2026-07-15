package ru.aireview.review

data class ParsedPatch(
    val changedRightLines: Set<Int>,
    val contextRightLines: Set<Int>,
    val addedTextLines: List<String>,
) {
    val commentableLines: Set<Int> get() = changedRightLines + contextRightLines
}

object DiffParser {
    private val hunk = Regex("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,(\\d+))? @@")

    fun parse(patch: String): ParsedPatch {
        val changed = linkedSetOf<Int>()
        val context = linkedSetOf<Int>()
        val addedText = mutableListOf<String>()
        var rightLine: Int? = null
        patch.lineSequence().forEach { line ->
            val match = hunk.find(line)
            if (match != null) {
                rightLine = match.groupValues[1].toInt()
            } else if (rightLine != null) {
                when {
                    line.startsWith("+") -> {
                        changed += rightLine
                        addedText += line.removePrefix("+")
                        rightLine += 1
                    }
                    line.startsWith("-") && !line.startsWith("---") -> Unit
                    line.startsWith("\\ No newline") -> Unit
                    else -> {
                        context += rightLine
                        rightLine += 1
                    }
                }
            }
        }
        return ParsedPatch(changed, context, addedText)
    }
}
