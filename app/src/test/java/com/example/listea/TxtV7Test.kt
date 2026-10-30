package com.example.listea

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.listea.domain.PartApplicability
import com.example.listea.domain.PartCondition
import com.example.listea.domain.PartConditionMatch
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The text export of a v7 round: the webhook body, byte for byte.
 *
 * The file has no second format to keep in step — [TxtExporter.render] is
 * [buildWebhookPayload] — so this test is the contract that says a v7 part's
 * `judgment`/`when`/`applicability`/`stale` reach a file receiver exactly as they reach a POST.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TxtV7Test {

    private val exporter = TxtExporter(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun `the text export of a conditional round is the v7 body`() {
        val payload = payload()

        val rendered = exporter.render(payload)

        assertEquals(buildWebhookPayload(mapExport(payload)), rendered)
        val part = JSONObject(rendered)
            .getJSONArray("tasks").getJSONObject(0)
            .getJSONArray("parts").getJSONObject(0)
        assertEquals(7, JSONObject(rendered).getInt("version"))
        assertEquals("inactive", part.getString("applicability"))
        assertEquals("gate", part.getJSONObject("when").getString("prompt"))
    }

    private fun payload(): ExportPayload = ExportPayload(
        event = "list.completed",
        listId = 1,
        listTitle = "Conditional",
        completedAt = null,
        sourceId = null,
        sourceTitle = null,
        at = 1,
        tasks = listOf(
            ExportTaskView(
                publicId = "TASK-1",
                title = "Conditional",
                isCompleted = true,
                templates = emptyList(),
                assets = emptyList(),
                parts = listOf(
                    ExportPartView(
                        partKey = "menu",
                        promptKey = "menu",
                        kind = "media",
                        schema = 1,
                        revision = 1,
                        required = true,
                        condition = PartCondition(
                            sourcePartKey = "gate",
                            field = "choice",
                            match = PartConditionMatch.Equals,
                            values = listOf("yes")
                        ),
                        applicability = PartApplicability.Inactive,
                        result = ExportPartResult.AnsweredEmpty,
                        assets = emptyList()
                    )
                ),
                hasMultipleParts = true
            )
        )
    )
}
