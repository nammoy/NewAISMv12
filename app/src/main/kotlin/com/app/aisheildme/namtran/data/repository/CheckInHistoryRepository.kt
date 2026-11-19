package com.app.aisheildme.namtran.data.repository

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.app.aisheildme.namtran.domain.model.SavedCheckInSummary
import com.app.aisheildme.namtran.negotiator.Negotiator
import com.app.aisheildme.namtran.negotiator.Purpose
import com.app.aisheildme.namtran.negotiator.Requester
import com.app.aisheildme.namtran.vault.ClearCheckInSummaries
import com.app.aisheildme.namtran.vault.CountCheckInSummaries
import com.app.aisheildme.namtran.vault.DeleteCheckInSummary
import com.app.aisheildme.namtran.vault.ListCheckInSummaries
import com.app.aisheildme.namtran.vault.ObserveCheckInSummaries
import com.app.aisheildme.namtran.vault.SaveCheckInSummary
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CheckInHistoryRepository @Inject constructor(
    private val negotiator: Negotiator,
    @param:ApplicationContext private val context: Context,
) {
    val summaries: Flow<List<SavedCheckInSummary>> =
        negotiator.observe(Requester.CheckIn, Purpose.OwnerView, ObserveCheckInSummaries)
            .map { it.orEmpty() }

    suspend fun all(): List<SavedCheckInSummary> =
        negotiator.request(Requester.CheckIn, Purpose.OwnerView, ListCheckInSummaries)

    suspend fun count(): Int =
        negotiator.request(Requester.CheckIn, Purpose.OwnerView, CountCheckInSummaries)

    suspend fun save(summary: SavedCheckInSummary) =
        negotiator.request(Requester.CheckIn, Purpose.OwnerEdit, SaveCheckInSummary(summary))

    suspend fun delete(id: String) =
        negotiator.request(Requester.CheckIn, Purpose.OwnerEdit, DeleteCheckInSummary(id))

    suspend fun clear() =
        negotiator.request(Requester.CheckIn, Purpose.OwnerEdit, ClearCheckInSummaries)

    /**
     * The previous build kept check-in AI summaries in an unencrypted database. Moves them
     * into the signed-in account's vault (as an Ingest through the Negotiator), then deletes
     * the plaintext file. Safe to call repeatedly; does nothing once the file is gone.
     */
    suspend fun importLegacySummaries() {
        val file = context.getDatabasePath(LEGACY_DB)
        if (!file.exists()) return

        val rows = withContext(Dispatchers.IO) {
            runCatching {
                SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    db.rawQuery("SELECT * FROM checkin_ai_summaries", null).use { c ->
                        buildList {
                            while (c.moveToNext()) {
                                fun s(col: String) = c.getColumnIndex(col).takeIf { it >= 0 }?.let(c::getString)
                                add(
                                    SavedCheckInSummary(
                                        id = s("id") ?: continue,
                                        createdAtEpochMs = c.getLong(c.getColumnIndexOrThrow("created_at")),
                                        summaryText = s("summary_text").orEmpty(),
                                        patientName = s("patient_name").orEmpty(),
                                        clinicName = s("clinic_name").orEmpty(),
                                        appointmentType = s("appointment_type").orEmpty(),
                                        reasonForVisit = s("reason_for_visit").orEmpty(),
                                        deviceLabel = s("device_label"),
                                    ),
                                )
                            }
                        }
                    }
                }
            }.getOrNull()
        } ?: return // unreadable: leave the file alone rather than lose data

        rows.forEach { negotiator.request(Requester.System, Purpose.Ingest, SaveCheckInSummary(it)) }
        withContext(Dispatchers.IO) { context.deleteDatabase(LEGACY_DB) }
    }

    private companion object {
        const val LEGACY_DB = "checkin_history.db"
    }
}
