package de.lautstark.vorlaut.boardpackage

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

/**
 * SPEC.md 5's `data`: a picture or a clip carried in the board as a base64 data
 * URI. "An importer MUST accept it", and this one used to answer image_missing.
 *
 * No exchange fixture carries `data`, so the packages here are built in-process
 * like SpecRulesTest's, which keeps this a test of the rule and not a second,
 * unpinned fixture set. The media are real enough for the importer's own
 * header checks: a PNG from ImageIO and a short 16 kHz mono PCM WAV.
 */
class InlineMediaTest {
    private val png: ByteArray by lazy {
        val out = ByteArrayOutputStream()
        ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB), "png", out)
        out.toByteArray()
    }

    /** A tenth of a second of silence, 16 kHz mono 16-bit, as SPEC.md 6 asks. */
    private val wav: ByteArray by lazy {
        val samples = 1600
        val data = samples * 2
        ByteBuffer
            .allocate(44 + data)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                put("RIFF".toByteArray())
                putInt(36 + data)
                put("WAVE".toByteArray())
                put("fmt ".toByteArray())
                putInt(16)
                putShort(1) // PCM
                putShort(1) // mono
                putInt(16_000)
                putInt(16_000 * 2)
                putShort(2)
                putShort(16)
                put("data".toByteArray())
                putInt(data)
            }.array()
    }

    private fun dataUri(
        type: String,
        bytes: ByteArray,
    ) = "data:$type;base64," + Base64.getEncoder().encodeToString(bytes)

    private fun importWith(
        image: String,
        sound: String,
    ): ImportResult {
        val manifest =
            """
            {
              "format": "open-board-0.1",
              "root": "boards/b.obf",
              "paths": { "boards": { "b": "boards/b.obf" }, "images": {}, "sounds": {} },
              "ext_lautstark_spec_version": "1.0.0",
              "ext_lautstark_package_id": "inline",
              "ext_lautstark_package_name": "Inline",
              "ext_lautstark_modified": "2026-08-24T09:00:00Z",
              "ext_lautstark_symbol_source": "none",
              "ext_lautstark_redistributable": true
            }
            """.trimIndent()
        val board =
            """
            {
              "format": "open-board-0.1",
              "id": "b",
              "locale": "de",
              "name": "Board",
              "buttons": [ { "id": "b1", "label": "Ball", "image_id": "i1", "sound_id": "s1" } ],
              "images": [ { "id": "i1", "data": "$image", "width": 8, "height": 8 } ],
              "sounds": [ { "id": "s1", "data": "$sound" } ],
              "grid": { "rows": 1, "columns": 1, "order": [["b1"]] }
            }
            """.trimIndent()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            listOf("manifest.json" to manifest, "boards/b.obf" to board).forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return BoardPackageImporter.import(out.toByteArray())
    }

    @Test
    fun `an image and a sound carried as data are accepted and can be read back`() {
        val imageUri = dataUri("image/png", png)
        val soundUri = dataUri("audio/wav", wav)
        val accepted = importWith(imageUri, soundUri) as ImportResult.Accepted
        assertTrue("no warnings for inline media: ${accepted.warnings}", accepted.warnings.isEmpty())

        val button =
            accepted.boardPackage.boards
                .single()
                .buttons
                .single()
        assertEquals(ButtonState.NORMAL, button.state)
        val imageAddress = requireNotNull(button.imagePath) { "the picture was dropped" }
        val soundAddress = (button.audio as AudioSource.Recorded).path

        // What the renderer will do with them: ask the archive, and get the
        // same bytes back without there being any such member in it.
        val archive = requireNotNull(PackageArchive.open(anyArchive("manifest.json" to "{}")))
        assertArrayEquals(png, archive.read(imageAddress))
        assertArrayEquals(wav, archive.read(soundAddress))
    }

    @Test
    fun `data that is not base64 degrades the button and says why`() {
        val accepted = importWith("data:image/png,not-base64", dataUri("audio/wav", wav)) as ImportResult.Accepted
        val button =
            accepted.boardPackage.boards
                .single()
                .buttons
                .single()
        assertEquals(ButtonState.DEGRADED, button.state)
        assertEquals(null, button.imagePath)
        assertEquals(listOf(WarningCode.IMAGE_UNDECODABLE), accepted.warnings.map { it.code })
    }

    @Test
    fun `an inline image is held to the same size cap as a baked one`() {
        val out = ByteArrayOutputStream()
        ImageIO.write(BufferedImage(2048, 8, BufferedImage.TYPE_INT_ARGB), "png", out)
        val accepted = importWith(dataUri("image/png", out.toByteArray()), dataUri("audio/wav", wav)) as ImportResult.Accepted
        assertEquals(listOf(WarningCode.IMAGE_OVERSIZED), accepted.warnings.map { it.code })
    }

    private fun anyArchive(vararg members: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            members.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
