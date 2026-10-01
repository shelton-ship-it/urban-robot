package io.pixgo.app

import android.app.Application
import io.pixgo.app.data.auth.AuthRepository
import io.pixgo.app.data.catalog.CatalogRepository
import io.pixgo.app.data.channels.ChannelsRepository
import io.pixgo.app.data.contact.ContactRepository
import io.pixgo.app.data.i18n.LanguageManager
import io.pixgo.app.data.legal.LegalRepository

class PixGoApp : Application() {
    lateinit var authRepository: AuthRepository
        private set
    lateinit var catalogRepository: CatalogRepository
        private set
    lateinit var channelsRepository: ChannelsRepository
        private set
    lateinit var contactRepository: ContactRepository
        private set
    lateinit var legalRepository: LegalRepository
        private set
    lateinit var languageManager: LanguageManager
        private set

    override fun onCreate() {
        super.onCreate()
        authRepository = AuthRepository(this)
        catalogRepository = CatalogRepository(this, authRepository)
        channelsRepository = ChannelsRepository(this, authRepository)
        contactRepository = ContactRepository(this)
        legalRepository = LegalRepository(this)
        languageManager = LanguageManager(this)
        authRepository.bootstrap()
    }
}
