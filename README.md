# ZONA-OSIER

**Android OS Layer Agentik** — Satu aplikasi yang mengambil posisi asisten
sistem di Android, sepenuhnya di bawah kendali pengguna.

Data tidak pergi ke cloud kecuali secara eksplisit diizinkan.

---

## Prinsip Inti

| Prinsip | Penjelasan |
|---------|------------|
| **Local-first** | Kontak, pesan, foto, dan data personality tetap di perangkat. |
| **BYO-Model** | Bawa model sendiri: API gratis, inferensi lokal, atau hybrid. |
| **Agentic** | AI membaca layar, mengeksekusi perintah, dan bertindak proaktif. |
| **Voice-native** | Antarmuka utama adalah suara, dengan TTS ekspresif dan STT on-device. |
| **Security-first** | Eksekusi shell dan tool-call dikunci lewat policy enforcement + biometrik. |
| **God Mode** | Kombinasi Shizuku + Termux + Accessibility = akses mendekati root tanpa rooting. |
| **GitHub-as-Cloud** | Sinkronisasi memori ke repo privat milik pengguna, terenkripsi end-to-end. |
| **Character-Native** | Setiap interaksi dalam konteks Karakter yang switchable real-time. |

---

## Persyaratan Build

- **Android Studio** Hedgehog (2023.1.1) atau lebih baru
- **JDK** 17
- **Gradle** 8.11.1 (wrapper sudah disertakan)
- **Android SDK** compileSdk 35, minSdk 28
- **Git** untuk clone

---

## Setup & Build

### 1. Clone Repository

```bash
git clone https://github.com/YOUR_USERNAME/zona-osier.git
cd zona-osier
```

### 2. Konfigurasi API Keys

```bash
cp local.properties.template local.properties
# Edit local.properties, isi minimal satu API key
```

API key dibaca dari `local.properties` dan di-inject ke `BuildConfig`.
**Jangan pernah commit `local.properties` ke Git.**

**Provider Gratis (Tanpa Batas/Tinggi):**

| Provider | Free Tier | Catatan |
|----------|-----------|--------|
| Groq | Unlimited requests, rate limited | LPU inference, 300-800 tok/s |
| OpenRouter | Model-model `:free` | BYOK: gratis routing fee, bukan inference |
| Google AI Studio | 250 RPD (konservatif) | Gemini 2.0 Flash |
| Mistral | Free tier tersedia | Mistral Small 3.1 / Devstral |
| Cloudflare AI Workers | Free tier tersedia | Workers AI |
| Cohere | Trial tersedia | Command R+ |
| HuggingFace | Free Inference API | Model open-source |
| NVIDIA NIM | Free tier tersedia | NIM catalog |
| Novita AI | Free tier tersedia | LLM + image |

**Provider Trial Terbatas (Wajib Track Expiry):**

| Provider | Trial | Wajib Expiry Tracking |
|----------|-------|---------------------|
| DeepSeek | 5 juta token / 30 hari | Ya |
| Cerebras | $5 credit / 30 hari, RPM ~5 | Ya |
| SambaNova | $5 credit / 30 hari | Ya |

**Provider TTS:**

| Provider | Free Tier | Catatan |
|----------|-----------|--------|
| ElevenLabs | 10.000 karakter/bulan | Flash v2.5, ~75ms inference |
| MiniMax | **BERBAYAR** | Voice cloning premium, cek kredit sebelum routing |
| sherpa-onnx (lokal) | Gratis, offline | SupertonicTTS 3, 31 bahasa |

### 3. Download Model Suara (Opsional, untuk fitur suara lokal)

Model-model ini terlalu besar untuk Git (>50MB). Unduh manual:

```bash
# STT Vosk Bahasa Indonesia (~50MB)
# Download dari: https://alphacephei.com/vosk/models
# Cari model "id-ID" ukuran small
# Ekstrak ke: android/app/src/main/assets/vosk-model-id/

# TTS sherpa-onnx Bahasa Indonesia
# Download SupertonicTTS 3 multi-bahasa dari:
#   https://k2-fsa.github.io/sherpa/onnx/tts/all/Indonesian/
# (~99M parameter, 31 bahasa, pilih --lang id)
# Ekstrak ke: android/app/src/main/assets/sherpa-onnx-id/

# (Opsional) sherpa-onnx AAR
# Download dari: https://github.com/k2-fsa/sherpa-onnx/releases
# Letakkan: android/app/libs/sherpa-onnx-android.aar
# Kemudian uncomment baris implementation(files(...)) di build.gradle.kts
```

### 4. Build

```bash
# Debug APK
./gradlew assembleDebug

# Release APK (perlu signing config)
./gradlew assembleRelease

# Install ke device tersambung
./gradlew installDebug

# Run unit tests
./gradlew test

# Run instrumented tests (perlu device/emulator)
./gradlew connectedAndroidTest
```

---

## Struktur Repository

```
zona-osier/
├── android/app/src/main/
│   ├── java/com/zonaosier/
│   │   ├── ZonaOsierApp.kt          # Application class, Room DB, notification channels
│   │   ├── brain/                    # SystemThinker, VoiceAssistant, DualBrainOrchestrator, CharacterOrchestrator
│   │   ├── agent/                    # AgentLoop, ToolRegistry, FilteredToolRegistry, AgentTypes
│   │   ├── character/
│   │   │   ├── imports/              # CharacterParserRegistry, PngCharacterCardParser, JsonCharacterParser, CharacterAiZipParser
│   │   │   ├── mapper/               # CharacterCardMapper, AvatarHelper
│   │   │   └── store/                # CharacterRepository
│   │   ├── voice/                    # VADGatekeeper, STTRouter, VoiceRouter, TTS engines, AudioPipeline
│   │   ├── security/                 # ShellSecurityPolicy, BiometricToolGate, FreezeAgent, AuditLogger
│   │   ├── tools/                    # SendSmsTool, ShizukuTool, TermuxExecTool, ...
│   │   ├── memory/
│   │   │   ├── entity/               # CharacterCard, ConversationEntry, AuditEntry, ModelBinding, ToolPolicy
│   │   │   └── dao/                  # CharacterDao, ConversationDao, AuditDao, Converters
│   │   ├── system/                   # ShizukuController, TermuxExecutor, AccessibilityService, CallScreening
│   │   ├── model/                    # ModelTierSelector, GpuBackendDetector, MultiProviderLLMRouter
│   │   │   └── provider/             # Implementasi provider (GroqClient, OpenRouterClient, dll)
│   │   ├── governor/                 # BatteryThermalGovernor, ProviderQuotaRouter
│   │   └── ui/
│   │       ├── theme/                # ZonaPalette, SkinEngine, Theme
│   │       ├── components/           # BinduButton, NadiBar, AgentTransparencyCard
│   │       ├── screens/              # HomeScreen, ChatScreen, VoiceScreen, SettingsScreen
│   │       └── navigation/           # NavGraph
│   └── assets/
│       ├── vosk-model-id/            # (unduh manual, ~50MB)
│       ├── sherpa-onnx-id/           # (unduh manual, ~100MB)
│       └── models/                   # GGUF model files (unduh manual)
├── docs/                             # Dokumentasi arsitektur
├── termux-scripts/                   # Script untuk Termux
├── voice-training/                   # (OFF-DEVICE) pelatihan suara
├── gradle/
│   ├── wrapper/                      # Gradle wrapper
│   └── libs.versions.toml            # Version catalog
├── build.gradle.kts                  # Root build config
├── settings.gradle.kts               # Project settings
├── local.properties.template         # Template API keys
├── .gitignore
├── LICENSE                           # MIT
└── README.md
```

---

## Sistem Karakter & Pilihan AI

### Format Impor Karakter Didukung

| Format | Ekstensi | Spesifikasi |
|--------|----------|-------------|
| **PNG Character Card V2** | `.png` | tEXt chunk `chara`, base64 JSON |
| **PNG Character Card V3** | `.png` | tEXt chunk `ccv3`, raw UTF-8 JSON atau zTXt compressed |
| **SillyTavern V2** | `.json` | `{"spec": "chara_card_v2", "data": {...}}` |
| **ChatterUI** | `.json` | Flat format: `char_name`, `char_persona`, ... |
| **Character.AI** | `.zip` | ZIP export berisi `character.json` |
| **Generic JSON** | `.json` | Minimal: `name` + `description` |
| **Manual (Z.O)** | - | Form manual di aplikasi, semua field Z.O |

### Pilihan Model AI Per Karakter

Setiap karakter bisa memilih model **berbeda** dari **15+ pilihan**:

**Model Lokal (On-Device, Offline Penuh):**

| Tier | Ukuran | RAM Min | Backend | Contoh Model |
|------|--------|---------|---------|-------------|
| **Adhi** | 7B-13B | 6GB+ | CPU I8MM / OpenCL Adreno | Phi-4, Gemma 2 9B |
| **Madya** | 3B-4B | 4GB+ | CPU I8MM | Gemma 2 2B, Phi-3 mini |
| **Alit** | 1B-2B | 2GB+ | CPU | TinyLlama 1.1B, SmollM |
| **Auto** | otomatis | deteksi runtime | deteksi runtime | Pilih tier terbaik untuk device |

**Model Cloud (10+ Provider):**

| Provider | Model Free | Kecepatan | Catatan |
|----------|-----------|-----------|--------|
| Groq | llama-3.3-70b | 300-800 tok/s | Tercepat, untuk Voice Assistant |
| OpenRouter | multi-model `:free` | bervariasi | Live discovery, BYOK |
| Google AI Studio | gemini-2.0-flash | cepat | 250 RPD konservatif |
| DeepSeek | deepseek-chat | cepat | Trial 30 hari |
| Mistral | mistral-small | cepat | Devstral untuk coding |
| Cerebras | llama-3.3-70b | sangat cepat | Trial $5, RPM ~5 |
| SambaNova | Meta-Llama-3.3-70B | cepat | Trial $5 |
| Cloudflare | multi-model | bervariasi | Workers AI |
| Cohere | command-r+ | sedang | Trial |
| HuggingFace | open-source | bervariasi | Inference API gratis |
| NVIDIA NIM | multi-model | cepat | NIM catalog |
| Novita AI | multi-model | cepat | LLM + image |

---

## Lisensi

MIT License — *"Zero Order National Building."*

---

> Disusun oleh ZONA-OSIER TEAM, Agustus 2026
