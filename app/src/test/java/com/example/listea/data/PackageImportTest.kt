package com.example.listea.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.listea.ImportPlan
import com.example.listea.ImporterKind
import com.example.listea.PlannedPackage
import com.example.listea.PlannedTask
import com.example.listea.PlannedTaskPrompt
import com.example.listea.catalogKey
import com.example.listea.domain.PlannedPackageResource
import com.example.listea.domain.PlannedPromptResource
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A Package inside the existing import transaction: the checkpoint, the payloads, the Prompt links,
 * the Resources, and the reconciliation rules that keep a Task's identity across revisions.
 *
 * The planner is pure and pinned separately; these tests exercise what [ListsDao.runImport] does
 * with a plan. They build the plan by hand rather than reading files, because the write side must
 * be answerable for a plan that came from anywhere — and because a rejected manifest never reaches
 * it, that case has no rows to assert.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PackageImportTest {

    private lateinit var database: ListeaDatabase
    private lateinit var dao: ListsDao

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun openDatabase() {
        database = Room.inMemoryDatabaseBuilder(context, ListeaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.listsDao()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun `a Package writes its checkpoint payload links and Resources in one run`() = runTest {
        val source = registerSource(ROOT, "dating", ImporterKind.PACKAGE)

        val result = dao.runImport(source.id, "dating", packagePlan(), 2000)

        assertEquals(ImportOutcome.COMPLETE, result.outcome)
        assertEquals(1, result.packagesWritten)
        assertEquals(0, result.packagesRejected)
        assertEquals(3, result.tasksCreated)
        assertEquals(3, rawInt("SELECT COUNT(*) FROM tasks"))
        assertEquals(3, rawInt("SELECT COUNT(*) FROM task_payloads"))
        assertEquals(3, rawInt("SELECT COUNT(*) FROM package_prompt_tasks WHERE present = 1"))
        assertEquals(1, rawInt("SELECT COUNT(*) FROM package_resources WHERE missing = 0"))
        assertEquals(3, rawInt("SELECT COUNT(*) FROM task_resources WHERE required = 1"))
        assertEquals(1, rawInt("SELECT COUNT(*) FROM package_checkpoints"))
        assertEquals("profile", rawText("SELECT kind FROM task_payloads LIMIT 1"))
        assertEquals(1, rawInt("SELECT schemaVersion FROM task_payloads LIMIT 1"))
        assertEquals("listea.package/1", rawText("SELECT protocol FROM package_checkpoints"))
        // Package-relative, as the protocol's storage note promises: the exact document URI is
        // stored beside it, and the checkpoint says where the Package lives inside the Upstream.
        assertEquals(
            "media/photo.png",
            rawText("SELECT relativePath FROM package_resources")
        )
        assertEquals(
            "dating",
            rawText("SELECT relativePath FROM package_checkpoints")
        )
    }

    @Test
    fun `the same revision and fingerprint is a no-op after the Task loop`() = runTest {
        val source = registerSource(ROOT, "dating", ImporterKind.PACKAGE)
        dao.runImport(source.id, "dating", packagePlan(), 2000)
        val taskIds = rawTexts("SELECT id FROM tasks ORDER BY id").toSet()
        val payloadAt = rawInt("SELECT updatedAt FROM task_payloads LIMIT 1")
        val seenAt = rawInt("SELECT lastSeenAt FROM package_checkpoints")

        val result = dao.runImport(source.id, "dating", packagePlan(), 3000)

        assertEquals(ImportOutcome.COMPLETE, result.outcome)
        assertEquals(0, result.packagesWritten)
        assertEquals(3, result.tasksReconciled)
        assertEquals(taskIds, rawTexts("SELECT id FROM tasks ORDER BY id").toSet())
        assertEquals(payloadAt, rawInt("SELECT updatedAt FROM task_payloads LIMIT 1"))
        assertEquals(seenAt, rawInt("SELECT lastSeenAt FROM package_checkpoints"))
        assertEquals(3, rawInt("SELECT COUNT(*) FROM tasks"))
    }

    @Test
    fun `a different fingerprint at the same revision refuses the Package`() = runTest {
        val source = registerSource(ROOT, "dating", ImporterKind.PACKAGE)
        dao.runImport(source.id, "dating", packagePlan(), 2000)
        val payloadAt = rawInt("SELECT updatedAt FROM task_payloads LIMIT 1")

        val result = dao.runImport(
            source.id,
            "dating",
            packagePlan(fingerprint = "fp-2", payloadSuffix = " changed"),
            3000
        )

        assertEquals(ImportOutcome.PARTIAL, result.outcome)
        assertEquals(1, result.packagesRejected)
        assertEquals(0, result.packagesWritten)
        assertEquals(payloadAt, rawInt("SELECT updatedAt FROM task_payloads LIMIT 1"))
        assertFalse(rawText("SELECT payload FROM task_payloads LIMIT 1").contains("changed"))
    }

    @Test
    fun `a higher revision updates the payload and keeps the Task id and decision`() = runTest {
        val source = registerSource(ROOT, "dating", ImporterKind.PACKAGE)
        dao.runImport(source.id, "dating", packagePlan(), 2000)
        val taskId = rawText("SELECT taskId FROM package_prompt_tasks WHERE promptKey = 'alice'")
        dao.setTaskCompleted(taskId, true, 2500)

        val result = dao.runImport(
            source.id,
            "dating",
            packagePlan(revision = 2, fingerprint = "fp-2", payloadSuffix = " changed"),
            3000
        )

        assertEquals(ImportOutcome.COMPLETE, result.outcome)
        assertEquals(1, result.packagesWritten)
        assertEquals(taskId, rawText("SELECT taskId FROM package_prompt_tasks WHERE promptKey = 'alice'"))
        assertEquals(2, rawInt("SELECT revision FROM task_payloads WHERE taskId = '$taskId'"))
        assertTrue(rawText("SELECT payload FROM task_payloads WHERE taskId = '$taskId'").contains("changed"))
        assertEquals(1, rawInt("SELECT isCompleted FROM task_state WHERE taskId = '$taskId'"))
        assertEquals(2, rawInt("SELECT revision FROM package_checkpoints"))
    }

    @Test
    fun `a lower revision is stale and writes nothing`() = runTest {
        val source = registerSource(ROOT, "dating", ImporterKind.PACKAGE)
        dao.runImport(source.id, "dating", packagePlan(revision = 3, fingerprint = "fp-3"), 2000)

        val result = dao.runImport(source.id, "dating", packagePlan(), 3000)

        assertEquals(ImportOutcome.PARTIAL, result.outcome)
        assertEquals(1, result.packagesRejected)
        assertEquals(3, rawInt("SELECT revision FROM package_checkpoints"))
        assertEquals(3, rawInt("SELECT revision FROM task_payloads LIMIT 1"))
    }

    @Test
    fun `a Prompt the revision no longer names is marked absent and keeps its Task`() = runTest {
        val source = registerSource(ROOT, "dating", ImporterKind.PACKAGE)
        dao.runImport(source.id, "dating", packagePlan(), 2000)
        val carol = rawText("SELECT taskId FROM package_prompt_tasks WHERE promptKey = 'carol'")
        dao.setTaskCompleted(carol, true, 2500)

        val result = dao.runImport(
            source.id,
            "dating",
            packagePlan(
                revision = 2,
                fingerprint = "fp-2",
                prompts = listOf("alice", "bob")
            ),
            3000
        )

        assertEquals(ImportOutcome.COMPLETE, result.outcome)
        assertEquals(0, rawInt("SELECT present FROM package_prompt_tasks WHERE promptKey = 'carol'"))
        assertEquals(1, rawInt("SELECT COUNT(*) FROM tasks WHERE id = '$carol'"))
        assertEquals(1, rawInt("SELECT COUNT(*) FROM task_payloads WHERE taskId = '$carol'"))
        assertEquals(1, rawInt("SELECT isCompleted FROM task_state WHERE taskId = '$carol'"))
    }

    @Test
    fun `two Todos over one Package keep independent Tasks`() = runTest {
        val first = registerSource(ROOT, "dating", ImporterKind.PACKAGE)
        val second = registerSource(ROOT, "dating", ImporterKind.PACKAGE)

        dao.runImport(first.id, "dating", packagePlan(), 2000)
        dao.runImport(second.id, "dating", packagePlan(), 3000)

        assertEquals(6, rawInt("SELECT COUNT(*) FROM tasks"))
        assertEquals(6, rawInt("SELECT COUNT(*) FROM task_payloads"))
        assertEquals(2, rawInt("SELECT COUNT(*) FROM package_checkpoints"))
        assertNotEquals(
            rawText("SELECT taskId FROM package_prompt_tasks WHERE sourceId = '${first.id}' LIMIT 1"),
            rawText("SELECT taskId FROM package_prompt_tasks WHERE sourceId = '${second.id}' LIMIT 1")
        )
    }

    @Test
    fun `deleting a Package Todo succeeds and takes its Package rows with it`() = runTest {
        val source = registerSource(ROOT, "dating", ImporterKind.PACKAGE)
        dao.runImport(source.id, "dating", packagePlan(), 2000)

        val report = dao.deleteTodo(source.id, keepState = false, now = 3000)

        // The failure this pins was a foreign key: checkpoint, Resource and Prompt-link rows name
        // the Todo with NO ACTION, so the Source row could not go and the UI could only say
        // "Nothing was changed". A successful report is the point; the counts say it is complete.
        assertNotNull(report)
        assertEquals(0, rawInt("SELECT COUNT(*) FROM sources"))
        assertEquals(0, rawInt("SELECT COUNT(*) FROM package_checkpoints"))
        assertEquals(0, rawInt("SELECT COUNT(*) FROM package_resources"))
        assertEquals(0, rawInt("SELECT COUNT(*) FROM package_prompt_tasks"))
        assertEquals(0, rawInt("SELECT COUNT(*) FROM task_payloads"))
        assertEquals(0, rawInt("SELECT COUNT(*) FROM tasks"))
    }

    @Test
    fun `a missing optional Resource is flagged and keeps per-Task requiredness`() = runTest {
        val source = registerSource(ROOT, "dating", ImporterKind.PACKAGE)

        dao.runImport(
            source.id,
            "dating",
            packagePlan(resourcePresent = false, resourceRequired = false),
            2000
        )

        assertEquals(1, rawInt("SELECT missing FROM package_resources"))
        assertEquals(0, rawInt("SELECT required FROM task_resources LIMIT 1"))
        // The Task and its payload still exist: a missing optional photo disables an affordance,
        // it does not make the Prompt unanswerable.
        assertEquals(3, rawInt("SELECT COUNT(*) FROM task_payloads"))
    }

    @Test
    fun `sourceHasChanges asks the checkpoint, not only the Task list`() = runTest {
        val source = registerSource(ROOT, "dating", ImporterKind.PACKAGE)
        val first = packagePlan()
        dao.runImport(source.id, "dating", first, 2000)

        assertFalse(dao.sourceHasChanges(source.id, "dating", first))
        assertTrue(
            dao.sourceHasChanges(
                source.id,
                "dating",
                packagePlan(revision = 2, fingerprint = "fp-2")
            )
        )
    }

    // --- fixtures ---------------------------------------------------------------------------------

    private suspend fun registerSource(
        rootUri: String,
        path: String,
        importer: ImporterKind
    ): SourceEntity = dao.registerSource(
        rootUri = rootUri,
        relativePath = path,
        displayName = "Dating",
        importerKind = importer,
        now = 1000
    )

    private fun packagePlan(
        revision: Int = 1,
        fingerprint: String = "fp-1",
        prompts: List<String> = listOf("alice", "bob", "carol"),
        payloadSuffix: String = "",
        resourcePresent: Boolean = true,
        resourceRequired: Boolean = true
    ): ImportPlan {
        val resource = PlannedPackageResource(
            resourceKey = "photo",
            kind = "image",
            member = "media/photo.png",
            present = resourcePresent
        )
        val tasks = prompts.map { key ->
            PlannedTask(
                groupPath = catalogKey(PACKAGE_KEY, key),
                displayName = key,
                assets = emptyList(),
                prompt = PlannedTaskPrompt(
                    packageKey = PACKAGE_KEY,
                    promptKey = key,
                    kind = "profile",
                    schema = 1,
                    payload = """{"bio":"$key$payloadSuffix"}""",
                    revision = revision,
                    resources = listOf(PlannedPromptResource("photo", required = resourceRequired))
                )
            )
        }
        return ImportPlan(
            tasks = tasks,
            notices = emptyList(),
            packages = listOf(
                PlannedPackage(
                    rootRelativePath = "dating",
                    packageKey = PACKAGE_KEY,
                    protocol = "listea.package/1",
                    title = "Dating demo",
                    revision = revision,
                    fingerprint = fingerprint,
                    resources = listOf(resource)
                )
            )
        )
    }

    // --- raw reads --------------------------------------------------------------------------------

    private fun rawInt(sql: String): Int =
        database.openHelper.writableDatabase.query(sql).use { cursor ->
            assertTrue("no row for: $sql", cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun rawText(sql: String): String =
        database.openHelper.writableDatabase.query(sql).use { cursor ->
            assertTrue("no row for: $sql", cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun rawTexts(sql: String): List<String> =
        database.openHelper.writableDatabase.query(sql).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }

    private companion object {
        const val ROOT = "content://root"
        const val PACKAGE_KEY = "dating-demo"
    }
}
