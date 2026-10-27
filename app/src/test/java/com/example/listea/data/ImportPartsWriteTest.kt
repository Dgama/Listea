package com.example.listea.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.listea.ImportPlan
import com.example.listea.ImporterKind
import com.example.listea.PartOrigin
import com.example.listea.PlannedAsset
import com.example.listea.PlannedPackage
import com.example.listea.PlannedPart
import com.example.listea.PlannedTask
import com.example.listea.PlannedTaskPrompt
import com.example.listea.domain.CompletionPolicy
import com.example.listea.domain.PlannedPackageResource
import com.example.listea.domain.PlannedPromptResource
import com.example.listea.domain.ReviewResponse
import com.example.listea.domain.TemplateId
import com.example.listea.domain.TemplateVersionRef
import com.example.listea.domain.encode
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * One Package Task of several Prompts, written in one import run.
 *
 * Before v0.8.4 a Package Prompt was a Task: one payload row keyed by the Task, one Resource set,
 * one answer. The parts split makes every one of those facts per part, and this suite pins that the
 * ordinary Task loop and the Package pass together produce a single Task whose parts are complete
 * and independent — positions, payloads, Assets, Resource uses, state rows and composition links,
 * all naming the same Task and the same part keys.
 *
 * The plan is built by hand for the same reason [PackageImportTest] does: the write side has to be
 * answerable for any plan, not only for one this build's planner happens to produce.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportPartsWriteTest {

    private lateinit var database: ListeaDatabase
    private lateinit var dao: ListsDao

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val template = TemplateVersionRef(TemplateId("fixture.review"), 1)

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
    fun `a section Task writes one Task whose parts carry their own payloads, Assets and Resources`() =
        runTest {
            val source = registerSource()

            val result = dao.runImport(source.id, "dating", plan(), 2000)

            assertEquals(ImportOutcome.COMPLETE, result.outcome)
            assertEquals(1, result.tasksCreated)
            assertEquals(3, result.assetsCreated)
            assertEquals(1, result.packagesWritten)
            assertEquals(1, rawInt("SELECT COUNT(*) FROM tasks"))
            val taskId = rawText("SELECT taskId FROM task_imports")
            assertEquals("main", rawText("SELECT displayName FROM task_imports"))
            assertEquals("$PACKAGE_KEY/~section/main", rawText("SELECT groupPath FROM task_imports"))

            // The parts, in composition order, with their provenance and requiredness.
            assertEquals(
                listOf(
                    "q1|0|package|q1|1|1|",
                    "q2|1|package|q2|1|1|",
                    "q3|2|package|q3|0|1|"
                ),
                rows(
                    "SELECT partKey || '|' || position || '|' || origin || '|' || " +
                        "IFNULL(promptKey, '') || '|' || required || '|' || present || '|' || " +
                        "IFNULL(availability, '') FROM task_parts ORDER BY position"
                )
            )
            // Import creates the state row a part's first answer will overwrite; it does not
            // answer anything on the person's behalf.
            assertEquals(
                listOf("q1||null", "q2||null", "q3||null"),
                rows(
                    "SELECT partKey || '|' || response || '|' || IFNULL(answeredAt, 'null') " +
                        "FROM task_part_state ORDER BY partKey"
                )
            )

            // Each body belongs to exactly the part that names it.
            assertEquals(
                listOf(
                    "q1|{\"stem\":\"One\"}",
                    "q2|{\"stem\":\"Two\"}",
                    "q3|{\"stem\":\"Three\"}"
                ),
                rows("SELECT partKey || '|' || payload FROM task_part_payloads ORDER BY partKey")
            )

            // The Assets are one Task's, scoped to the part that presents them. Two files under
            // q2 is the case a Task-level Asset list could not express.
            assertEquals(
                listOf(
                    "q2|dating/photos/a.jpg|0",
                    "q2|dating/photos/b.jpg|1",
                    "q3|dating/photos/c.jpg|2"
                ),
                rows(
                    "SELECT partKey || '|' || relativePath || '|' || sortOrder " +
                        "FROM assets ORDER BY relativePath"
                )
            )
            assertEquals(1, rawInt("SELECT COUNT(DISTINCT taskId) FROM assets"))
            assertEquals(taskId, rawText("SELECT taskId FROM assets LIMIT 1"))

            // One photo, used by every Prompt, required differently by q3.
            assertEquals(
                listOf("q1|1", "q2|1", "q3|0"),
                rows("SELECT partKey || '|' || required FROM task_part_resources ORDER BY partKey")
            )

            // Composition and Prompt links name the Task the Task loop created.
            assertEquals(
                "section:main|1|$taskId",
                rawText(
                    "SELECT compositionKey || '|' || present || '|' || taskId " +
                        "FROM package_compositions"
                )
            )
            assertEquals(
                listOf(
                    "q1|1|section:main|$taskId",
                    "q2|1|section:main|$taskId",
                    "q3|1|section:main|$taskId"
                ),
                rows(
                    "SELECT promptKey || '|' || present || '|' || compositionKey || '|' || " +
                        "taskId FROM package_composition_prompts ORDER BY promptKey"
                )
            )
            assertEquals(
                "listea.package/2",
                rawText("SELECT protocol FROM package_checkpoints")
            )
        }

    @Test
    fun `a refresh leaves an answered part's state and its payload alone`() = runTest {
        val source = registerSource()
        dao.runImport(source.id, "dating", plan(), 2000)
        val taskId = rawText("SELECT taskId FROM task_imports")
        val answer = ReviewResponse.MediaReview(completed = true, selections = setOf("keep"))
        dao.submitReview(
            taskId = taskId,
            partKey = "q2",
            response = answer,
            template = template,
            context = null,
            completion = CompletionPolicy.SinglePart,
            completesTask = false,
            explicitCompletion = false,
            now = 2500
        )
        val payloadAt = rawInt("SELECT updatedAt FROM task_part_payloads WHERE partKey = 'q2'")

        dao.runImport(source.id, "dating", plan(), 3000)

        assertEquals(
            answer.encode(),
            rawText("SELECT response FROM task_part_state WHERE partKey = 'q2'")
        )
        assertEquals(2500, rawInt("SELECT answeredAt FROM task_part_state WHERE partKey = 'q2'"))
        // The same fingerprint is a checkpoint no-op: a refresh of unchanged content must not
        // re-stamp the body under the answer that was made against it.
        assertEquals(payloadAt, rawInt("SELECT updatedAt FROM task_part_payloads WHERE partKey = 'q2'"))
        assertEquals(1, rawInt("SELECT COUNT(*) FROM tasks"))
    }

    @Test
    fun `a part the plan drops is absent and the rest keep their rows`() = runTest {
        val source = registerSource()
        dao.runImport(source.id, "dating", plan(), 2000)
        val q3State = rawInt("SELECT COUNT(*) FROM task_part_state WHERE partKey = 'q3'")

        dao.runImport(
            source.id,
            "dating",
            plan(revision = 2, fingerprint = "fp-2", prompts = listOf("q1", "q2")),
            3000
        )

        assertEquals(1, rawInt("SELECT COUNT(*) FROM tasks"))
        // Rows are kept, not deleted: the answer a person made remains reviewable history.
        assertEquals(1, rawInt("SELECT COUNT(*) FROM task_parts WHERE partKey = 'q3'"))
        assertEquals(0, rawInt("SELECT present FROM task_parts WHERE partKey = 'q3'"))
        assertEquals(1, rawInt("SELECT present FROM task_parts WHERE partKey = 'q1'"))
        assertEquals(q3State, rawInt("SELECT COUNT(*) FROM task_part_state WHERE partKey = 'q3'"))
        // The body stays with the part even though the Package no longer names the Prompt.
        assertEquals(1, rawInt("SELECT COUNT(*) FROM task_part_payloads WHERE partKey = 'q3'"))
        // The Prompt link is marked absent, not deleted, exactly as the part is.
        assertEquals(
            0,
            rawInt("SELECT present FROM package_composition_prompts WHERE promptKey = 'q3'")
        )
    }

    // --- fixtures ---------------------------------------------------------------------------------

    private suspend fun registerSource(): SourceEntity = dao.registerSource(
        rootUri = ROOT,
        relativePath = "dating",
        displayName = "Dating",
        importerKind = ImporterKind.PACKAGE,
        now = 1000
    )

    private fun plan(
        revision: Int = 1,
        fingerprint: String = "fp-1",
        prompts: List<String> = listOf("q1", "q2", "q3")
    ): ImportPlan {
        val parts = mutableListOf<PlannedPart>()
        var sortOrder = 0
        for ((position, key) in listOf("q1", "q2", "q3").withIndex()) {
            if (key !in prompts) continue
            val assets = when (key) {
                "q2" -> listOf(asset("photos/a.jpg", sortOrder++), asset("photos/b.jpg", sortOrder++))
                "q3" -> listOf(asset("photos/c.jpg", sortOrder++))
                else -> emptyList()
            }
            val required = key != "q3"
            val resourceUse = listOf(PlannedPromptResource("photo", required = required))
            parts += PlannedPart(
                partKey = key,
                position = position,
                origin = PartOrigin.PACKAGE,
                prompt = prompt(key, resourceUse),
                assets = assets,
                required = required,
                resources = resourceUse
            )
        }
        return ImportPlan(
            tasks = listOf(
                PlannedTask(
                    groupPath = "$PACKAGE_KEY/~section/main",
                    displayName = "main",
                    parts = parts,
                    compositionKey = "section:main"
                )
            ),
            notices = emptyList(),
            packages = listOf(
                PlannedPackage(
                    rootRelativePath = "dating",
                    packageKey = PACKAGE_KEY,
                    protocol = "listea.package/2",
                    title = "Section demo",
                    revision = revision,
                    fingerprint = fingerprint,
                    resources = listOf(
                        PlannedPackageResource(
                            resourceKey = "photo",
                            kind = "image",
                            member = "media/photo.png",
                            present = true
                        )
                    )
                )
            )
        )
    }

    private fun prompt(key: String, resources: List<PlannedPromptResource>) = PlannedTaskPrompt(
        packageKey = PACKAGE_KEY,
        promptKey = key,
        kind = "profile",
        schema = 1,
        payload = """{"stem":"${stems.getValue(key)}"}""",
        revision = 1,
        resources = resources
    )

    private fun asset(name: String, sortOrder: Int) = PlannedAsset(
        rootRelativePath = "dating/$name",
        name = name.substringAfterLast('/'),
        uri = "content://fixture/$name",
        sizeBytes = 10L,
        lastModified = 20L,
        sortOrder = sortOrder
    )

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

    /** One `|`-joined string per row, in the order the query returns them. */
    private fun rows(sql: String): List<String> =
        database.openHelper.writableDatabase.query(sql).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add((0 until cursor.columnCount).joinToString("|") { cursor.getString(it) })
                }
            }
        }

    private companion object {
        const val ROOT = "content://root"
        const val PACKAGE_KEY = "section-demo"
        val stems = mapOf("q1" to "One", "q2" to "Two", "q3" to "Three")
    }
}
