package com.app.aisheildme.namtran.data.repository

import com.app.aisheildme.namtran.data.ai.PatientReviewGenerator
import com.app.aisheildme.namtran.domain.model.CheckInForm
import com.app.aisheildme.namtran.domain.model.PatientPrefill
import com.app.aisheildme.namtran.domain.model.PatientProfilePayload
import com.app.aisheildme.namtran.domain.model.PatientReview
import com.app.aisheildme.namtran.domain.model.PrefillContact
import com.app.aisheildme.namtran.domain.model.PrefillDemographics
import com.app.aisheildme.namtran.domain.model.PrefillInsurance
import com.app.aisheildme.namtran.domain.model.PrefillSymptom
import com.app.aisheildme.namtran.domain.model.PrefillVitals
import com.app.aisheildme.namtran.negotiator.Negotiator
import com.app.aisheildme.namtran.negotiator.Purpose
import com.app.aisheildme.namtran.negotiator.Requester
import com.app.aisheildme.namtran.vault.GetPatientProfile
import com.app.aisheildme.namtran.vault.LatestPatientReview
import com.app.aisheildme.namtran.vault.SavePatientProfile
import com.app.aisheildme.namtran.vault.SavePatientReview
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Patient check-in data, kept in the vault. Submitting a check-in saves the profile (to
 * pre-fill the next visit) and generates the patient review on the device.
 */
@Singleton
class PatientProfileRepository @Inject constructor(
    private val negotiator: Negotiator,
    private val reviewGenerator: PatientReviewGenerator,
) {

    /** Saves the check-in, builds the on-device review, stores it, and returns it. */
    suspend fun submitCheckIn(form: CheckInForm): PatientReview {
        val code = negotiator.request(Requester.CheckIn, Purpose.OwnerEdit, SavePatientProfile(prefillFrom(form)))
        val review = reviewGenerator.generate(form, code)
        negotiator.request(Requester.CheckIn, Purpose.OwnerEdit, SavePatientReview(review))
        return review
    }

    suspend fun getPrefill(): PatientPrefill =
        negotiator.request(Requester.CheckIn, Purpose.OwnerView, GetPatientProfile) ?: PatientPrefill.EMPTY

    suspend fun latestReview(): PatientReview? =
        negotiator.request(Requester.CheckIn, Purpose.OwnerView, LatestPatientReview)

    private fun prefillFrom(form: CheckInForm): PatientPrefill {
        fun clean(v: String?) = v?.let(PatientProfilePayload::clean)
        val symptoms = (form.symptoms + listOfNotNull(form.otherSymptom.trim().takeIf { it.isNotEmpty() }))
            .map { PrefillSymptom(name = it, painLevel = form.painLevel) }
        return PatientPrefill(
            demographics = PrefillDemographics(
                firstName = clean(form.firstName),
                lastName = clean(form.lastName),
                dob = clean(form.dateOfBirth),
                gender = clean(form.gender),
                heightCm = PatientProfilePayload.heightCm(form.height)?.toString(),
                weightKg = PatientProfilePayload.weightKg(form.weight)?.toString(),
                bloodType = clean(form.bloodType),
                phone = clean(form.phone),
                email = clean(form.email),
                street = clean(form.street),
                city = clean(form.city),
                state = clean(form.state),
                zipCode = clean(form.zipCode),
            ),
            vitals = PrefillVitals(
                bloodPressure = clean(form.bloodPressure),
                heartRate = PatientProfilePayload.intFrom(form.pulse)?.toString(),
                temperature = PatientProfilePayload.numFrom(form.temperature)?.toString(),
                oxygen = PatientProfilePayload.intFrom(form.oxygenSaturation)?.toString(),
                bmi = PatientProfilePayload.numFrom(form.bmi)?.toString(),
            ),
            medicalConditions = PatientProfilePayload.splitList(form.knownConditions),
            allergies = (PatientProfilePayload.splitList(form.knownAllergies) +
                if (form.hasNewAllergies) PatientProfilePayload.splitList(form.newAllergyDetail) else emptyList()).distinct(),
            medications = form.filledMedications,
            labResults = form.filledLabResults,
            insurance = PrefillInsurance(
                company = clean(form.insuranceCompany),
                memberId = clean(form.memberId),
                groupNumber = clean(form.groupNumber),
                primaryCarePhysician = clean(form.primaryCarePhysician),
            ),
            emergencyContact = PrefillContact(
                name = clean(form.emergencyName),
                relation = clean(form.emergencyRelation),
                phone = clean(form.emergencyPhone),
            ),
            symptoms = symptoms,
        )
    }
}
