package com.example.listea

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.listea.domain.MarkKind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the text export writes: since v0.6.2, **exactly the body the webhook would post, byte for
 * byte**.
 *
 * The user asked for this so the two outputs of one round could be diffed — the file is the POST
 * minus its destination, and a destination is not part of a body. The equality asserted here is
 * therefore the whole contract: there is no second format to keep in step, and a change to the v2
 * payload shows up in both renderings at once. It is a deliberate, temporary decision (recorded in
 * `docs/exports.md`), not the beginning of a shared format.
 *
 * The file also keeps its naming tests. A file is the one export a receiver cannot negotiate with:
 * a POST that arrives malformed is a failed request someone sees in a log, while a file sits on an
 * SD card and is parsed months later by someone holding only the file — so the name has to be
 * readable, unique per second, and safe for a filesystem that refuses the characters a Todo's name
 * may contain.
 *
 * Robolectric because both halves of the equality need `org.json`; [TxtExporter] itself also takes
 * a `Context`, though `render` never uses it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TxtExportTest {

    private val exporter = TxtExporter(ApplicationProvider.getApplicationContext<Context>())

    private fun template(
        id: String = "tpl-1",
        name: String? = "Media Asset Control",
        version: Int = 4
    ) = ExportTemplateView(id, name, version)

    private fun asset(
        id: String = "a1b2",
        title: String = "IMG_0001.jpg",
        path: String? = "Camera/2024",
        sourceMissing: Boolean = false,
        actions: List<String> = listOf("favorite"),
        marks: List<ExportMarkView> = emptyList()
    ) = ExportAssetView(id, title, path, sourceMissing, actions, marks)

    private fun task(
        id: String = "task-1",
        title: String = "IMG_0001.jpg",
        completed: Boolean = true,
        templates: List<ExportTemplateView> = listOf(template()),
        assets: List<ExportAssetView> = listOf(asset())
    ) = ExportTaskView(id, title, completed, templates, assets)

    private fun payload(
        event: String = "quickreview.completed",
        listTitle: String = "Holiday",
        sourceId: String? = null,
        sourceTitle: String? = null,
        at: Long = 1_700_000_000_000,
        tasks: List<ExportTaskView> = listOf(task())
    ) = ExportPayload(
        event = event,
        listId = 7,
        listTitle = listTitle,
        completedAt = null,
        sourceId = sourceId,
        sourceTitle = sourceTitle,
        at = at,
        tasks = tasks
    )

    @Test
    fun `the file is exactly the bytes the webhook would post`() {
        val round = payload(sourceId = "s1", sourceTitle = "Holiday photos")

        assertEquals(buildWebhookPayload(round), exporter.render(round))
    }

    @Test
    fun `the two renderings agree on every shape a round can take`() {
        // The equality is only worth something if it holds where a second implementation would
        // have drifted: no tasks, manual tasks with no templates or assets, a pathless asset, a
        // missing source file, empty actions, and names holding JSON's own punctuation.
        val rounds = listOf(
            payload(),
            payload(tasks = emptyList()),
            payload(tasks = listOf(task(templates = emptyList(), assets = emptyList()))),
            payload(tasks = listOf(task(assets = listOf(asset(path = null))))),
            payload(tasks = listOf(task(assets = listOf(asset(sourceMissing = true))))),
            payload(tasks = listOf(task(assets = listOf(asset(actions = emptyList()))))),
            payload(listTitle = "Holiday\t2024\n%"),
            payload(tasks = listOf(task(title = "my%20file\tphoto\n\"quoted\".jpg"))),
            payload(
                tasks = listOf(
                    task(id = "a1", completed = false),
                    task(id = "b2", templates = listOf(template(id = "t1"), template(id = "t2", name = null))),
                    task(id = "c3", assets = listOf(asset(id = "c"), asset(id = "a"), asset(id = "b")))
                )
            )
        )

        rounds.forEach { round ->
            assertEquals(buildWebhookPayload(round), exporter.render(round))
        }
    }

    @Test
    fun `the file carries the version and the hierarchy, and no address`() {
        // A reader of the file is a reader of the POST, so the shape is the contract's shape. The
        // address is not in it because a payload never carries one — *where* an export went is
        // history's fact, not the body's.
        val body = JSONObject(exporter.render(payload()))

        assertEquals(EXPORT_VERSION, body.getInt("version"))
        assertEquals(listOf("version", "event", "list", "tasks"), body.keys().asSequence().toList())
        assertFalse(body.toString().contains("http"))
    }

    @Test
    fun `a marked round is version 3 in the file too, and still the exact POST`() {
        // The file is the POST's bytes, so a passage reaches a file export the same way it reaches a
        // receiver — same array, same order, same version claim. This is the one round where the two
        // renderings would be easiest to let drift, because a file export has no receiver to notice.
        val round = payload(
            tasks = listOf(
                task(
                    assets = listOf(
                        asset(
                            id = "pdf-1",
                            title = "paper.pdf",
                            path = "Papers",
                            actions = emptyList(),
                            marks = listOf(
                                ExportMarkView("m-1", MarkKind.COMMENT, "comment-1", 2, "the passage", "I think so"),
                                ExportMarkView("m-2", MarkKind.TAG, "tag-1", 4, "another passage", "funny_sentence")
                            )
                        )
                    )
                )
            )
        )

        val body = JSONObject(exporter.render(round))

        assertEquals(buildWebhookPayload(round), exporter.render(round))
        assertEquals(EXPORT_VERSION_MARKS, body.getInt("version"))
        val marks = body.getJSONArray("tasks").getJSONObject(0)
            .getJSONArray("assets").getJSONObject(0).getJSONArray("marks")
        assertEquals(listOf("m-1", "m-2"), (0 until marks.length()).map { marks.getJSONObject(it).getString("id") })
    }

    @Test
    fun `an unchecked task says so in the file`() {
        val body = JSONObject(
            exporter.render(
                payload(tasks = listOf(task(id = "on", completed = true), task(id = "off", completed = false)))
            )
        )
        val tasks = body.getJSONArray("tasks")

        assertEquals(true, tasks.getJSONObject(0).getBoolean("isCompleted"))
        assertEquals(false, tasks.getJSONObject(1).getBoolean("isCompleted"))
    }

    @Test
    fun `a title holding JSON punctuation survives as itself`() {
        // A filename is the user's: tabs, quotes, backslashes and newlines all reach the body, and
        // org.json — not a hand-written escaper — is what makes the file parse back to the name.
        val nasty = "my%20file\tphoto\n\"quoted\" \\ .jpg"
        val body = JSONObject(exporter.render(payload(tasks = listOf(task(title = nasty)))))

        assertEquals(nasty, body.getJSONArray("tasks").getJSONObject(0).getString("title"))
    }

    @Test
    fun `tasks and assets keep the order the payload gave them`() {
        // The payload's order is the List's order, which is the order the user reviewed in. A
        // renderer that sorted or grouped would be reordering a round behind the user's back.
        val body = JSONObject(
            exporter.render(
                payload(
                    tasks = listOf(
                        task(id = "c", assets = listOf(asset(id = "z"), asset(id = "x"), asset(id = "y"))),
                        task(id = "a"),
                        task(id = "b")
                    )
                )
            )
        )
        val tasks = body.getJSONArray("tasks")

        assertEquals(listOf("c", "a", "b"), (0 until tasks.length()).map { tasks.getJSONObject(it).getString("id") })
        val assets = tasks.getJSONObject(0).getJSONArray("assets")
        assertEquals(listOf("z", "x", "y"), (0 until assets.length()).map { assets.getJSONObject(it).getString("id") })
    }

    @Test
    fun `the file name is the moment, the event and the origin`() {
        // Its three segments, rather than one golden string: the first is the *host's* local time,
        // and a golden would be a test that passes in one timezone and fails in the next. What the
        // name has to be is a readable stamp, then the event, then the origin — and the space in
        // `Holiday photos` replaced, because a name a filesystem might argue about is an export that
        // silently does not happen.
        val stem = exportNameStem(payload(sourceId = "s1", sourceTitle = "Holiday photos"))

        assertTrue("not a yyyyMMdd-HHmmss stamp: $stem", Regex("""^\d{8}-\d{6}-""").containsMatchIn(stem))
        assertTrue(stem, stem.endsWith("-quickreview.completed-Holiday-photos"))
    }

    @Test
    fun `the file name falls back to the List when no Todo chose`() {
        // `originLabel`: a name has room for one of the two, and *which round is this* is what it
        // answers. The body inside carries whichever name was left out.
        val stem = exportNameStem(payload())

        assertTrue("not a yyyyMMdd-HHmmss stamp: $stem", Regex("""^\d{8}-\d{6}-""").containsMatchIn(stem))
        assertTrue(stem, stem.endsWith("-quickreview.completed-Holiday"))
    }

    @Test
    fun `a file name carries nothing a filesystem would argue about`() {
        // A Todo's display name is the user's, and an SD card will refuse a slash, a colon and a
        // brace. A name that cannot be written is an export that silently does not happen, so the
        // replacement is deliberately lossy — the body inside keeps the name in full.
        val stem = exportNameStem(payload(sourceTitle = "C:/Photos \\ 2024: best*"))
        val segment = stem.substringAfterLast('-')

        assertTrue("kept a character a filesystem refuses: $segment", segment.all { it.isLetterOrDigit() || it in "-_." })
        assertFalse(segment.contains('/'))
        assertFalse(segment.contains(':'))
    }

    @Test
    fun `a name with nothing usable left is still a name`() {
        // The replacement can empty a segment entirely. A file called `-.txt` is not better than
        // one called `-unnamed.txt`.
        assertTrue(exportNameStem(payload(sourceTitle = "///")).endsWith("-unnamed"))
        // And an empty Todo name is an empty name, not a fallback: `originLabel` reads
        // `sourceTitle ?: listTitle`, so a Todo that *has* a name — even a blank one — is what the
        // name says. Falling through to the List here would label a Todo's round with a List's name.
        assertTrue(exportNameStem(payload(sourceTitle = "")).endsWith("-unnamed"))
    }

    @Test
    fun `two rounds a second apart get different names`() {
        // Second precision, local time. A round takes longer than a second, so equality here would
        // mean a clock that did not move — and one file overwriting another.
        val first = exportNameStem(payload(at = 1_700_000_000_000))
        val second = exportNameStem(payload(at = 1_700_000_001_000))

        assertFalse(first == second)
    }
}
