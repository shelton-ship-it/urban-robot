plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "io.pixgo.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.pixgo.app"
        minSdk = 24
        targetSdk = 34
        // versionCode/versionName ficam por definir até termos o keystore/
        // pipeline de release reais (ver .github/workflows/android-build.yml) —
        // não inventados aqui.
        versionCode = 1
        versionName = "0.1.0-dev"

        // Client ID "Web application" do Google (o MESMO GOOGLE_CLIENT_ID do backend, que
        // valida o `audience` do ID token). Vem de -PGOOGLE_WEB_CLIENT_ID ou da variável de
        // ambiente do CI. Vazio => o botão "Continuar com Google" não aparece (como no web).
        val googleWebClientId: String =
            (project.findProperty("GOOGLE_WEB_CLIENT_ID") as String?)
                ?: System.getenv("GOOGLE_WEB_CLIENT_ID")
                ?: ""
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"$googleWebClientId\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Persistência local (token/refresh/perfil ativo/me-cache — equivalente
    // ao localStorage usado em store/auth.ts do pixel)
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Rede — dois backends (api.pixgo.qzz.io / pixel.pixgo.qzz.io) partilhando
    // o mesmo OkHttpClient/CookieJar, tal como o browser partilha cookies
    // entre subdomínios .pixgo.qzz.io
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    // Conversor kotlinx.serialization para Retrofit — não existe sob o
    // groupId com.squareup.retrofit2 (só gson/moshi/etc oficiais); o
    // conversor da comunidade, mantido pelo próprio autor do Retrofit, é
    // este:
    implementation("com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter:1.0.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Login com Google nativo (Credential Manager) — ver ui/auth/GoogleSignIn.kt
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    implementation("io.coil-kt:coil-compose:2.6.0")
    // logo.svg (public/logo.svg do frontend_web) é desenhado tal e qual, sem conversão
    implementation("io.coil-kt:coil-svg:2.6.0")

    // Player — Media3/ExoPlayer com HLS. O DataSource customizado
    // (BinDecryptDataSource) é que faz o trabalho equivalente ao
    // BinLoader do hls.js original: intercepta .bin, decifra ChaCha20.
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")
    implementation("androidx.media3:media3-datasource:1.4.1")

    testImplementation("junit:junit:4.13.2")
}
