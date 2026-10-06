package io.pixgo.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.SvgDecoder
import io.pixgo.app.data.auth.AuthRepository
import io.pixgo.app.data.catalog.CatalogRepository
import io.pixgo.app.data.channels.ChannelsRepository
import io.pixgo.app.data.contact.ContactRepository
import io.pixgo.app.data.copyright.CopyrightRepository
import io.pixgo.app.data.download.DownloadEngine
import io.pixgo.app.data.i18n.LanguageManager
import io.pixgo.app.data.legal.LegalRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PixGoApp : Application(), ImageLoaderFactory {
    lateinit var authRepository: AuthRepository
        private set
    lateinit var catalogRepository: CatalogRepository
        private set
    lateinit var channelsRepository: ChannelsRepository
        private set
    lateinit var contactRepository: ContactRepository
        private set
    lateinit var copyrightRepository: CopyrightRepository
        private set
    lateinit var legalRepository: LegalRepository
        private set
    lateinit var languageManager: LanguageManager
        private set
    lateinit var downloadEngine: DownloadEngine
        private set

    /** Escopo de app para escritas locais rápidas chamadas a partir da UI (ex.: gate de idioma). */
    val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * ImageLoader global do Coil com os MESMOS timeouts generosos do resto da app.
     * O Coil usa por defeito um OkHttp com 10 s: em rede lenta, posters/logos
     * falhavam e ficavam em branco, enquanto o <img> do web simplesmente esperava.
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient(io.pixgo.app.data.network.NetworkModule.plainHttpClient())
            .components { add(SvgDecoder.Factory()) }
            .build()

    override fun onCreate() {
        super.onCreate()
        authRepository = AuthRepository(this)
        catalogRepository = CatalogRepository(this, authRepository)
        channelsRepository = ChannelsRepository(this, authRepository)
        contactRepository = ContactRepository(this)
        copyrightRepository = CopyrightRepository(this)
        legalRepository = LegalRepository(this)
        languageManager = LanguageManager(this)
        downloadEngine = DownloadEngine(this)
        authRepository.bootstrap()
        // Retomada automática de downloads interrompidos — equivalente do
        // resumeInterruptedDownloads() chamado no mount da página Downloads
        // (downloads-resume.ts). Reobtém o manifesto pelo endpoint real e
        // continua apenas os segmentos que faltam.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { downloadEngine.resumeAll() }
        }
    }
}
