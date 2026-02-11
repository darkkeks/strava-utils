package stravahooks.config

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import java.nio.file.Files

class ConfigLoaderTest {
    @Test
    fun `write stub refuses to overwrite without force`() {
        val dir = Files.createTempDirectory("stravahooks-test")
        val path = dir.resolve("config.json")
        ConfigLoader.writeStub(path, force = true)

        val error = assertFailsWith<IllegalStateException> {
            ConfigLoader.writeStub(path, force = false)
        }
        assertTrue(error.message!!.contains("already exists"))
    }

    @Test
    fun `load requires telegram bot token`() {
        val dir = Files.createTempDirectory("stravahooks-test")
        val path = dir.resolve("config.json")
        path.toFile().writeText("{\"telegram_bot_token\": \"\"}")

        val error = assertFailsWith<IllegalArgumentException> {
            ConfigLoader.load(path)
        }
        assertTrue(error.message!!.contains("telegram_bot_token"))
    }
}
