package com.app.aisheildme.namtran.data.ai

import com.app.aisheildme.namtran.domain.model.CheckInForm
import com.app.aisheildme.namtran.domain.model.FindingSeverity
import com.app.aisheildme.namtran.domain.model.PatientReview
import com.app.aisheildme.namtran.domain.model.ReviewAiAgent
import com.app.aisheildme.namtran.domain.model.ReviewFinding
import com.app.aisheildme.namtran.domain.model.ReviewPatient
import com.app.aisheildme.namtran.domain.model.ReviewSummary
import com.app.aisheildme.namtran.domain.model.ReviewVitals
import com.app.aisheildme.namtran.domain.model.PatientProfilePayload
import com.app.aisheildme.namtran.feature.checkin.CheckInFormText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the pre-visit patient review entirely on the device:
 *  1. [ReviewRules] flag readings and conflicts deterministically.
 *  2. Gemini Nano writes the summary and recommendation from the form and those findings.
 * If Nano is unavailable or slow, the review still completes from the rules alone.
 */
@Singleton
class PatientReviewGenerator @Inject constructor(
    private val onDeviceAi: OnDeviceAi,
) {

    suspend fun generate(form: CheckInForm, patientCode: String): PatientReview {
        val findings = ReviewRules.evaluate(form)
        val ai = aiSummary(form, findings) ?: rulesOnlySummary(form, findings)

        return PatientReview(
            patient = ReviewPatient(
                patientCode = patientCode,
                name = form.fullName.takeUnless { it.isBlank() || it == "—" },
                dob = form.dateOfBirth.takeUnless { it.isBlank() || it == "—" },
                gender = form.gender.takeUnless { it.isBlank() || it == "—" },
                heightCm = PatientProfilePayload.heightCm(form.height)?.toString(),
                weightKg = PatientProfilePayload.weightKg(form.weight)?.toString(),
                bloodType = form.bloodType.takeUnless { it.isBlank() || it == "—" },
                primaryDoctor = form.primaryCarePhysician.takeIf { it.isNotBlank() },
            ),
            vitalSigns = ReviewVitals(
                bloodPressure = form.bloodPressure.takeIf { it.isNotBlank() },
                heartRate = form.pulse.takeIf { it.isNotBlank() },
                temperature = form.temperature.takeIf { it.isNotBlank() },
                oxygen = form.oxygenSaturation.takeIf { it.isNotBlank() },
                bmi = form.bmi.takeIf { it.isNotBlank() },
            ),
            medicalConditions = PatientProfilePayload.splitList(form.knownConditions),
            allergies = (PatientProfilePayload.splitList(form.knownAllergies) +
                if (form.hasNewAllergies) PatientProfilePayload.splitList(form.newAllergyDetail) else emptyList()).distinct(),
            medications = form.filledMedications,
            labResults = form.filledLabResults,
            findings = findings,
            summary = ReviewSummary(
                total = findings.size,
                critical = findings.count { it.severity == FindingSeverity.Critical },
                warning = findings.count { it.severity == FindingSeverity.Warning },
                info = findings.count { it.severity == FindingSeverity.Info },
                hasBlock = findings.any { it.severity == FindingSeverity.Critical },
            ),
            aiAgent = ai,
        )
    }

    private suspend fun aiSummary(form: CheckInForm, findings: List<ReviewFinding>): ReviewAiAgent? = try {
        val prompt = CheckInPrompts.patientReview(
            formText = CheckInFormText.render(form),
            findings = findings.mapNotNull { f -> f.message?.let { "${f.severity.name.uppercase()}: $it" } },
        )
        val raw = withTimeoutOrNull(AI_TIMEOUT_MS) { onDeviceAi.generate(prompt) }
        raw?.let(::parse)?.copy(driver = "Gemini Nano · on-device (${onDeviceAi.deviceLabel()})")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private fun parse(raw: String): ReviewAiAgent? {
        fun line(key: String) = raw.lineSequence()
            .map { it.trim().trimStart('*', '-', ' ') }
            .firstOrNull { it.startsWith("$key:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.trim('*')?.takeIf { it.isNotBlank() }

        val summary = line("SUMMARY") ?: raw.trim().takeIf { it.isNotBlank() }?.take(600) ?: return null
        return ReviewAiAgent(summary = summary, recommendation = line("RECOMMENDATION"))
    }

    private fun rulesOnlySummary(form: CheckInForm, findings: List<ReviewFinding>): ReviewAiAgent {
        val who = form.fullName.takeUnless { it.isBlank() || it == "—" } ?: "The patient"
        val critical = findings.filter { it.severity == FindingSeverity.Critical }
        val summary = buildString {
            append("$who is checking in for: ${form.reasonForVisit.trim().ifEmpty { "not stated" }}.")
            append(" ${findings.size} screening finding(s): ${critical.size} critical.")
        }
        val recommendation = critical.firstOrNull()?.message?.let { "Review first: $it" }
            ?: findings.firstOrNull()?.message?.let { "Review: $it" }
            ?: "No screening flags. Confirm the reason for visit and current medications."
        return ReviewAiAgent(summary = summary, recommendation = recommendation, driver = "On-device screening rules")
    }

    private companion object {
        const val AI_TIMEOUT_MS = 45_000L
    }
}
