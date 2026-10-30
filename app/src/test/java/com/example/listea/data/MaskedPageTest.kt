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
import com.example.listea.domain.CONDITION_FIELD_CHOICE
import com.example.listea.domain.CompletionPolicy
import com.example.listea.domain.PartApplicability
import com.example.listea.domain.PartCondition
import com.example.listea.domain.PartConditionMatch
import com.example.listea.domain.PlannedPromptResource
import com.example.listea.domain.ReviewResponse
import com.example.listea.domain.TemplateId
import com.example.listea.domain.TemplateVersionRef
import com.example.listea.domain.ViewerEvent
import kotlinx.coroutines.flow.first
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
 * What the read model says about a conditional part, from the rows it was written with.
 *
 * Applicability is never a column: [ListsDao.resolveParts] derives it from the stored conditions
 * and the stored answers every time somebody looks. These tests pin the derivation through the
 * catalog read — the surface a List review and the Catalog both draw parts from — and pin the two
 * fail-closed rules around it: an unreadable condition or judgment costs the part its availability
 * rather than becoming unconditional or inherited.
 *
 * A masked part is still present, so the Task's part count never moves with the branch. The count
 * of *answered* parts does: a stale answer is on record but is not work already done.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MaskedPageTest {

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
    fun `a branch whose gate is unanswered is pending, and its page stays`() = runTest {
        val source = registerSource()
        dao.runImport(source.id, "dating", plan(gate = null, menu = gateIsYes(), receipt = null), 1000)

        val parts = partsOf(source)

        assertEquals(listOf("gate", "menu", "receipt"), parts.map { it.partKey })
        assertEquals(
            listOf(
                PartApplicability.Active,
                PartApplicability.Pending,
                PartApplicability.Active
            ),
            parts.map { it.applicability }
        )
        // A masked page is still a page: nothing about the branch removes it from the Task.
        assertEquals(3, parts.count { it.present })
    }

    @Test
    fun `the gate's answer opens and closes the branch`() = runTest {
        val source = registerSource()
        dao.runImport(source.id, "dating", plan(gate = null, menu = gateIsYes(), receipt = null), 1000)
        val taskId = rawText("SELECT taskId FROM task_imports")

        answer(taskId, "gate", "yes")
        assertEquals(PartApplicability.Active, applicabilityOf(source, "menu"))

        answer(taskId, "gate", "no", now = 1600)
        assertEquals(PartApplicability.Inactive, applicabilityOf(source, "menu"))

        // The gate itself is unconditional, so its own page is answerable throughout.
        assertEquals(PartApplicability.Active, applicabilityOf(source, "gate"))
        assertEquals(3, partsOf(source).size)
    }

    @Test
    fun `a condition this build cannot read makes the part unavailable`() = runTest {
        val source = registerSource()
        dao.runImport(source.id, "dating", plan(gate = null, menu = gateIsYes(), receipt = null), 1000)

        exec("UPDATE task_parts SET condition = '{\"prompt\":' WHERE partKey = 'menu'")

        val menu = partsOf(source).single { it.partKey == "menu" }
        // Fail closed: an unreadable condition is not permission to treat the part as
        // unconditional. It stays a readable page, but not an answerable one.
        assertNull(menu.condition)
        assertEquals("condition-invalid", menu.availability)
    }

    @Test
    fun `a judgment this build cannot read makes the part unavailable`() = runTest {
        val source = registerSource()
        dao.runImport(source.id, "dating", plan(gate = null, menu = gateIsYes(), receipt = null), 1000)

        exec("UPDATE task_parts SET partJudgment = 'nonsense' WHERE partKey = 'menu'")

        val menu = partsOf(source).single { it.partKey == "menu" }
        assertNull(menu.judgment)
        assertEquals("unsupported-judgment", menu.availability)
    }

    @Test
    fun `an earlier availability code is kept when the branch also fails`() = runTest {
        val source = registerSource()
        dao.runImport(source.id, "dating", plan(gate = null, menu = gateIsYes(), receipt = null), 1000)

        exec(
            "UPDATE task_parts SET availability = 'condition-source-detached', " +
                "condition = 'garbage' WHERE partKey = 'menu'"
        )

        // One cause, not a cascade: the import already said why this part cannot be answered.
        assertEquals(
            "condition-source-detached",
            partsOf(source).single { it.partKey == "menu" }.availability
        )
    }

    @Test
    fun `a stale answer is on record but not counted as answered`() = runTest {
        val source = registerSource()
        dao.runImport(source.id, "dating", plan(gate = null, menu = gateIsYes(), receipt = null), 1000)
        val taskId = rawText("SELECT taskId FROM task_imports")
        answer(taskId, "gate", "yes")
        answer(taskId, "receipt", "yes", now = 1500)

        exec("UPDATE task_part_state SET stale = 1 WHERE partKey = 'receipt'")

        assertEquals(listOf("gate"), dao.observeAnsweredPartKeys(taskId).first())
    }

    // --- fixtures ---------------------------------------------------------------------------------

    /**
     * One Task of three single-choice parts: a gate, a part conditioned on it, and a receipt that
     * is always asked. All three are `profile` parts because the read model cares about the kind
     * only for reading an answer field, and the answer is written by the protocol-agnostic DAO.
     */
    private fun plan(
        gate: PartCondition?,
        menu: PartCondition?,
        receipt: PartCondition?
    ): ImportPlan = ImportPlan(
        tasks = listOf(
            PlannedTask(
                groupPath = "$PACKAGE_KEY/~section/main",
                displayName = "Main",
                compositionKey = "section:main",
                parts = listOf("gate" to gate, "menu" to menu, "receipt" to receipt)
                    .mapIndexed { position, (key, condition) ->
                        PlannedPart(
                            partKey = key,
                            position = position,
                            origin = PartOrigin.PACKAGE,
                            prompt = PlannedTaskPrompt(
                                packageKey = PACKAGE_KEY,
                                promptKey = key,
                                kind = "profile",
                                schema = 1,
                                payload = profileBody(key),
                                revision = 1,
                                resources = emptyList<PlannedPromptResource>()
                            ),
                            condition = condition
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
                revision = 1,
                fingerprint = "fp-1",
                resources = emptyList()
            )
        )
    )

    private fun profileBody(key: String): String = """{"stem":"$key"}"""

    private fun gateIsYes() = PartCondition(
        sourcePartKey = "gate",
        field = CONDITION_FIELD_CHOICE,
        match = PartConditionMatch.Equals,
        values = listOf("yes")
    )

    private suspend fun answer(
        taskId: String,
        partKey: String,
        choice: String,
        now: Long = 1500
    ) {
        dao.submitReview(
            taskId = taskId,
            partKey = partKey,
            response = ReviewResponse.PrimaryReview(
                completed = true,
                events = listOf(ViewerEvent.Choice(optionId = choice, label = choice))
            ),
            template = REVIEW,
            context = null,
            completion = CompletionPolicy.SinglePart,
            completesTask = false,
            explicitCompletion = false,
            now = now
        )
    }

    private suspend fun registerSource(): SourceEntity = dao.registerSource(
        rootUri = ROOT,
        relativePath = "dating",
        displayName = "Dating",
        importerKind = ImporterKind.PACKAGE,
        now = 1000
    )

    private suspend fun partsOf(source: SourceEntity): List<ReviewPart> =
        dao.getCatalogTasks(source.id).single().parts

    private suspend fun applicabilityOf(
        source: SourceEntity,
        partKey: String
    ): PartApplicability = partsOf(source).single { it.partKey == partKey }.applicability

    private fun exec(sql: String) {
        database.openHelper.writableDatabase.execSQL(sql)
    }

    private fun rawText(sql: String): String =
        database.openHelper.writableDatabase.query(sql).use { cursor ->
            assertTrue("no row for: $sql", cursor.moveToFirst())
            cursor.getString(0)
        }

    private companion object {
        const val ROOT = "content://root"
        const val PACKAGE_KEY = "conditional"
        val REVIEW = TemplateVersionRef(TemplateId("fixture.review"), 1)
    }
}
