plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    // ObjectBox — vector DB on-device, HNSW indexing sejak v4.0
    id("io.objectbox")
}

// ============================================================
// API Keys dari local.properties — TIDAK pernah hardcoded di source.
// Copy local.properties.template → local.properties dan isi key Anda.
// ============================================================
val groqApiKey: String by lazy {
    val props = rootProject.file("local.properties")
    if (props.exists()) {
        val p = java.util.Properties().apply { props.inputStream().use { load(it) } }
        p.getProperty("GROQ_API_KEY", "")
    } else ""
}

val openrouterApiKey: String by lazy {
    val props = rootProject.file("local.properties")
    if (props.exists()) {
        val p = java.util.Properties().apply { props.inputStream().use { load(it) } }
        p.getProperty("OPENROUTER_API_KEY", "")
    } else ""
}

val googleAiApiKey: String by lazy {
    val props = rootProject.file("local.properties")
    if (props.exists()) {
        val p = java.util.Properties().apply { props.inputStream().use { load(it) } }
        p.getProperty("GOOGLE_AI_API_KEY", "")
    } else ""
}

val deepseekApiKey: String by lazy {
    val props = rootProject.file("local.properties")
    if (props.exists()) {
        val p = java.util.Properties().apply { props.inputStream().use { load(it) } }
        p.getProperty("DEEPSEEK_API_KEY", "")
    } else ""
}

val cerebrasApiKey: String by lazy {
    val props = rootProject.file("local.properties")
    if (props.exists()) {
        val p = java.util.Properties().apply { props.inputStream().use { load(it) } }
        p.getProperty("CEREBRAS_API_KEY", "")
    } else ""
}

val sambaNovaApiKey: String by lazy {
    val props = rootProject.file("local.properties")
    if (props.exists()) {
        val p = java.util.Properties().apply { props.inputStream().use { load(it) } }
        p.getProperty("SAMBA_NOVA_API_KEY", "")
    } else ""
}

val novitaApiKey: String by lazy {
    val props = rootProject.file("local.properties")
    if (props.exists()) {
        val p = java.util.Properties().apply { props.inputStream().use { load(it) } }
        p.getProperty("NOVITA_API_KEY", "")
    } else ""
}

val mistralApiKey: String by lazy {
    val props = rootProject.file("local.properties")
    if (props.exists()) {
        val p = java.util.Properties().apply { props.inputStream().use { load(it) } }
        p.getProperty("MISTRAL_API_KEY", "")
    } else ""
}

val cloudflareApiKey: String by lazy {
    val props = rootProject.file("local.properties")
    if (props.exists()) {
        val p = java.util.Properties().apply { props.inputStream().use { load(it) } }
        p.getProperty("CLOUDFLARE_API_KEY", "")
    } else ""
}

val cloudflareAccountId: String by lazy {
    val props = rootProject.file("local.properties")
    if (props.exists()) {
        val p = java.util.Properties().apply { props.inputStream().use { load(it) } }
        p.getProperty("CLOUDFLARE_ACCOUNT_ID", "")
    } else ""
}

val cohereApiKey: String by lazy {
    val props = rootProject.file("local.properties")
    if (props.exists()) {
        val p = java.util.Properties().apply { props.inputStream().use { load(it) } }
        p.getProperty("COHERE_API_KEY", "")
    } else ""
}

val huggingfaceApiKey: String by lazy {
    val props = rootProject.file("local.properties")
    if (props.exists()) {
        val p = java.util.Properties().apply { props.inputStream().use { load(it) } }
        p.getProperty("HUGGINGFACE_API_KEY", "")
    } else ""
}

val nvidiaApiKey: String by lazy {
    val props = rootProject.file("local.properties")
    if (props.exists()) {
        val p = java.util.Properties().apply { props.inputStream().use { load(it) } }
        p.getProperty("NVIDIA_API_KEY", "")
    } else ""
}

val elevenlabsApiKey: String by lazy {
    val props = rootProject.file("local.properties")
    if (props.exists()) {
        val p = java.util.Properties().apply { props.inputStream().use { load(it) } }
        p.getProperty("ELEVENLABS_API_KEY", "")
    } else ""
}

val minimaxApiKey: String by lazy {
    val props = rootProject.file("local.properties")
    if (props.exists()) {
        val p = java.util.Properties().apply { props.inputStream().use { load(it) } }
        p.getProperty("MINIMAX_API_KEY", "")
    } else ""
}

val picovoiceAccessKey: String by lazy {
    val props = rootProject.file("local.properties")
    if (props.exists()) {
        val p = java.util.Properties().apply { props.inputStream().use { load(it) } }
        p.getProperty("PICOVOICE_ACCESS_KEY", "")
    } else ""
}

val githubSyncToken: String by lazy {
    val props = rootProject.file("local.properties")
    if (props.exists()) {
        val p = java.util.Properties().apply { props.inputStream().use { load(it) } }
        p.getProperty("GITHUB_SYNC_TOKEN", "")
    } else ""
}

val githubSyncRepo: String by lazy {
    val props = rootProject.file("local.properties")
    if (props.exists()) {
        val p = java.util.Properties().apply { props.inputStream().use { load(it) } }
        p.getProperty("GITHUB_SYNC_REPO", "")
    } else ""
}

android {
    namespace = "com.zonaosier"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.zonaosier"
        minSdk = 28  // API 28+ wajib untuk BiometricPrompt
        targetSdk = 35
        versionCode = 1
        versionName = "5.0.0"

        // Sembunyikan API keys ke BuildConfig
        buildConfigField("String", "GROQ_API_KEY", "\"$groqApiKey\"")
        buildConfigField("String", "OPENROUTER_API_KEY", "\"$openrouterApiKey\"")
        buildConfigField("String", "GOOGLE_AI_API_KEY", "\"$googleAiApiKey\"")
        buildConfigField("String", "DEEPSEEK_API_KEY", "\"$deepseekApiKey\"")
        buildConfigField("String", "CEREBRAS_API_KEY", "\"$cerebrasApiKey\"")
        buildConfigField("String", "SAMBA_NOVA_API_KEY", "\"$sambaNovaApiKey\"")
        buildConfigField("String", "NOVITA_API_KEY", "\"$novitaApiKey\"")
        buildConfigField("String", "MISTRAL_API_KEY", "\"$mistralApiKey\"")
        buildConfigField("String", "CLOUDFLARE_API_KEY", "\"$cloudflareApiKey\"")
        buildConfigField("String", "CLOUDFLARE_ACCOUNT_ID", "\"$cloudflareAccountId\"")
        buildConfigField("String", "COHERE_API_KEY", "\"$cohereApiKey\"")
        buildConfigField("String", "HUGGINGFACE_API_KEY", "\"$huggingfaceApiKey\"")
        buildConfigField("String", "NVIDIA_API_KEY", "\"$nvidiaApiKey\"")
        buildConfigField("String", "ELEVENLABS_API_KEY", "\"$elevenlabsApiKey\"")
        buildConfigField("String", "MINIMAX_API_KEY", "\"$minimaxApiKey\"")
        buildConfigField("String", "PICOVOICE_ACCESS_KEY", "\"$picovoiceAccessKey\"")
        buildConfigField("String", "GITHUB_SYNC_TOKEN", "\"$githubSyncToken\"")
        buildConfigField("String", "GITHUB_SYNC_REPO", "\"$githubSyncRepo\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.serialization.ExperimentalSerializationApi"
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Sumber untuk AAR manual (sherpa-onnx)
    // Jika file AAR ada di libs/, otomatis di-include
}

// Room schema export directory — must be OUTSIDE android {} block
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // ============================================================
    // CORE
    // ============================================================
    implementation(libs.core.ktx)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.guava)
    implementation(libs.serialization.json)
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.compose)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.datastore.preferences)
    implementation(libs.crypto)

    // ============================================================
    // JETPACK COMPOSE (BOM-managed)
    // ============================================================
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.foundation)
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    // ============================================================
    // ROOM DATABASE (KSP, bukan KAPT)
    // ============================================================
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // ============================================================
    // OBJECTBOX — Vector DB on-device, Apache 2.0, HNSW sejak v4.0
    // ============================================================
    implementation(libs.objectbox.android)

    // ============================================================
    // SHIZUKU — MIT License, Maven Central
    // Sideload dari GitHub/F-Droid untuk Android 16 QPR1
    // ============================================================
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    // ============================================================
    // VOICE PIPELINE
    // ============================================================
    // VAD — Silero DNN on-device via JitPack
    implementation(libs.android.vad)
    // STT on-device
    implementation(libs.vosk.android)
    // Voice-print — Picovoice Eagle (3 users/bulan free)
    implementation(libs.picovoice.eagle)

    // ============================================================
    // BIOMETRIC
    // ============================================================
    implementation(libs.biometric)

    // ============================================================
    // HEALTH CONNECT
    // ============================================================
    implementation(libs.health.connect)

    // ============================================================
    // CAMERA + ML KIT
    // ============================================================
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.mlkit.face)

    // ============================================================
    // NETWORK (OkHttp + Retrofit)
    // ============================================================
    implementation(libs.okhttp.core)
    implementation(libs.okhttp.logging)
    implementation(libs.retrofit.core)
    implementation(libs.retrofit.kotlinx)

    // ============================================================
    // GIT (GitHub-as-Cloud)
    // ============================================================
    implementation(libs.jgit)

    // ============================================================
    // JSON (Room TypeConverter)
    // ============================================================
    implementation(libs.gson)

    // ============================================================
    // SHERPA-ONNX — AAR MANUAL IMPORT
    // Untuk build: download AAR dari https://github.com/k2-fsa/sherpa-onnx
    // dan letakkan di android/app/libs/
    // Kemudian uncomment baris di bawah:
    // implementation(files("libs/sherpa-onnx-android.aar"))
    //
    // Model TTS SupertonicTTS 3 multi-bahasa (~99M param, 31 bahasa)
    // di assets/sherpa-onnx-id/ (unduh manual, ~100MB)
    // ============================================================

    // ============================================================
    // TESTING
    // ============================================================
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
    androidTestImplementation(libs.junit.ext)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
