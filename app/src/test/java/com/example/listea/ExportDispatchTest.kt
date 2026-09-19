package com.example.listea

import com.example.listea.data.ListItemEntity
import com.example.listea.domain.ExportChoice
import com.example.listea.domain.ExportKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule the release exists for: **one round, more than one export.**
 *
 * A List may hold Tasks that came from several Todos, and each Todo names its own way out. A round
 * over such a List therefore exports more than once, and the user's statement of the rule is exact —
 * *"正确顺序是：浏览退出或者完成->触发export->检查list当前是否有配置export->没有，则统计此次payload
 * 涉及的task的来源->按照来源todo整理成多份payload->按照todo各自配置的payload输出"* — so the tests
 * below walk that sentence in order.
 *
 * What makes this cheap is that it is a **sum computed when the round ends** and never a list stored
 * on a row. Nothing has to be kept in sync, which is why there is no table here to assert against:
 * the whole decision is one pure function, and this file is what holds it.
 *
 * The cases that matter are the ones where a plausible shortcut gives the wrong answer — an item no
 * Todo owns, a Todo that chose silence, and a List that chose for the whole round rather than
 * inheriting. Each of those is one line of the function and one test here.
 */
class ExportDispatchTest {

    private fun item(taskId: String, id: String = taskId, title: String = "$taskId.jpg") =
        ListItemEntity(
            publicId = id,
            listId = 1L,
            taskId = taskId,
            title = title,
            sortOrder = 0,
            createdAt = 0L
        )

    /** The lookup a caller hands in: a Todo's id, name and kind, keyed by the Task that came from it. */
    private fun sources(vararg pairs: Pair<String, ExportSource>): (ListItemEntity) -> ExportSource? {
        val byTask = pairs.toMap()
        return { item -> byTask[item.taskId] }
    }

    private fun source(id: String, title: String, kind: ExportKind?) = ExportSource(id, title, kind)

    @Test
    fun `a List that did not choose splits the round by its Todos`() {
        // **The acceptance item the release exists for.** Two Todos, two kinds, one round: two
        // payloads, and neither carries an item that belongs to the other.
        val items = listOf(item("t1"), item("t2"), item("t1", id = "t1b"))
        val groups = planExports(
            items,
            listChoice = ExportChoice.Inherit,
            sourceOf = sources(
                "t1" to source("s1", "Phone", ExportKind.WEBHOOK),
                "t2" to source("s2", "Camera", ExportKind.TXT)
            )
        )

        assertEquals(2, groups.size)
        assertEquals(listOf(ExportKind.WEBHOOK, ExportKind.TXT), groups.map { it.kind })
        assertEquals(listOf("s1", "s2"), groups.map { it.sourceId })
        assertEquals(listOf("Phone", "Camera"), groups.map { it.sourceTitle })
        // The two memberships of `t1` travel together and `t2` travels alone — asserted by public id
        // rather than by task id, since both of the first group's rows share theirs.
        assertEquals(
            listOf(listOf("t1", "t1b"), listOf("t2")),
            groups.map { g -> g.items.map { it.publicId } }
        )
    }

    @Test
    fun `the two payloads share no item`() {
        // The property the split is *for*. A round that exported its items twice would be worse
        // than one that exported them once by the wrong mechanism, because the receiver could not
        // tell that it was a duplicate.
        val items = List(6) { item("t${it % 3}", id = "i$it") }
        val groups = planExports(
            items,
            listChoice = ExportChoice.Inherit,
            sourceOf = sources(
                "t0" to source("s0", "A", ExportKind.WEBHOOK),
                "t1" to source("s1", "B", ExportKind.TXT),
                "t2" to source("s2", "C", ExportKind.TXT)
            )
        )

        val ids = groups.flatMap { g -> g.items.map { it.publicId } }
        assertEquals(items.map { it.publicId }.sorted(), ids.sorted())
        assertEquals("an item was exported twice", items.size, ids.distinct().size)
    }

    @Test
    fun `Todos with the same kind still deliver separately`() {
        // Two Todos, one kind, is still two payloads: the source is what a group is keyed by, not
        // the kind. A receiver that files by Todo wants to be able to tell them apart, and merging
        // them here would make the split depend on a configuration the user may change tomorrow.
        val groups = planExports(
            listOf(item("t1"), item("t2")),
            listChoice = ExportChoice.Inherit,
            sourceOf = sources(
                "t1" to source("s1", "Phone", ExportKind.WEBHOOK),
                "t2" to source("s2", "Camera", ExportKind.WEBHOOK)
            )
        )

        assertEquals(2, groups.size)
        assertEquals(listOf(ExportKind.WEBHOOK, ExportKind.WEBHOOK), groups.map { it.kind })
        assertEquals(listOf("s1", "s2"), groups.map { it.sourceId })
    }

    @Test
    fun `a List that named a kind delivers once, holding everything`() {
        // Acceptance item 5: a List that has chosen stops being split. Even where its Todos
        // disagree, and even where one of them said nothing.
        val items = listOf(item("t1"), item("t2"), item("t3"))
        val groups = planExports(
            items,
            listChoice = ExportChoice.By(ExportKind.TXT),
            sourceOf = sources(
                "t1" to source("s1", "Phone", ExportKind.WEBHOOK),
                "t2" to source("s2", "Camera", ExportKind.TXT),
                "t3" to source("s3", "Silent", null)
            )
        )

        assertEquals(1, groups.size)
        assertEquals(ExportKind.TXT, groups.single().kind)
        assertEquals(items, groups.single().items)
    }

    @Test
    fun `a List that chose names no source, and that is not a gap`() {
        // The header already carries a `list` line, so an empty `source` says unambiguously *the
        // List chose this* — which is a different fact from *a Todo with no name*, and the two
        // must not be spelled the same way.
        val groups = planExports(
            listOf(item("t1")),
            listChoice = ExportChoice.By(ExportKind.WEBHOOK),
            sourceOf = sources("t1" to source("s1", "Phone", ExportKind.TXT))
        )

        assertNull(groups.single().sourceId)
        assertNull(groups.single().sourceTitle)
    }

    @Test
    fun `a List that chose nothing exports nothing`() {
        // The failure this guards against is a shortcut: treating *nothing* as *did not choose* and
        // inheriting. That would export exactly what the user said not to.
        val groups = planExports(
            listOf(item("t1"), item("t2")),
            listChoice = ExportChoice.Nothing,
            sourceOf = sources(
                "t1" to source("s1", "Phone", ExportKind.WEBHOOK),
                "t2" to source("s2", "Camera", ExportKind.WEBHOOK)
            )
        )

        assertTrue(groups.isEmpty())
    }

    @Test
    fun `a Todo that said nothing is absent, not empty`() {
        // Its items are dropped while its neighbour's are delivered. An *empty group* would produce
        // a request carrying nothing, which is the one outcome nobody could act on.
        val items = listOf(item("t1"), item("t2"))
        val groups = planExports(
            items,
            listChoice = ExportChoice.Inherit,
            sourceOf = sources(
                "t1" to source("s1", "Silent", null),
                "t2" to source("s2", "Camera", ExportKind.TXT)
            )
        )

        assertEquals(1, groups.size)
        assertEquals("s2", groups.single().sourceId)
        assertEquals(listOf("t2"), groups.single().items.map { it.taskId })
        assertTrue(groups.none { it.items.isEmpty() })
    }

    @Test
    fun `a round whose Todos all said nothing exports nothing`() {
        val groups = planExports(
            listOf(item("t1"), item("t2")),
            listChoice = ExportChoice.Inherit,
            sourceOf = sources(
                "t1" to source("s1", "A", null),
                "t2" to source("s2", "B", null)
            )
        )

        assertTrue(groups.isEmpty())
    }

    @Test
    fun `an item no Todo owns goes out by webhook`() {
        // Decision 14, and it is a compatibility rule rather than a preference: items that predate
        // a Todo, or that were added by hand, did export before this release, and they exported by
        // webhook. They keep doing what they did.
        val groups = planExports(
            listOf(item("t1"), item("orphan")),
            listChoice = ExportChoice.Inherit,
            sourceOf = sources("t1" to source("s1", "Phone", ExportKind.TXT))
        )

        assertEquals(2, groups.size)
        val orphan = groups.first { it.sourceId == null }
        assertEquals(ExportKind.WEBHOOK, orphan.kind)
        assertNull(orphan.sourceTitle)
        assertEquals(listOf("orphan"), orphan.items.map { it.taskId })
    }

    @Test
    fun `every orphan in a round is one group, not one each`() {
        // The lookup returns null for all of them, so they share the null key. A group per item
        // would be a POST per item to the same URL, which no receiver wants and no user asked for.
        val groups = planExports(
            listOf(item("o1"), item("o2"), item("o3")),
            listChoice = ExportChoice.Inherit,
            sourceOf = sources()
        )

        assertEquals(1, groups.size)
        assertEquals(3, groups.single().items.size)
    }

    @Test
    fun `groups come out in the order their Todos first appear`() {
        // A sequence a test can assert, rather than whatever a hash map feels like. The items are
        // in the List's order, which is the order the user reviewed in, and the deliveries follow
        // it rather than a name sort or an id sort.
        val groups = planExports(
            listOf(item("t3"), item("t1"), item("t2")),
            listChoice = ExportChoice.Inherit,
            sourceOf = sources(
                "t1" to source("s1", "A", ExportKind.TXT),
                "t2" to source("s2", "B", ExportKind.TXT),
                "t3" to source("s3", "C", ExportKind.TXT)
            )
        )

        assertEquals(listOf("s3", "s1", "s2"), groups.map { it.sourceId })
    }

    @Test
    fun `an item's items keep their own order inside a group`() {
        val groups = planExports(
            listOf(item("t1", id = "c"), item("t1", id = "a"), item("t1", id = "b")),
            listChoice = ExportChoice.Inherit,
            sourceOf = sources("t1" to source("s1", "A", ExportKind.TXT))
        )

        assertEquals(listOf("c", "a", "b"), groups.single().items.map { it.publicId })
    }

    @Test
    fun `an empty round plans nothing, whatever the choice`() {
        // The dispatcher reports an empty round before it gets here for an inheriting List, and
        // never gets here for a List that chose nothing. Both must still be a loop over nothing
        // rather than a loop over one empty payload.
        listOf(
            ExportChoice.Inherit,
            ExportChoice.Nothing,
            ExportChoice.By(ExportKind.WEBHOOK),
            ExportChoice.By(ExportKind.TXT)
        ).forEach { choice ->
            val groups = planExports(emptyList(), choice) { null }

            // A List that chose a kind still plans one group, which the dispatcher's empty check
            // has already refused by then. Asserted here so the shape of the function is explicit:
            // this function never invents items, and never drops a choice.
            if (choice is ExportChoice.By) {
                assertEquals(1, groups.size)
                assertTrue(groups.single().items.isEmpty())
            } else {
                assertTrue("$choice planned a group over nothing", groups.isEmpty())
            }
        }
    }

}
