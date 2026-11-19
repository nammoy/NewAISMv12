package com.app.aisheildme.namtran.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class PatientPrefill(
    val patientCode: String? = null,
    val demographics: PrefillDemographics = PrefillDemographics(),
    val vitals: PrefillVitals? = null,
    val medicalConditions: List<String> = emptyList(),
    val allergies: List<String> = emptyList(),
    val medications: List<MedicationEntry> = emptyList(),
    val labResults: List<LabResult> = emptyList(),
    val insurance: PrefillInsurance? = null,
    val emergencyContact: PrefillContact? = null,
    val symptoms: List<PrefillSymptom> = emptyList(),
) {
    companion object {
        val EMPTY = PatientPrefill()
    }
}

@Serializable
data class PrefillDemographics(
    val firstName: String? = null,
    val lastName: String? = null,
    val dob: String? = null,
    val gender: String? = null,
    val heightCm: String? = null,
    val weightKg: String? = null,
    val bloodType: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val street: String? = null,
    val city: String? = null,
    val state: String? = null,
    val zipCode: String? = null,
) {
    val fullName: String?
        get() = listOfNotNull(firstName, lastName)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .takeIf { it.isNotBlank() }

    val addressLine: String?
        get() = listOfNotNull(street, city, state, zipCode)
            .filter { it.isNotBlank() }
            .joinToString(", ")
            .takeIf { it.isNotBlank() }
}

@Serializable
data class PrefillVitals(
    val bloodPressure: String? = null,
    val heartRate: String? = null,
    val temperature: String? = null,
    val oxygen: String? = null,
    val bmi: String? = null,
)

@Serializable
data class PrefillInsurance(
    val company: String? = null,
    val memberId: String? = null,
    val groupNumber: String? = null,
    val primaryCarePhysician: String? = null,
)

@Serializable
data class PrefillContact(
    val name: String? = null,
    val relation: String? = null,
    val phone: String? = null,
)

@Serializable
data class PrefillSymptom(
    val name: String? = null,
    val painLevel: Int = 0,
)
