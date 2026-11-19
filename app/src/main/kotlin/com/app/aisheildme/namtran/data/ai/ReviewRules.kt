package com.app.aisheildme.namtran.data.ai

import com.app.aisheildme.namtran.domain.model.CheckInForm
import com.app.aisheildme.namtran.domain.model.FindingSeverity
import com.app.aisheildme.namtran.domain.model.ReviewFinding

/**
 * Deterministic screening checks run on the device before the AI summary.
 *
 * Thresholds follow common adult screening references (e.g. ACC/AHA blood-pressure
 * categories, CDC fever definition). They flag readings for a clinician's attention;
 * they are not a diagnosis.
 */
object ReviewRules {

    fun evaluate(form: CheckInForm): List<ReviewFinding> = buildList {
        bloodPressure(form.bloodPressure)?.let(::add)
        pulse(form.pulse)?.let(::add)
        temperature(form.temperature)?.let(::add)
        oxygen(form.oxygenSaturation)?.let(::add)
        bmi(form.bmi)?.let(::add)
        addAll(allergyConflicts(form))
        addAll(symptoms(form))
        if (form.hasNewAllergies && form.newAllergyDetail.isNotBlank()) {
            add(info("NEW_ALLERGY", "New allergy reported: ${form.newAllergyDetail.trim()}."))
        }
        if (form.medicationsChanged) {
            add(info("MEDS_CHANGED", "Medications changed since the last visit — please reconcile."))
        }
    }.sortedBy { it.severity.ordinal }

    // ---- vitals ----

    private val BP = Regex("""(\d{2,3})\s*/\s*(\d{2,3})""")

    fun bloodPressure(raw: String): ReviewFinding? {
        val m = BP.find(raw) ?: return null
        val sys = m.groupValues[1].toInt()
        val dia = m.groupValues[2].toInt()
        val reading = "$sys/$dia mmHg"
        return when {
            sys >= 180 || dia >= 120 -> critical("BP_CRISIS", "Blood pressure $reading is in the hypertensive-crisis range.")
            sys >= 140 || dia >= 90 -> warning("BP_STAGE2", "Blood pressure $reading is in the stage 2 hypertension range.")
            sys < 90 || dia < 60 -> warning("BP_LOW", "Blood pressure $reading is low.")
            sys >= 130 || dia >= 80 -> info("BP_STAGE1", "Blood pressure $reading is in the stage 1 hypertension range.")
            sys >= 120 -> info("BP_ELEVATED", "Blood pressure $reading is elevated.")
            else -> null
        }
    }

    fun pulse(raw: String): ReviewFinding? {
        val bpm = number(raw)?.toInt() ?: return null
        return when {
            bpm < 40 || bpm > 130 -> critical("PULSE_EXTREME", "Pulse $bpm bpm is far outside the normal range.")
            bpm < 50 -> warning("PULSE_LOW", "Pulse $bpm bpm is low.")
            bpm > 100 -> warning("PULSE_HIGH", "Pulse $bpm bpm is high.")
            else -> null
        }
    }

    fun temperature(raw: String): ReviewFinding? {
        val value = number(raw) ?: return null
        // Above 45 can only be Fahrenheit for a living patient.
        val fahrenheit = value > 45 || raw.contains("F", ignoreCase = true) && !raw.contains("C", ignoreCase = true)
        val c = if (fahrenheit) (value - 32) * 5 / 9 else value
        val shown = if (fahrenheit) "%.1f °F".format(value) else "%.1f °C".format(value)
        return when {
            c >= 40.0 -> critical("TEMP_VERY_HIGH", "Temperature $shown is very high.")
            c >= 38.0 -> warning("FEVER", "Temperature $shown indicates a fever.")
            c < 35.0 -> warning("TEMP_LOW", "Temperature $shown is low.")
            else -> null
        }
    }

    fun oxygen(raw: String): ReviewFinding? {
        val spo2 = number(raw)?.toInt() ?: return null
        return when {
            spo2 < 90 -> critical("SPO2_CRITICAL", "Oxygen saturation $spo2% is critically low.")
            spo2 < 95 -> warning("SPO2_LOW", "Oxygen saturation $spo2% is below normal.")
            else -> null
        }
    }

    fun bmi(raw: String): ReviewFinding? {
        val bmi = number(raw) ?: return null
        return when {
            bmi >= 30 -> info("BMI_HIGH", "BMI %.1f is in the obesity range.".format(bmi))
            bmi < 18.5 -> info("BMI_LOW", "BMI %.1f is in the underweight range.".format(bmi))
            else -> null
        }
    }

    // ---- allergies vs medications ----

    /** Allergy keyword → medication names that commonly cross-react. */
    private val ALLERGY_CLASSES = mapOf(
        "penicillin" to listOf("penicillin", "amoxicillin", "ampicillin", "augmentin", "piperacillin"),
        "sulfa" to listOf("sulfamethoxazole", "bactrim", "septra", "sulfasalazine"),
        "nsaid" to listOf("ibuprofen", "naproxen", "aspirin", "diclofenac", "celecoxib", "advil", "aleve", "motrin"),
        "aspirin" to listOf("aspirin", "ibuprofen", "naproxen"),
        "codeine" to listOf("codeine", "tylenol #3", "morphine"),
        "cephalosporin" to listOf("cephalexin", "keflex", "ceftriaxone", "cefuroxime"),
    )

    fun allergyConflicts(form: CheckInForm): List<ReviewFinding> {
        val allergies = (split(form.knownAllergies) + if (form.hasNewAllergies) split(form.newAllergyDetail) else emptyList())
            .map { it.lowercase() }
            .filter { it.isNotBlank() && it != "none" && it != "none on file" && it != "nkda" }
        if (allergies.isEmpty()) return emptyList()

        val meds = (form.filledMedications.map { it.name } + split(form.knownMedications))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        return meds.mapNotNull { med ->
            val name = med.lowercase()
            val hit = allergies.firstOrNull { allergy ->
                name.contains(allergy) || allergy.contains(name) ||
                    ALLERGY_CLASSES.any { (key, drugs) -> allergy.contains(key) && drugs.any { name.contains(it) } }
            } ?: return@mapNotNull null
            critical("ALLERGY_CONFLICT", "Medication \"$med\" may conflict with the listed allergy \"$hit\".")
        }.distinctBy { it.message }
    }

    // ---- symptoms ----

    fun symptoms(form: CheckInForm): List<ReviewFinding> = buildList {
        val all = (form.symptoms + form.otherSymptom).joinToString(" ").lowercase()
        val chestPain = "chest pain" in all
        val breathless = "shortness of breath" in all || "breath" in all
        when {
            chestPain && breathless -> add(critical("URGENT_SYMPTOMS", "Chest pain with shortness of breath needs prompt evaluation."))
            chestPain -> add(warning("CHEST_PAIN", "Chest pain reported."))
            breathless -> add(warning("BREATHLESS", "Shortness of breath reported."))
        }
        if (form.painLevel >= 8) add(warning("PAIN_SEVERE", "Severe pain reported (${form.painLevel}/10)."))
    }

    // ---- helpers ----

    private val NUMBER = Regex("""\d+(\.\d+)?""")

    private fun number(raw: String): Double? = NUMBER.find(raw)?.value?.toDoubleOrNull()

    private fun split(raw: String): List<String> = raw.split(Regex("[,;\n]")).map { it.trim() }

    private fun critical(code: String, message: String) = ReviewFinding(FindingSeverity.Critical, code, message)
    private fun warning(code: String, message: String) = ReviewFinding(FindingSeverity.Warning, code, message)
    private fun info(code: String, message: String) = ReviewFinding(FindingSeverity.Info, code, message)
}
