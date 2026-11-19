package com.app.aisheildme.namtran.di

import com.app.aisheildme.namtran.data.remote.api.DocumentApi
import com.app.aisheildme.namtran.data.remote.api.MiscApi
import com.app.aisheildme.namtran.data.remote.api.NotificationApi
import com.app.aisheildme.namtran.data.remote.api.PagesApi
import com.app.aisheildme.namtran.data.remote.api.PatientProfileApi
import com.app.aisheildme.namtran.data.remote.api.RecordApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideDocumentApi(retrofit: Retrofit): DocumentApi =
        retrofit.create(DocumentApi::class.java)

    @Provides
    @Singleton
    fun provideRecordApi(retrofit: Retrofit): RecordApi =
        retrofit.create(RecordApi::class.java)

    @Provides
    @Singleton
    fun provideNotificationApi(retrofit: Retrofit): NotificationApi =
        retrofit.create(NotificationApi::class.java)

    @Provides
    @Singleton
    fun providePagesApi(retrofit: Retrofit): PagesApi =
        retrofit.create(PagesApi::class.java)

    @Provides
    @Singleton
    fun provideMiscApi(retrofit: Retrofit): MiscApi =
        retrofit.create(MiscApi::class.java)

    @Provides
    @Singleton
    fun providePatientProfileApi(retrofit: Retrofit): PatientProfileApi =
        retrofit.create(PatientProfileApi::class.java)
}
