package ru.aireview.web

import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

class ReviewLedger(private val directory: java.nio.file.Path) {
    fun isCompleted(key: String): Boolean = Files.exists(marker(key))

    fun complete(key: String) {
        Files.createDirectories(directory)
        Files.writeString(
            marker(key),
            key,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
    }

    private fun marker(key: String) = directory.resolve(
        MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) } + ".done",
    )
}
