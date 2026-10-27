package com.example.listea

import com.example.listea.domain.AddOnId
import com.example.listea.domain.AddOnVersionRef
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

    @Test
    fun `the add-on a run opens on is part of its identity`() {
        val withAddOn = run(addOn = AddOnVersionRef(AddOnId("difficulty"), 2))
        val without = run(addOn = null)

        assertNotEquals(importRunIdentity(withAddOn), importRunIdentity(without))
    }

    /**
     * The upgrade flags are part of the identity even when every reference is equal.
     *
     * They decide whether a submitted reference is moved to the offered version. Two runs that look
     * identical but answer that differently would write different rows, so a restored draft must
     * not carry one's answer into the other.
     */
    @Test
    fun `whether a run upgrades what it holds is part of its identity`() {
        val adopting = run(upgradeTemplates = true, upgradeAddOn = true)
        val keeping = run(upgradeTemplates = false, upgradeAddOn = false)

        assertNotEquals(importRunIdentity(adopting), importRunIdentity(keeping))
    }

    private fun run(
        refresh: Boolean = true,
        choice: ExportChoice = ExportChoice.By(ExportKind.WEBHOOK),
        address: String = "https://n8n.example/webhook/a",
        addOn: AddOnVersionRef? = null,
        upgradeTemplates: Boolean = true,
        upgradeAddOn: Boolean = true
    ) = ImportFolderRequest.Confirm(
        folderLabel = "cos",
        fileCount = 23,
        folderCount = 0,
        refresh = refresh,
        options = ImportOptions(
            displayName = "cos",
            exportChoice = choice,
            webhookUrl = address,
            webhookFromDefault = true,
            addOn = addOn,
            upgradeTemplates = upgradeTemplates,
            upgradeAddOn = upgradeAddOn
        )
    )
}
