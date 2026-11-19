package com.app.aisheildme.namtran.navigation

import androidx.navigation.NavController
import com.app.aisheildme.namtran.domain.model.User

object AuthFlow {

    fun routeAfterAuth(navController: NavController, user: User, @Suppress("UNUSED_PARAMETER") email: String) {
        when {
            !user.isPinSet -> navController.navigateAndClear(CreateVaultPinRoute)
            else -> navController.navigateAndClear(DashboardRoute)
        }
    }
}

fun NavController.navigateAndClear(destination: Destination) {
    navigate(destination) {
        popUpTo(graph.id) { inclusive = true }
        launchSingleTop = true
    }
}

fun NavController.navigateReplacing(destination: Destination) {
    val current = currentBackStackEntry?.destination?.id
    navigate(destination) {
        if (current != null) popUpTo(current) { inclusive = true }
        launchSingleTop = true
    }
}
