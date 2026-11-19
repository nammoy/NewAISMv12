package com.app.aisheildme.namtran.vault.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        AccountEntity::class,
        CheckInSummaryEntity::class,
        PatientProfileEntity::class,
        PatientReviewEntity::class,
        FolderEntity::class,
        DocumentEntity::class,
        RecordEntity::class,
        NotificationEntity::class,
        AuditEntryEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
internal abstract class VaultDatabase : RoomDatabase() {
    abstract fun accounts(): AccountDao
    abstract fun checkIns(): CheckInSummaryDao
    abstract fun patientProfiles(): PatientProfileDao
    abstract fun patientReviews(): PatientReviewDao
    abstract fun audit(): AuditDao

    companion object {
        const val NAME = "aishield_vault.db"
    }
}
