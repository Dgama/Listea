package com.example.listea

import com.example.listea.data.ListItemEntity
import com.example.listea.domain.ExportChoice
import com.example.listea.domain.ExportKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where each payload of a round goes.
 *
 * v0.6.2 moved the destination off Settings and onto the thing that owns the work: a Todo holds the
 * address it posts to, a List may override it, and items no Todo owns fall back to the live default.
 * The planner is the one place that arithmetic happens, so it is pinned here without a database —
 * the same reason [ExportDispatchTest] exists beside it for the mechanism.
 *
 * The cases that matter are the ones a plausible shortcut gets wrong: giving a blank Todo the
 * default (it must fail visibly instead), merging two Todos because they share a kind (inheritance
 * keys by Todo, not address), and letting a List address apply to a text export that has no
 * destination at all.
 */
class WebhookAddressPlanTest {

    private fun item(taskId: String, id: String = taskId) = ListItemEntity(
        publicId = id,
        listId = 1L,
        taskId = taskId,
        title = "$taskId.jpg",
        sortOrder = 0,
        createdAt = 0L
    )

    private fun source(id: String, kind: ExportKind?, url: String = "") =
        ExportSource(id, id, kind, url)

    private fun plan(
        items: List<ListItemEntity>,
        choice: ExportChoice,
        sources: Map<String, ExportSource> = emptyMap(),
        listUrl: String = "",
        defaultUrl: String = ""
    ) = planExports(
        items = items,
        listChoice = choice,
        sourceOf = { sources[it.taskId] },
        listWebhookUrl = listUrl,
        defaultWebhookUrl = defaultUrl
    )

    // --- inheritance: one group per Todo, each to its own address --------------------------------

    @Test
    fun `an inheriting round posts each Todo's group to that Todo's address`() {
        val groups = plan(
            listOf(item("t1"), item("t2")),
            ExportChoice.Inherit,
            sources = mapOf(
                "t1" to source("s1", ExportKind.WEBHOOK, "https://one.example/hook"),
                "t2" to source("s2", ExportKind.WEBHOOK, "https://two.example/hook")
            ),
            defaultUrl = "https://default.example/hook"
        )

        assertEquals(
            listOf("https://one.example/hook", "https://two.example/hook"),
            groups.map { it.webhookUrl }
        )
    }

    @Test
    fun `a Todo with no address keeps the blank instead of borrowing the default`() {
        // The upgrade deliberately left old Todos blank. Quietly filling in the default here would
        // undo that decision at send time and post work the user never configured.
        val groups = plan(
            listOf(item("t1")),
            ExportChoice.Inherit,
            sources = mapOf("t1" to source("s1", ExportKind.WEBHOOK)),
            defaultUrl = "https://default.example/hook"
        )

        assertEquals("", groups.single().webhookUrl)
    }

    @Test
    fun `items no Todo owns fall back to the live default`() {
        val groups = plan(
            listOf(item("orphan")),
            ExportChoice.Inherit,
            defaultUrl = "https://default.example/hook"
        )

        assertEquals("https://default.example/hook", groups.single().webhookUrl)
        assertNull(groups.single().sourceId)
    }

    @Test
    fun `a List address overrides every webhook group of an inheriting round`() {
        val groups = plan(
            listOf(item("t1"), item("t2"), item("t3")),
            ExportChoice.Inherit,
            sources = mapOf(
                "t1" to source("s1", ExportKind.WEBHOOK, "https://one.example/hook"),
                "t2" to source("s2", ExportKind.TXT),
                "t3" to source("s3", ExportKind.WEBHOOK, "https://three.example/hook")
            ),
            listUrl = "https://list.example/hook",
            defaultUrl = "https://default.example/hook"
        )

        assertEquals(
            listOf("https://list.example/hook", "", "https://list.example/hook"),
            groups.map { it.webhookUrl }
        )
        // Still one payload per Todo: the address moves; the mechanism does not. The text Todo is
        // untouched and carries no address of its own.
        assertEquals(listOf(ExportKind.WEBHOOK, ExportKind.TXT, ExportKind.WEBHOOK), groups.map { it.kind })
    }

    @Test
    fun `an inheriting round still keys by Todo, so two Todos sharing an address stay two payloads`() {
        val shared = "https://same.example/hook"
        val groups = plan(
            listOf(item("t1"), item("t2")),
            ExportChoice.Inherit,
            sources = mapOf(
                "t1" to source("s1", ExportKind.WEBHOOK, shared),
                "t2" to source("s2", ExportKind.WEBHOOK, shared)
            )
        )

        assertEquals(2, groups.size)
        assertEquals(listOf("s1", "s2"), groups.map { it.sourceId })
        assertEquals(listOf(shared, shared), groups.map { it.webhookUrl })
    }

    // --- the List chose webhook: split by address ------------------------------------------------

    @Test
    fun `a List address makes a chosen webhook one payload to it`() {
        val groups = plan(
            listOf(item("t1"), item("t2")),
            ExportChoice.By(ExportKind.WEBHOOK),
            sources = mapOf(
                "t1" to source("s1", ExportKind.TXT, "https://ignored.example/hook"),
                "t2" to source("s2", ExportKind.WEBHOOK, "https://also.example/hook")
            ),
            listUrl = "https://list.example/hook"
        )

        assertEquals(1, groups.size)
        assertEquals("https://list.example/hook", groups.single().webhookUrl)
        assertNull(groups.single().sourceId)
    }

    @Test
    fun `a chosen webhook with no List address splits by the Todos' addresses`() {
        val groups = plan(
            listOf(item("t1"), item("t2"), item("t1", id = "t1b")),
            ExportChoice.By(ExportKind.WEBHOOK),
            sources = mapOf(
                "t1" to source("s1", ExportKind.WEBHOOK, "https://one.example/hook"),
                "t2" to source("s2", ExportKind.TXT, "https://two.example/hook")
            )
        )

        assertEquals(
            listOf("https://one.example/hook", "https://two.example/hook"),
            groups.map { it.webhookUrl }
        )
        assertEquals(listOf(listOf("t1", "t1b"), listOf("t2")), groups.map { g -> g.items.map { it.publicId } })
    }

    @Test
    fun `two Todos with the same address merge into one payload when the List chose webhook`() {
        // Unlike inheritance, a List that chose the mechanism has no Todo to attribute a group
        // to: the receiver is the thing being decided, so one receiver is one POST.
        val shared = "https://same.example/hook"
        val groups = plan(
            listOf(item("t1"), item("t2"), item("t3")),
            ExportChoice.By(ExportKind.WEBHOOK),
            sources = mapOf(
                "t1" to source("s1", ExportKind.WEBHOOK, shared),
                "t2" to source("s2", ExportKind.TXT, shared),
                "t3" to source("s3", ExportKind.WEBHOOK, "https://other.example/hook")
            )
        )

        assertEquals(2, groups.size)
        assertEquals(shared, groups[0].webhookUrl)
        assertEquals(listOf("t1", "t2"), groups[0].items.map { it.taskId })
        assertEquals("https://other.example/hook", groups[1].webhookUrl)
    }

    @Test
    fun `a blank address is a group of its own rather than a dropped item`() {
        // It will fail at delivery and say *no webhook address is set*. Dropping it would make the
        // round quietly smaller than the List the user just exported.
        val groups = plan(
            listOf(item("blank"), item("set")),
            ExportChoice.By(ExportKind.WEBHOOK),
            sources = mapOf(
                "blank" to source("s1", ExportKind.WEBHOOK),
                "set" to source("s2", ExportKind.WEBHOOK, "https://set.example/hook")
            ),
            defaultUrl = "https://default.example/hook"
        )

        assertEquals(listOf("", "https://set.example/hook"), groups.map { it.webhookUrl })
        assertEquals(listOf("blank"), groups[0].items.map { it.taskId })
    }

    @Test
    fun `unowned items of a chosen webhook go to the default and merge with a Todo already there`() {
        val default = "https://default.example/hook"
        val groups = plan(
            listOf(item("o1"), item("t1"), item("o2")),
            ExportChoice.By(ExportKind.WEBHOOK),
            sources = mapOf("t1" to source("s1", ExportKind.WEBHOOK, default)),
            defaultUrl = default
        )

        assertEquals(1, groups.size)
        assertEquals(default, groups.single().webhookUrl)
        assertEquals(listOf("o1", "t1", "o2"), groups.single().items.map { it.taskId })
    }

    // --- choices that have no address -----------------------------------------------------------

    @Test
    fun `a text choice ignores addresses and stays one group`() {
        val groups = plan(
            listOf(item("t1"), item("t2")),
            ExportChoice.By(ExportKind.TXT),
            sources = mapOf(
                "t1" to source("s1", ExportKind.WEBHOOK, "https://one.example/hook"),
                "t2" to source("s2", ExportKind.TXT, "https://two.example/hook")
            ),
            listUrl = "https://list.example/hook",
            defaultUrl = "https://default.example/hook"
        )

        assertEquals(1, groups.size)
        assertEquals("", groups.single().webhookUrl)
        assertEquals(ExportKind.TXT, groups.single().kind)
    }

    @Test
    fun `a List that chose nothing plans nothing`() {
        assertTrue(
            plan(
                listOf(item("t1")),
                ExportChoice.Nothing,
                sources = mapOf("t1" to source("s1", ExportKind.WEBHOOK, "https://one.example/hook"))
            ).isEmpty()
        )
    }

    // --- the words a missing address is refused in ------------------------------------------------

    @Test
    fun `a blank webhook address says it is missing rather than asking for typing`() {
        assertEquals(
            "No webhook address is set for this export",
            webhookAddressProblem(ExportKind.WEBHOOK, "")
        )
        assertEquals(
            "No webhook address is set for this export",
            webhookAddressProblem(ExportKind.WEBHOOK, "   ")
        )
    }

    @Test
    fun `a malformed address keeps the field's own error`() {
        assertEquals("Not a valid URL", webhookAddressProblem(ExportKind.WEBHOOK, "not a url"))
        assertEquals(
            "URL must start with http:// or https://",
            webhookAddressProblem(ExportKind.WEBHOOK, "ftp://example.com/hook")
        )
    }

    @Test
    fun `a usable webhook and any text export have no problem`() {
        assertNull(webhookAddressProblem(ExportKind.WEBHOOK, "https://example.com/hook"))
        assertNull(webhookAddressProblem(ExportKind.TXT, ""))
    }
}
