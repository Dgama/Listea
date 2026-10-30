package com.example.listea.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.listea.ImportPlan
import com.example.listea.ImporterKind
import com.example.listea.PartOrigin
import com.example.listea.PlannedPackage
import com.example.listea.PlannedPart
import com.example.listea.PlannedTask
import com.example.listea.PlannedTaskPrompt
import com.example.listea.encodeTemplateAttachments
import com.example.listea.domain.CONDITION_FIELD_CHOICE
import com.example.listea.domain.CompletionPolicy
import com.example.listea.domain.FieldAnswer
import com.example.listea.domain.PartCondition
import com.example.listea.domain.PartConditionMatch
import com.example.listea.domain.PlannedPromptResource
import com.example.listea.domain.ReviewResponse
import com.example.listea.domain.TemplateId
import com.example.listea.domain.TemplateVersionRef
import com.example.listea.domain.Templates
import com.example.listea.domain.ViewerEvent
import com.example.listea.domain.encode
import com.example.listea.domain.encodePartCondition
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a dissolved conditional Task keeps, and what a recovery is allowed to decide.
 *
 * The snapshot records the branch, the part's own judgment and the stale flag so a history is a
 * faithful account of the composition it departed. A recovery does **not** copy them back: the new
 * Package's composition is authoritative, so applicability is re-derived against the parts that now
 * exist, and an answer whose branch the new composition closes is set aside rather than restored
 * as current.
 *
 * This is the difference between a record and an instruction, and it is the one place a recovery
 * could quietly resurrect a branch its Package no longer declares.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecoveryApplicabilityTest {

    private lateinit var database: ListeaDatabase
    private lateinit var dao: ListsDao

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val mediaPart = Templates.MediaPart.ref

    @Before
    fun openDatabase() {
        database = inMemory()
        dao = database.listsDao()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun `the snapshot keeps the branch, the judgment and the stale flag`() = runTest {
        val first = importTodo("v1", plan(menu = gateIs("yes"), menuJudgment = mediaPart))
        val taskId = taskIdOf(first)

        answer(taskId, "gate", "yes")
        answer(taskId, "menu", "yes")
        answer(taskId, "gate", "no", now = 1700)
        dao.deleteTodo(first.id, keepState = true, now = 5000)

        val records = decodeDissolvedParts(rawText("SELECT partRecords FROM dissolved_task_state"))
        val menu = records?.single { it.partKey == "menu" }
        assertEquals(encodePartCondition(gateIs("yes")), menu?.condition)
        assertEquals(encodeTemplateAttachments(listOf(mediaPart)), menu?.partJudgment)
        // The gate closed the menu's branch, so its answer is on record but no longer current.
        assertTrue(menu?.stale == true)
        val gate = records?.single { it.partKey == "gate" }
        assertNull(gate?.condition)
        assertNull(gate?.partJudgment)
        assertEquals(false, gate?.stale)
    }

    @Test
    fun `a recovery restores the answer and re-derives its applicability from the new branch`() =
        runTest {
            val first = importTodo(
                "old",
                plan(menu = gateIs("yes"), menuJudgment = mediaPart, revision = 1)
            )
            val oldTask = taskIdOf(first)
            answer(oldTask, "gate", "yes")
            answer(oldTask, "menu", "yes")
            dao.deleteTodo(first.id, keepState = true, now = 5000)

            // The Package now asks the menu when the gate is "no". The recovered gate answer is
            // "yes", so the new branch closes the part the answer belongs to.
            val second = importTodo(
                "new",
                plan(menu = gateIs("no"), menuJudgment = Templates.MixedStructured.ref, revision = 2),
                recoverFrom = first.id
            )
            val newTask = taskIdOf(second)

            assertEquals(
                menuAnswer("yes"),
                rawText("SELECT response FROM task_part_state WHERE taskId = '$newTask' AND partKey = 'menu'")
            )
            assertEquals(
                1,
                rawInt("SELECT stale FROM task_part_state WHERE taskId = '$newTask' AND partKey = 'menu'")
            )
            // The branch is the new Package's, not the dissolved one, and neither is the judgment:
            // a recovery restores an answer, never the composition it was made under.
            assertEquals(
                encodePartCondition(gateIs("no")),
                rawText("SELECT condition FROM task_parts WHERE taskId = '$newTask' AND partKey = 'menu'")
            )
            assertEquals(
                encodeTemplateAttachments(listOf(Templates.MixedStructured.ref)),
                rawText("SELECT partJudgment FROM task_parts WHERE taskId = '$newTask' AND partKey = 'menu'")
            )
        }

    @Test
    fun `a recovered answer the new branch still asks for stays current`() = runTest {
        val first = importTodo("old", plan(menu = gateIs("yes"), revision = 1))
        val oldTask = taskIdOf(first)
        answer(oldTask, "gate", "yes")
        answer(oldTask, "menu", "yes")
        dao.deleteTodo(first.id, keepState = true, now = 5000)

        val second = importTodo(
            "new",
            plan(menu = gateIs("yes"), revision = 2),
            recoverFrom = first.id
        )
        val newTask = taskIdOf(second)

        assertEquals(
            0,
            rawInt("SELECT stale FROM task_part_state WHERE taskId = '$newTask' AND partKey = 'menu'")
        )
    }

    // --- fixtures ---------------------------------------------------------------------------------

    private fun inMemory(): ListeaDatabase =
        Room.inMemoryDatabaseBuilder(context, ListeaDatabase::class.java)
            .allowMainThreadQueries()
            .build()

    private fun plan(
        menu: PartCondition?,
        menuJudgment: TemplateVersionRef? = null,
        revision: Int = 1
    ): ImportPlan = ImportPlan(
        tasks = listOf(
            PlannedTask(
                groupPath = "$PACKAGE_KEY/~section/main",
                displayName = "Main",
                compositionKey = "section:main",
                taskReview = Templates.MixedStructured.ref,
                parts = listOf(
                    "gate" to null,
                    "menu" to menu
                ).mapIndexed { position, (key, condition) ->
                    PlannedPart(
                        partKey = key,
                        position = position,
                        origin = PartOrigin.PACKAGE,
                        prompt = PlannedTaskPrompt(
                            packageKey = PACKAGE_KEY,
                            promptKey = key,
                            kind = if (key == "menu") "media" else "form",
                            schema = 1,
                            payload = if (key == "menu") """{"stem":"menu"}""" else GATE_BODY,
                            revision = revision,
                            resources = emptyList<PlannedPromptResource>()
                        ),
                        required = true,
                        condition = condition,
                        judgment = if (key == "menu") menuJudgment else null
                    )
                }
            )
        ),
        notices = emptyList(),
        packages = listOf(
            PlannedPackage(
                rootRelativePath = "dating",
                packageKey = PACKAGE_KEY,
                protocol = "listea.package/3",
                title = "Conditional",
                revision = revision,
                fingerprint = "fp-$revision",
                resources = emptyList()
            )
        )
    )

    private suspend fun importTodo(
        folder: String,
        plan: ImportPlan,
        recoverFrom: String? = null
    ): SourceEntity {
        val source = dao.registerSource(
            rootUri = ROOT,
            relativePath = folder,
            displayName = folder,
            importerKind = ImporterKind.PACKAGE,
            now = if (recoverFrom == null) 1000 else 6000
        )
        dao.runImport(
            source.id,
            folder,
            plan,
            if (recoverFrom == null) 2000 else 6000,
            recoverFrom = recoverFrom
        )
        return source
    }

    private fun gateIs(value: String) = PartCondition(
        sourcePartKey = "gate",
        field = CONDITION_FIELD_CHOICE,
        match = PartConditionMatch.Equals,
        values = listOf(value)
    )

    /** The gate is a `form`, so its answer is a structured field — the one a condition reads. */
    private fun gateAnswer(value: String) = ReviewResponse.StructuredReview(
        completed = true,
        fields = listOf(FieldAnswer("choice", "choice.single", value))
    ).encode()

    private fun menuAnswer(value: String) = ReviewResponse.PrimaryReview(
        completed = true,
        events = listOf(ViewerEvent.Choice(optionId = value, label = value))
    ).encode()

    private suspend fun answer(taskId: String, partKey: String, value: String, now: Long = 1500) {
        dao.submitReview(
            taskId = taskId,
            partKey = partKey,
            response = if (partKey == "gate") {
                ReviewResponse.StructuredReview(
                    completed = true,
                    fields = listOf(FieldAnswer("choice", "choice.single", value))
                )
            } else {
                ReviewResponse.PrimaryReview(
                    completed = true,
                    events = listOf(ViewerEvent.Choice(optionId = value, label = value))
                )
            },
            template = TemplateVersionRef(TemplateId("fixture.review"), 1),
            context = null,
            completion = CompletionPolicy.AllRequiredParts,
            completesTask = true,
            explicitCompletion = false,
            now = now
        )
    }

    private fun taskIdOf(source: SourceEntity): String =
        rawText("SELECT taskId FROM task_imports WHERE sourceId = '${source.id}'")

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

    private companion object {
        const val ROOT = "content://root"
        const val PACKAGE_KEY = "conditional"
        const val GATE_BODY = """
            {
              "title": "Gate",
              "fields": [
                {
                  "key": "choice",
                  "type": "choice.single",
                  "label": "Show?",
                  "required": true,
                  "options": [
                    { "id": "yes", "label": "Yes" },
                    { "id": "no", "label": "No" }
                  ]
                }
              ]
            }
        """
    }
}
