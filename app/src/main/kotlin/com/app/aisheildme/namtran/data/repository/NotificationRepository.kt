package com.app.aisheildme.namtran.data.repository

import com.app.aisheildme.namtran.data.remote.api.NotificationApi
import com.app.aisheildme.namtran.data.remote.dto.ToggleNotificationRequest
import com.app.aisheildme.namtran.data.remote.dto.toDomain
import com.app.aisheildme.namtran.domain.model.AppNotification
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationRepository @Inject constructor(
    private val api: NotificationApi,
) {
    suspend fun getNotifications(page: Int = 1, limit: Int = 20): List<AppNotification> =
        api.getNotifications(page, limit).data.orEmpty().map { it.toDomain() }

    suspend fun markRead(id: Int): Boolean = api.markRead(id).code == 200

    suspend fun approveRequest(id: Int): String = api.approveRequest(id).message

    suspend fun rejectRequest(id: Int): String = api.rejectRequest(id).message

    suspend fun toggleNotification(body: ToggleNotificationRequest): String =
        api.toggleNotification(body).message
}
