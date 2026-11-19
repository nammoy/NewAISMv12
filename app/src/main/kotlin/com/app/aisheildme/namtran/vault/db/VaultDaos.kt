package com.app.aisheildme.namtran.vault.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
internal interface AccountDao {

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun byId(id: Int): AccountEntity?

    @Query("SELECT * FROM accounts WHERE id = :id")
    fun observe(id: Int): Flow<AccountEntity?>

    @Query("SELECT * FROM accounts WHERE email = :email COLLATE NOCASE LIMIT 1")
    suspend fun byEmail(email: String): AccountEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(account: AccountEntity): Long

    @Update
    suspend fun update(account: AccountEntity)

    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun delete(id: Int)
}

@Dao
internal interface CheckInSummaryDao {

    @Query("SELECT * FROM checkin_summaries WHERE account_id = :accountId AND sync_state != 'DELETED' ORDER BY created_at DESC")
    fun observeAll(accountId: Int): Flow<List<CheckInSummaryEntity>>

    @Query("SELECT * FROM checkin_summaries WHERE account_id = :accountId AND sync_state != 'DELETED' ORDER BY created_at DESC")
    suspend fun all(accountId: Int): List<CheckInSummaryEntity>

    @Query("SELECT COUNT(*) FROM checkin_summaries WHERE account_id = :accountId AND sync_state != 'DELETED'")
    suspend fun count(accountId: Int): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(entity: CheckInSummaryEntity)

    @Query("DELETE FROM checkin_summaries WHERE account_id = :accountId AND id = :id")
    suspend fun delete(accountId: Int, id: String)

    @Query("DELETE FROM checkin_summaries WHERE account_id = :accountId")
    suspend fun clear(accountId: Int)
}

@Dao
internal interface PatientProfileDao {

    @Query("SELECT * FROM patient_profiles WHERE account_id = :accountId")
    suspend fun get(accountId: Int): PatientProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(entity: PatientProfileEntity)
}

@Dao
internal interface PatientReviewDao {

    @Insert
    suspend fun insert(entity: PatientReviewEntity): Long

    @Query("SELECT * FROM patient_reviews WHERE account_id = :accountId AND sync_state != 'DELETED' ORDER BY created_at DESC LIMIT 1")
    suspend fun latest(accountId: Int): PatientReviewEntity?
}

@Dao
internal interface AuditDao {

    @Insert
    suspend fun insert(entry: AuditEntryEntity): Long

    @Query("SELECT * FROM audit_log WHERE account_id = :accountId ORDER BY at DESC LIMIT :limit")
    fun observeRecent(accountId: Int, limit: Int): Flow<List<AuditEntryEntity>>

    /** Keeps the log bounded on the device. */
    @Query("DELETE FROM audit_log WHERE at < :before")
    suspend fun pruneBefore(before: Long)
}
