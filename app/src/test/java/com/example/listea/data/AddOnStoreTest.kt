package com.example.listea.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.listea.AddOnResolver
import com.example.listea.domain.ADD_ON_QUESTIONS_VERSION
import com.example.listea.domain.AddOnQuestion
import com.example.listea.domain.AddOnQuestionType
import com.example.listea.domain.decodeAddOnQuestions
import com.example.listea.domain.encodeAddOnQuestions
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
 * The one thing that writes an add-on: an edit appends a version, and nothing revises one.
 *
 * The template store's promise in the add-on vocabulary, checked against the rows rather than the
 * values the store was handed. A version that came back with its questions intact and its
 * `createdAt` rewritten was rewritten, however equal the two look on a screen.
 *
 * **The refusals are the other half**, and they are tested here because they are where the store
 * stops trusting the page. A blank prompt or an empty questionnaire reaching the rows would be a
 * question with nothing to answer on every Task that attaches it; refusing writes nothing at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AddOnStoreTest {

    private lateinit var database: ListeaDatabase
    private lateinit var store: AddOnStore
    private lateinit var reads: AddOnDao
    private lateinit var writes: AddOnWriteDao

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val addOnId = "review"

    @Before
    fun openDatabase() {
        database = Room.inMemoryDatabaseBuilder(context, ListeaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        reads = database.addOnDao()
        writes = database.addOnWriteDao()
        store = AddOnStore(database)
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    // --- starting one -------------------------------------------------------------------------------

    @Test
    fun `create writes a lineage and version 1 with one rating question`() = runTest {
        val id = store.create("Task review")!!

        assertEquals("Task review", lineage(id)?.name)
        val version = versions(id).single()
        assertEquals(1, version.version)
        assertEquals(ADD_ON_QUESTIONS_VERSION, version.questionsVersion)
        // Not an empty questionnaire: one Rating question, so the add-on can be attached the moment
        // it exists and a user has something to rewrite rather than a name with nothing behind it.
        val question = decodeQuestions(version).single()
        assertEquals(AddOnQuestionType.RATING, question.type)
        assertEquals("Overall rating", question.prompt)
    }

    @Test
    fun `two creates get two opaque ids`() = runTest {
        val first = store.create("One")!!
        val second = store.create("Two")!!

        assertTrue(first != second)
        assertTrue(Regex("""^[0-9a-f-]{36}$""").matches(first))
        assertEquals(2, store.addOns.first().size)
    }

    @Test
    fun `create sanitises the name and refuses a blank one`() = runTest {
        val id = store.create("  Task\tReview  ")!!

        assertEquals("Task Review", lineage(id)?.name)
        assertNull(store.create("   "))
        assertEquals(1, store.addOns.first().size)
    }

    @Test
    fun `a created add-on is attachable at once`() = runTest {
        store.create("Task review")

        // The resolver's own answer, not a hand-built list: an add-on whose payload it cannot read
        // would be created and then silently unofferable.
        val (lineages, versions) = store.snapshot()
        val resolver = AddOnResolver.of(lineages, versions)

        assertEquals(listOf("Task review"), resolver.attachable.map { it.name })
        assertEquals(1, resolver.attachable.single().questions.size)
    }

    // --- an edit appends ----------------------------------------------------------------------------

    @Test
    fun `an edit appends a version and leaves the one it edited exactly as it was`() = runTest {
        seed()
        val before = versions()

        store.update(addOnId, "Review", listOf(question("q1", "How good are these?")))

        val after = versions()
        assertEquals(2, after.size)
        // The whole row: a version rewritten in place — even into the same text — is one whose
        // `createdAt` moved, and that is the thing being ruled out.
        assertEquals(before.single(), after.first())
        assertEquals(2, after.last().version)
        assertEquals(
            listOf(question("q1", "How good are these?")),
            decodeQuestions(after.last())
        )
    }

    @Test
    fun `three edits leave three versions and nothing else`() = runTest {
        seed()

        store.update(addOnId, "Review", listOf(question("q1", "One")))
        store.update(addOnId, "Review", listOf(question("q1", "Two")))
        store.update(addOnId, "Review", listOf(question("q1", "Three")))

        val rows = versions()
        assertEquals(listOf(1, 2, 3, 4), rows.map { it.version })
        assertEquals(
            listOf("Two", "Three"),
            rows.drop(2).map { decodeQuestions(it).single().prompt }
        )
    }

    @Test
    fun `every version it writes is stamped with the questions version this build reads`() = runTest {
        seed()
        store.update(addOnId, "Review", listOf(question("q1", "Changed")))

        assertTrue(versions().all { it.questionsVersion == ADD_ON_QUESTIONS_VERSION })
    }

    // --- a rename is not a version ------------------------------------------------------------------

    @Test
    fun `renaming an add-on writes no version at all`() = runTest {
        seed()
        val before = versions()

        store.update(addOnId, "Renamed", listOf(seededQuestion()))

        assertEquals("Renamed", lineage()?.name)
        assertEquals(before, versions())
    }

    @Test
    fun `an Update that says what the last version said writes nothing`() = runTest {
        seed()
        val before = versions()

        store.update(addOnId, "Review", listOf(seededQuestion()))

        assertEquals(before, versions())
        assertEquals("Review", lineage()?.name)
    }

    @Test
    fun `a rename and an edit in one visit write one version and one name`() = runTest {
        seed()

        store.update(addOnId, "Renamed", listOf(question("q1", "Changed")))

        assertEquals("Renamed", lineage()?.name)
        assertEquals(2, versions().size)
    }

    // --- what it refuses ----------------------------------------------------------------------------

    @Test
    fun `a questionnaire with no questions is refused`() = runTest {
        seed()
        val before = versions()

        store.update(addOnId, "Renamed", emptyList())

        // An add-on with nothing to ask draws a blank page on every Task that attaches it, and the
        // same rule keeps it out of reach of the seed path.
        assertEquals(before, versions())
        assertEquals("Review", lineage()?.name)
    }

    @Test
    fun `a blank prompt refuses the whole edit`() = runTest {
        seed()
        val before = versions()

        store.update(
            addOnId,
            "Renamed",
            listOf(question("q1", "Fine"), question("q2", "   "))
        )

        // Whole rather than in part: saving the good row would give the blank one an answerable line
        // on the page and no explanation of why it is blank.
        assertEquals(before, versions())
        assertEquals("Review", lineage()?.name)
    }

    @Test
    fun `a duplicate question id refuses the whole edit`() = runTest {
        seed()
        val before = versions()

        store.update(
            addOnId,
            "Renamed",
            listOf(question("q1", "One"), question("q1", "Two"))
        )

        // Two questions claiming one answer row is a table that cannot say which was answered.
        assertEquals(before, versions())
    }

    @Test
    fun `a blank name refuses the whole edit`() = runTest {
        seed()
        val before = versions()

        store.update(addOnId, "   ", listOf(question("q1", "Changed")))

        assertEquals(before, versions())
        assertEquals("Review", lineage()?.name)
    }

    @Test
    fun `an update to an add-on that is not there creates nothing`() = runTest {
        store.update("no.such.addon", "Anything", listOf(question("q1", "One")))

        assertNull(lineage("no.such.addon"))
        assertTrue(store.addOns.first().isEmpty())
    }

    @Test
    fun `an add-on with no version is left alone rather than given one`() = runTest {
        writes.insertAddOn(AddOnEntity("stub", "Stub", 1L))

        store.update("stub", "Renamed", listOf(question("q1", "One")))

        assertEquals("Stub", lineage("stub")?.name)
        assertTrue(versions("stub").isEmpty())
    }

    @Test
    fun `a version written by a newer build is not written over, name and all`() = runTest {
        seed(questionsVersion = ADD_ON_QUESTIONS_VERSION + 1)
        val before = versions()

        store.update(addOnId, "Renamed", listOf(question("q1", "One")))

        // Appending over a version this build cannot read is how a history gets a step nothing can
        // explain, and the two payloads could not be compared to decide whether anything moved.
        assertEquals(before, versions())
        assertEquals("Review", lineage()?.name)
    }

    @Test
    fun `a corrupt payload under a known version is also not written over`() = runTest {
        writes.insertAddOn(AddOnEntity(addOnId, "Review", 1L))
        writes.insertVersion(
            AddOnVersionEntity(
                addOnId = addOnId,
                version = 1,
                questions = "q1\tcolour\tNot a type",
                questionsVersion = ADD_ON_QUESTIONS_VERSION,
                createdAt = 1L
            )
        )

        store.update(addOnId, "Renamed", listOf(question("q1", "One")))

        assertEquals(1, versions().size)
        assertEquals("Review", lineage()?.name)
    }

    // --- and what it keeps out of the payload -------------------------------------------------------

    @Test
    fun `a prompt is kept to one line before it is stored`() = runTest {
        seed()

        store.update(addOnId, "Review", listOf(question("q1", "  A\tB\rC  ")))

        // The same sanitiser template text passes through: the encoding is one question per line,
        // so a newline in a prompt would split its own row into two unreadable ones.
        assertEquals("A B C", decodeQuestions(versions().last()).single().prompt)
    }

    @Test
    fun `a version already written cannot be overwritten`() = runTest {
        seed()
        val before = versions().single()

        val result = runCatching {
            writes.insertVersion(before.copy(questions = encodeAddOnQuestions(listOf(question("q1", "Replaced")))))
        }

        // The constraint, not a convention: `insertVersion` aborts on a key that is already there,
        // so *an edit appends* is enforced by SQLite rather than remembered by the store.
        assertTrue(result.isFailure)
        assertEquals(before, versions().single())
    }

    // --- the fixture --------------------------------------------------------------------------------

    private suspend fun seed(questionsVersion: Int = ADD_ON_QUESTIONS_VERSION) {
        writes.insertAddOn(AddOnEntity(addOnId, "Review", 1L))
        writes.insertVersion(
            AddOnVersionEntity(
                addOnId = addOnId,
                version = 1,
                questions = encodeAddOnQuestions(listOf(seededQuestion())),
                questionsVersion = questionsVersion,
                createdAt = 1L
            )
        )
    }

    /** The question the seeded version holds, so an edit that changes nothing says exactly that. */
    private fun seededQuestion() = question("q1", "Overall rating")

    private fun question(id: String, prompt: String) =
        AddOnQuestion(id, AddOnQuestionType.RATING, prompt)

    private suspend fun lineage(id: String = addOnId): AddOnEntity? = reads.addOn(id)

    private suspend fun versions(id: String = addOnId): List<AddOnVersionEntity> = reads.versionsOf(id)

    private fun decodeQuestions(version: AddOnVersionEntity): List<AddOnQuestion> =
        decodeAddOnQuestions(version.questions, version.questionsVersion).orEmpty()
}
