package com.example.listea

import com.example.listea.data.escapeField
import com.example.listea.data.unescapeField
import com.example.listea.domain.ExportKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The text an export writes, pinned field for field.
 *
 * A file is the one export a receiver cannot negotiate with: a POST that arrives malformed is a
 * failed request someone can see in a log, while a file that is malformed sits on an SD card and
 * gets parsed by something that is not this app, months later, by a person who has only the file.
 * So the shape is a golden expectation in the same sense the webhook body's is, and the same
 * argument applies — a change to it should be something somebody wrote down.
 *
 * v0.6.0 linearises the same hierarchy the JSON body carries: a `task` record, then its `template`
 * records and its `asset` records, and each asset its `action` records. Each record kind has a fixed
 * field count, which is the property a reader splitting on tabs relies on.
 *
 * The other half is the escaping. A title here is a user's **filename**, and filenames contain tabs,
 * newlines and percent signs. A renderer that lets one through produces a file that reads as two
 * records, so the round trip through [unescapeField] is asserted rather than assumed.
 *
 * Pure: no context, no device, no clock. That is the whole point of splitting `render` off
 * `deliver` — a format is a thing the suite can hold rather than a thing the user finds out about.
 */
class TxtExportTest {

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
        actions: List<String> = listOf("favorite")
    ) = ExportAssetView(id, title, path, sourceMissing, actions)

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
    fun `the file starts with a version line`() {
        // First line, so a reader knows what wrote it before it knows anything else. The version is
        // the number this build stamps, not a copy of it — a second literal here would be a second
        // thing to remember when the format gains a field.
        val first = renderExportText(payload()).lineSequence().first()

        assertEquals("listea-export\t$EXPORT_VERSION", first)
    }

    @Test
    fun `the header names the round, and one task block follows per task`() {
        val text = renderExportText(
            payload(tasks = listOf(task(id = "a1"), task(id = "b2")))
        )

        assertEquals(
            listOf(
                "listea-export\t2",
                "event\tquickreview.completed",
                "list\tHoliday",
                "source\t",
                "at\t1700000000000",
                "tasks\t2",
                "task\ta1\tIMG_0001.jpg\t1\t1\t1",
                "template\ttpl-1\t1\tMedia Asset Control\t4",
                "asset\ta1b2\tIMG_0001.jpg\t1\tCamera/2024\t0\t1",
                "action\tfavorite",
                "task\tb2\tIMG_0001.jpg\t1\t1\t1",
                "template\ttpl-1\t1\tMedia Asset Control\t4",
                "asset\ta1b2\tIMG_0001.jpg\t1\tCamera/2024\t0\t1",
                "action\tfavorite"
            ),
            text.trimEnd('\n').split('\n')
        )
    }

    @Test
    fun `records follow their parent, and the counts say how many`() {
        // The two are derived from the same lists and a reader may trust either, so a disagreement
        // would be a file that contradicts itself.
        listOf(0, 1, 3).forEach { count ->
            val text = renderExportText(
                payload(
                    tasks = List(count) {
                        task(
                            id = "id$it",
                            templates = List(it) { index -> template(id = "t$index") },
                            assets = List(it) { index -> asset(id = "a$index") }
                        )
                    }
                )
            )
            val declared = text.lineSequence().first { it.startsWith("tasks\t") }.removePrefix("tasks\t")
            val written = text.lineSequence().count { it.startsWith("task\t") }

            assertEquals("$count tasks", count.toString(), declared)
            assertEquals(count, written)
        }

        val text = renderExportText(
            payload(
                tasks = listOf(
                    task(templates = listOf(template(id = "t1"), template(id = "t2")), assets = listOf(asset(id = "a1"), asset(id = "a2")))
                )
            )
        )
        assertTrue(text.contains("task\ttask-1\tIMG_0001.jpg\t1\t2\t2"))
        assertEquals(2, text.lineSequence().count { it.startsWith("template\t") })
        assertEquals(2, text.lineSequence().count { it.startsWith("asset\t") })
    }

    @Test
    fun `the origin is the Todo's name, or nothing when the List chose`() {
        assertEquals(
            "source\tHoliday photos",
            renderExportText(payload(sourceId = "s1", sourceTitle = "Holiday photos"))
                .lineSequence().first { it.startsWith("source\t") }
        )
        // Empty rather than absent, and empty rather than the List's name: the `list` line already
        // carries that, and *no Todo chose this* is a different fact from *a Todo named the List*.
        assertEquals(
            "source\t",
            renderExportText(payload(sourceTitle = null)).lineSequence().first { it.startsWith("source\t") }
        )
    }

    @Test
    fun `an unchecked task says so, and so does a checked one`() {
        val text = renderExportText(
            payload(tasks = listOf(task(id = "on", completed = true), task(id = "off", completed = false)))
        )

        assertTrue(text.contains("task\ton\tIMG_0001.jpg\t1\t"))
        assertTrue(text.contains("task\toff\tIMG_0001.jpg\t0\t"))
    }

    @Test
    fun `a manual task has no templates and no assets, not made-up ones`() {
        // A manual item has no source file and no import. Empty counts and no child records are the
        // honest spelling: a placeholder path would be worse than none, because a downstream tool
        // would try to open it.
        val text = renderExportText(payload(tasks = listOf(task(templates = emptyList(), assets = emptyList()))))

        assertTrue(text.contains("task\ttask-1\tIMG_0001.jpg\t1\t0\t0"))
        assertEquals(0, text.lineSequence().count { it.startsWith("asset\t") })
        assertEquals(0, text.lineSequence().count { it.startsWith("template\t") })
    }

    @Test
    fun `a pathless asset says the path is absent and leaves the field empty`() {
        val text = renderExportText(payload(tasks = listOf(task(assets = listOf(asset(path = null))))))

        assertTrue(text.contains("asset\ta1b2\tIMG_0001.jpg\t0\t\t0\t1"))
    }

    @Test
    fun `a missing source file is a flag, not a missing line`() {
        val text = renderExportText(payload(tasks = listOf(task(assets = listOf(asset(sourceMissing = true))))))

        assertTrue(text.contains("asset\ta1b2\tIMG_0001.jpg\t1\tCamera/2024\t1\t1"))
    }

    @Test
    fun `an asset with no actions has a zero count and no action records`() {
        // Every line has the same field count, so a reader can split on tabs without counting. The
        // actions themselves are records, not a joined field, so a value with a comma stays one value.
        val text = renderExportText(payload(tasks = listOf(task(assets = listOf(asset(actions = emptyList()))))))

        val line = text.lineSequence().first { it.startsWith("asset\t") }
        assertEquals(7, line.split('\t').size)
        assertTrue(line.endsWith("\t0"))
        assertEquals(0, text.lineSequence().count { it.startsWith("action\t") })
    }

    @Test
    fun `each record kind has one fixed field count`() {
        // The property the split-on-tabs reader relies on. One record that gained a column would
        // silently shift every field of that kind, so the count is one number per record kind.
        val text = renderExportText(
            payload(
                tasks = listOf(
                    task(),
                    task(
                        id = "b",
                        templates = emptyList(),
                        assets = listOf(
                            asset(id = "x", path = null, actions = emptyList()),
                            asset(id = "y", title = "a\tb", actions = listOf("favorite", "cust1"))
                        )
                    ),
                    task(id = "c", title = "nasty\tname\n%")
                )
            )
        )

        val byKind = text.lineSequence()
            .filter { '\t' in it }
            .groupBy { it.substringBefore('\t') }
            .mapValues { (_, lines) -> lines.map { it.split('\t').size }.distinct() }

        assertEquals(6, byKind.getValue("task").single())
        assertEquals(5, byKind.getValue("template").single())
        assertEquals(7, byKind.getValue("asset").single())
        assertEquals(2, byKind.getValue("action").single())
    }

    @Test
    fun `the header lines each carry exactly one field`() {
        // The other half of the same property, and the reason it is asserted separately: a reader
        // finds a header by its first field and takes the rest as the value, so a header line with
        // a stray tab would silently truncate what it holds.
        val text = renderExportText(payload(listTitle = "a\tb"))

        text.lineSequence().takeWhile { it.startsWith("tasks\t").not() }.forEach { line ->
            assertEquals(line, 2, line.split('\t').size)
        }
    }

    @Test
    fun `a title holding a tab, a newline or a percent survives as itself`() {
        // The case that makes the escaper load-bearing: a filename is the user's, and this one holds
        // the file's own separators. Left unescaped, this record would read as three.
        val nasty = "my%20file\tphoto\n.jpg"
        val text = renderExportText(payload(tasks = listOf(task(title = nasty))))

        val written = text.lineSequence().first { it.startsWith("task\t") }.split('\t')[2]
        assertFalse("the tab was not escaped", written.contains('\t'))
        assertEquals(nasty, unescapeField(written))
    }

    @Test
    fun `an action holding a comma is still one action record`() {
        val text = renderExportText(payload(tasks = listOf(task(assets = listOf(asset(actions = listOf("a,b")))))))

        assertEquals(listOf("action\ta,b"), text.lineSequence().filter { it.startsWith("action\t") }.toList())
    }

    @Test
    fun `a List title holding a separator survives as itself`() {
        val nasty = "Holiday\t2024\n%"
        val text = renderExportText(payload(listTitle = nasty))

        val written = text.lineSequence().first { it.startsWith("list\t") }.removePrefix("list\t")
        assertEquals(nasty, unescapeField(written))
    }

    @Test
    fun `escaping is the same rule the dissolved history uses`() {
        // Decision 16's proof at the unit level: one escaper, so a title that survives here survives
        // there too. The two are the same function, and this is what says so out loud.
        listOf("plain.jpg", "a\tb", "a\nb", "100%", "%0A", "mixed%\t\n").forEach { value ->
            assertEquals(value, unescapeField(escapeField(value)))
        }
    }

    @Test
    fun `tasks keep the order the payload gave them`() {
        // The payload's order is the List's order, which is the order the user reviewed in. A
        // renderer that sorted or grouped would be reordering a round behind the user's back.
        val text = renderExportText(
            payload(tasks = listOf(task(id = "c"), task(id = "a"), task(id = "b")))
        )

        assertEquals(
            listOf("c", "a", "b"),
            text.lineSequence().filter { it.startsWith("task\t") }.map { it.split('\t')[1] }.toList()
        )
    }

    @Test
    fun `assets keep the order the payload gave them`() {
        val text = renderExportText(
            payload(tasks = listOf(task(assets = listOf(asset(id = "c"), asset(id = "a"), asset(id = "b")))))
        )

        assertEquals(
            listOf("c", "a", "b"),
            text.lineSequence().filter { it.startsWith("asset\t") }.map { it.split('\t')[1] }.toList()
        )
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
        // answers. The header inside carries whichever name was left out.
        val stem = exportNameStem(payload())

        assertTrue("not a yyyyMMdd-HHmmss stamp: $stem", Regex("""^\d{8}-\d{6}-""").containsMatchIn(stem))
        assertTrue(stem, stem.endsWith("-quickreview.completed-Holiday"))
    }

    @Test
    fun `a file name carries nothing a filesystem would argue about`() {
        // A Todo's display name is the user's, and an SD card will refuse a slash, a colon and a
        // brace. A name that cannot be written is an export that silently does not happen, so the
        // replacement is deliberately lossy — the header inside keeps the name in full.
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

    @Test
    fun `the webhook kind asks before it acts, and reports SENT`() {
        // `asksBeforeDelivering` is what makes the dispatcher's confirm branch total, and
        // `deliveredOutcome` is what a webhook's history row is written with. The txt kind's two
        // answers are the other half of the same pair and need a `Context` to construct, so they
        // are asserted in `ExportRoutingTest` rather than here.
        val exporter = WebhookExporter("https://example.invalid")

        assertEquals(ExportKind.WEBHOOK, exporter.kind)
        assertTrue(exporter.asksBeforeDelivering)
        assertEquals(NoticeOutcome.SENT, exporter.deliveredOutcome)
    }
}
