package com.example.listea

import com.example.listea.domain.ExportChoice
import com.example.listea.domain.ExportKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The dialog's saved state is keyed by the run it describes, and the run is its seed.
 *
 * **The regression these pin.** A Refresh Import of a webhook Todo opened saying *Don't export*:
 * the seed was right, but a draft kept in the Activity's saved state by an earlier run was restored
 * over it, because `rememberSaveable` restores by composition position and the flow holding the
 * request does not survive the process. [importRunIdentity] is what tells two runs apart after the
 * request object itself is gone, so the export choice — the field that was wrong — has to be part
 * of it, along with every other value the dialog opens on.
 */
class ImportRunIdentityTest {

    @Test
    fun `the export choice is part of a run's identity`() {
        val webhook = run(choice = ExportChoice.By(ExportKind.WEBHOOK))
        val silent = run(choice = ExportChoice.Nothing)

        assertNotEquals(importRunIdentity(webhook), importRunIdentity(silent))
    }

    @Test
    fun `the address a run opens on is part of its identity`() {
        val withAddress = run(address = "https://n8n.example/webhook/a")
        val otherAddress = run(address = "https://n8n.example/webhook/b")

        assertNotEquals(importRunIdentity(withAddress), importRunIdentity(otherAddress))
    }

    @Test
    fun `a refresh and a create of the same folder are different runs`() {
        assertNotEquals(importRunIdentity(run(refresh = true)), importRunIdentity(run(refresh = false)))
    }

    /** Two reads of the same Todo produce equal identities, which is what lets rotation keep a draft. */
    @Test
    fun `the same run read twice has the same identity`() {
        assertEquals(importRunIdentity(run()), importRunIdentity(run()))
    }

    private fun run(
        refresh: Boolean = true,
        choice: ExportChoice = ExportChoice.By(ExportKind.WEBHOOK),
        address: String = "https://n8n.example/webhook/a"
    ) = ImportFolderRequest.Confirm(
        folderLabel = "cos",
        fileCount = 23,
        folderCount = 0,
        refresh = refresh,
        options = ImportOptions(
            displayName = "cos",
            exportChoice = choice,
            webhookUrl = address,
            webhookFromDefault = true
        )
    )
}
