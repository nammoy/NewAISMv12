package com.app.aisheildme.namtran.vault.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Sync bookkeeping carried by every vault row so the later server-backup phase can push
 * and pull changes without another schema rewrite.
 *  - LOCAL: created on this device, never uploaded.
 *  - DIRTY: changed locally since the last upload.
 *  - SYNCED: matches the server copy identified by remote_id.
 *  - DELETED: tombstone kept until the deletion is uploaded.
 */
enum class SyncState { LOCAL, DIRTY, SYNCED, DELETED }

@Entity(
    tableName = "accounts",
    indices = [Index(value = ["email"], unique = true)],
)
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val email: String,
    @ColumnInfo(name = "password_salt") val passwordSalt: ByteArray,
    @ColumnInfo(name = "password_hash") val passwordHash: ByteArray,
    @ColumnInfo(name = "password_iterations") val passwordIterations: Int,
    @ColumnInfo(name = "pin_salt") val pinSalt: ByteArray? = null,
    @ColumnInfo(name = "pin_hash") val pinHash: ByteArray? = null,
    @ColumnInfo(name = "failed_attempts") val failedAttempts: Int = 0,
    @ColumnInfo(name = "locked_until") val lockedUntil: Long = 0,
    /** The user's profile (domain [com.app.aisheildme.namtran.domain.model.User]) as JSON. */
    @ColumnInfo(name = "profile_json") val profileJson: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "sync_state") val syncState: SyncState = SyncState.LOCAL,
    @ColumnInfo(name = "remote_id") val remoteId: String? = null,
)

private const val ACCOUNT_FK = "account_id"

@Entity(
    tableName = "checkin_summaries",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = [ACCOUNT_FK],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(ACCOUNT_FK)],
)
data class CheckInSummaryEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = ACCOUNT_FK) val accountId: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "summary_text") val summaryText: String,
    @ColumnInfo(name = "patient_name") val patientName: String?,
    @ColumnInfo(name = "clinic_name") val clinicName: String?,
    @ColumnInfo(name = "appointment_type") val appointmentType: String?,
    @ColumnInfo(name = "reason_for_visit") val reasonForVisit: String?,
    @ColumnInfo(name = "device_label") val deviceLabel: String?,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "sync_state") val syncState: SyncState = SyncState.LOCAL,
    @ColumnInfo(name = "remote_id") val remoteId: String? = null,
)

/** The patient's saved check-in profile, used to pre-fill the next check-in. One per account. */
@Entity(
    tableName = "patient_profiles",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = [ACCOUNT_FK],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class PatientProfileEntity(
    @PrimaryKey @ColumnInfo(name = ACCOUNT_FK) val accountId: Int,
    @ColumnInfo(name = "patient_code") val patientCode: String,
    @ColumnInfo(name = "payload_json") val payloadJson: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "sync_state") val syncState: SyncState = SyncState.LOCAL,
    @ColumnInfo(name = "remote_id") val remoteId: String? = null,
)

@Entity(
    tableName = "patient_reviews",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = [ACCOUNT_FK],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(ACCOUNT_FK)],
)
data class PatientReviewEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(name = ACCOUNT_FK) val accountId: Int,
    @ColumnInfo(name = "patient_code") val patientCode: String,
    @ColumnInfo(name = "review_json") val reviewJson: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "sync_state") val syncState: SyncState = SyncState.LOCAL,
    @ColumnInfo(name = "remote_id") val remoteId: String? = null,
)

@Entity(
    tableName = "folders",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = [ACCOUNT_FK],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(ACCOUNT_FK)],
)
data class FolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(name = ACCOUNT_FK) val accountId: Int,
    @ColumnInfo(name = "parent_id") val parentId: Int? = null,
    val name: String,
    val icon: String,
    val color: String,
    @ColumnInfo(name = "is_default") val isDefault: Boolean = false,
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "sync_state") val syncState: SyncState = SyncState.LOCAL,
    @ColumnInfo(name = "remote_id") val remoteId: String? = null,
)

@Entity(
    tableName = "documents",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = [ACCOUNT_FK],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(ACCOUNT_FK), Index("folder_id")],
)
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(name = ACCOUNT_FK) val accountId: Int,
    @ColumnInfo(name = "folder_id") val folderId: Int? = null,
    val title: String,
    val description: String = "",
    @ColumnInfo(name = "file_name") val fileName: String,
    @ColumnInfo(name = "file_type") val fileType: String,
    @ColumnInfo(name = "file_size") val fileSize: Long,
    /** Path of the encrypted blob inside the vault's private files directory. */
    @ColumnInfo(name = "blob_path") val blobPath: String,
    @ColumnInfo(name = "tags_json") val tagsJson: String = "[]",
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "sync_state") val syncState: SyncState = SyncState.LOCAL,
    @ColumnInfo(name = "remote_id") val remoteId: String? = null,
)

/**
 * Health appointments, bank statements, retail orders and insurance policies. One table,
 * typed by [industry]; the industry-specific fields live in [payloadJson].
 */
@Entity(
    tableName = "records",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = [ACCOUNT_FK],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(ACCOUNT_FK), Index("industry")],
)
data class RecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(name = ACCOUNT_FK) val accountId: Int,
    val industry: String,
    /** Where the record came from: MANUAL, SCAN, FILE_IMPORT, SHARED_IN, HEALTH_CONNECT. */
    val source: String,
    val title: String,
    @ColumnInfo(name = "occurred_at") val occurredAt: Long? = null,
    @ColumnInfo(name = "payload_json") val payloadJson: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "sync_state") val syncState: SyncState = SyncState.LOCAL,
    @ColumnInfo(name = "remote_id") val remoteId: String? = null,
)

@Entity(
    tableName = "notifications",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = [ACCOUNT_FK],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(ACCOUNT_FK)],
)
data class NotificationEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(name = ACCOUNT_FK) val accountId: Int,
    val type: String,
    val title: String,
    val message: String,
    @ColumnInfo(name = "is_read") val isRead: Boolean = false,
    @ColumnInfo(name = "request_id") val requestId: Int? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "sync_state") val syncState: SyncState = SyncState.LOCAL,
    @ColumnInfo(name = "remote_id") val remoteId: String? = null,
)

/**
 * Every request the Negotiator handles, allowed or denied. Records only *what kind* of
 * access happened, never the data itself.
 */
@Entity(tableName = "audit_log", indices = [Index("at")])
data class AuditEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    @ColumnInfo(name = "account_id") val accountId: Int?,
    val requester: String,
    val purpose: String,
    val operation: String,
    val decision: String,
    val reason: String? = null,
)
