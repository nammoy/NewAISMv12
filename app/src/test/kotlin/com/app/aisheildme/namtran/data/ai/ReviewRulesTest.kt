package com.app.aisheildme.namtran.data.ai

import com.app.aisheildme.namtran.domain.model.CheckInForm
import com.app.aisheildme.namtran.domain.model.FindingSeverity
import com.app.aisheildme.namtran.domain.model.MedicationEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewRulesTest {

    @Test
    fun `blood pressure categories`() {
        assertNull(ReviewRules.bloodPressure("118/76"))
        assertEquals("BP_ELEVATED", ReviewRules.bloodPressure("124/78")?.code)
        assertEquals("BP_STAGE1", ReviewRules.bloodPressure("134/82")?.code)
        assertEquals("BP_STAGE2", ReviewRules.bloodPressure("145/95")?.code)
        assertEquals(FindingSeverity.Critical, ReviewRules.bloodPressure("185 / 100")?.severity)
        assertEquals("BP_LOW", ReviewRules.bloodPressure("85/55")?.code)
        assertNull(ReviewRules.bloodPressure(""))
    }

    @Test
    fun `temperature handles both units`() {
        assertNull(ReviewRules.temperature("98.6"))
        assertEquals("FEVER", ReviewRules.temperature("101.2 F")?.code)
        assertEquals("FEVER", ReviewRules.temperature("38.4")?.code)
        assertEquals(FindingSeverity.Critical, ReviewRules.temperature("104.5")?.severity)
        assertEquals("TEMP_LOW", ReviewRules.temperature("34.5 C")?.code)
    }

    @Test
    fun `oxygen and pulse`() {
        assertNull(ReviewRules.oxygen("98%"))
        assertEquals("SPO2_LOW", ReviewRules.oxygen("93")?.code)
        assertEquals(FindingSeverity.Critical, ReviewRules.oxygen("88 %")?.severity)
        assertNull(ReviewRules.pulse("72 bpm"))
        assertEquals("PULSE_HIGH", ReviewRules.pulse("112")?.code)
        assertEquals(FindingSeverity.Critical, ReviewRules.pulse("35")?.severity)
    }

    @Test
    fun `penicillin allergy flags amoxicillin`() {
        val form = CheckInForm(
            knownAllergies = "Penicillin",
            medications = listOf(MedicationEntry(name = "Amoxicillin", dose = "500mg", frequency = "TID")),
        )
        val conflicts = ReviewRules.allergyConflicts(form)
        assertEquals(1, conflicts.size)
        assertEquals(FindingSeverity.Critical, conflicts.first().severity)
    }

    @Test
    fun `no allergies on file means no conflicts`() {
        val form = CheckInForm(
            knownAllergies = "None on file",
            medications = listOf(MedicationEntry(name = "Ibuprofen")),
        )
        assertTrue(ReviewRules.allergyConflicts(form).isEmpty())
    }

    @Test
    fun `chest pain with shortness of breath is critical and sorted first`() {
        val form = CheckInForm(
            symptoms = listOf("Chest Pain", "Shortness of Breath"),
            bmi = "31",
        )
        val findings = ReviewRules.evaluate(form)
        assertEquals("URGENT_SYMPTOMS", findings.first().code)
        assertTrue(findings.any { it.code == "BMI_HIGH" })
    }
}
