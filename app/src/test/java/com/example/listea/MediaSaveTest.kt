package com.example.listea

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Save decides before it touches storage, and what it reports afterwards.
 *
 * The copy itself goes through MediaStore and is exercised on device. What these pin is the two
 * decisions made without one: whether a file belongs in a gallery album at all, and what the user
 * is told about a save that did not simply succeed — because a save reported as done that did not
 * happen is the failure mode that costs a file.
 */
class MediaSaveTest {

    @Test
    fun `the provider's type decides what the file is`() {
        assertEquals(SavedMediaKind.Image, mediaKindOf("image/jpeg", "holiday.jpg"))
        assertEquals(SavedMediaKind.Video, mediaKindOf("video/mp4", "clip.mp4"))
    }

    @Test
    fun `an animated gif is an image, which is what an album can hold`() {
        assertEquals(SavedMediaKind.Image, mediaKindOf("image/gif", "loop.gif"))
    }

    @Test
    fun `anything that is not media is refused rather than saved`() {
        assertNull(mediaKindOf("text/plain", "notes.txt"))
        assertNull(mediaKindOf("application/pdf", "manual.pdf"))
    }

    @Test
    fun `the album sits under DCIM so one album holds both photos and video`() {
        assertEquals("DCIM/$ListeaAlbumName", ListeaAlbumRelativePath)
    }

    @Test
    fun `the export sits under Download, the only kind of place a text file may go`() {
        assertEquals("Download/$ListeaAlbumName/txt", ListeaExportRelativePath)
    }

    /**
     * The tripwire the release shipped without, and the reason it shipped broken.
     *
     * From Android 11, `MediaStore.Files` — the only collection that takes a non-media row —
     * accepts one under `Download/` and `Documents/` and refuses every other primary directory.
     * A `txt` export written to `DCIM/Listea/txt/` therefore fails **on the device, on every
     * insert**, with a permission this app cannot be granted, because the directory is not the
     * problem and no permission is.
     *
     * Robolectric's `MediaStore` shadow does not enforce the rule, so `ExportRoutingTest` delivers
     * a text export quite happily at `sdk = [34]` while the phone refuses every one. That is the
     * whole bug: a green suite over a path that cannot work. The only thing that can hold this is
     * a test that reads the constant and knows the rule — which is what this is. It cannot prove
     * the platform will accept the write; it can refuse to let the path drift back to a directory
     * that provably will not.
     */
    @Test
    fun `the export's primary directory is one the platform allows a non-media file into`() {
        val allowed = setOf("Download", "Documents")
        val primary = ListeaExportRelativePath.substringBefore('/')

        assertTrue(
            "a text export under $primary/ is refused by MediaStore.Files on Android 11+; " +
                "only $allowed are accepted",
            primary in allowed
        )
    }

    /**
     * The two destinations must not be the same directory, because the rule that picks the album
     * is the rule that broke the export: `DCIM` is chosen for what a gallery will show, and a
     * gallery is built out of `MediaStore.Images`, which is a collection a text file has no way
     * into. Sharing one constant is how the export ended up under it.
     */
    @Test
    fun `the export is not written under the album the photographs go in`() {
        assertFalse(
            ListeaExportRelativePath.startsWith("$ListeaAlbumRelativePath/") ||
                ListeaExportRelativePath == ListeaAlbumRelativePath
        )
    }

    @Test
    fun `a second save of the same file is reported as already there, not as a failure`() {
        val message = saveOutcomeMessage(SaveToAlbumResult.AlreadySaved("holiday.jpg"))

        assertTrue(message, message.contains("holiday.jpg"))
        assertTrue(message, message.contains("already"))
    }

    @Test
    fun `a failure says what went wrong instead of claiming a save`() {
        val message = saveOutcomeMessage(SaveToAlbumResult.Failed("the file could not be read"))

        assertEquals("Could not save: the file could not be read", message)
    }

    @Test
    fun `a save that worked names the album the user should go looking in`() {
        val message = saveOutcomeMessage(SaveToAlbumResult.Saved("holiday.jpg"))

        assertTrue(message, message.contains(ListeaAlbumName))
    }
}
