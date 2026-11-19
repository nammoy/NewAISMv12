package com.app.aisheildme.namtran

import android.app.Application
import com.app.aisheildme.namtran.data.repository.DocumentRepository
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class AiShieldMeApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Decrypted copies only exist while a viewer or share target uses them.
        Thread { DocumentRepository.wipeDecryptedCopies(this) }.start()
    }
}
