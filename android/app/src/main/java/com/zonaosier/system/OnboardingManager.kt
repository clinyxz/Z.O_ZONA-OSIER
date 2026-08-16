/**
 * ZONA-OSIER — Onboarding Manager.
 * Mengorkestrasi semua pengecekan prasyarat dan permission request
 * yang diperlukan Z.O untuk berfungsi penuh.
 *
 * Langkah Onboarding (berurutan):
 * 1. Permission dasar (RECORD_AUDIO, POST_NOTIFICATIONS, dll)
 * 2. Termux F-Droid + Termux:API
 * 3. Shizuku (sideload)
 * 4. Accessibility Service
 * 5. Notification Listener (bukan runtime permission — buka Settings)
 * 6. Call Screening Role (API 29+)
 * 7. SMS permission
 * 8. Biometric enrollment
 *
 * Setiap langkah memiliki status: PENDING, DONE, OPTIONAL, SKIP.
 * Onboarding selesai ketika semua langkah REQUIRED = DONE.
 */
package com.zonaosier.system

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.zonaosier.security.FreezeAgent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Status satu langkah onboarding.
 */
enum class OnboardingStepStatus {
    /** Belum dilakukan. */
    PENDING,
    /** Sudah selesai. */
    DONE,
    /** Opsional — tidak wajib. */
    OPTIONAL,
    /** Tidak tersedia di device ini (misal: API < 29). */
    SKIP,
    /** Sedang dicek/diproses. */
    CHECKING
}

/**
 * Satu langkah onboarding.
 */
data class OnboardingStep(
    val id: String,
    val title: String,
    val description: String,
    val isRequired: Boolean,
    val status: OnboardingStepStatus = OnboardingStepStatus.PENDING,
    val actionLabel: String = "Periksa",
    val helpUrl: String? = null
)

/**
 * Hasil full check onboarding.
 */
data class OnboardingState(
    val steps: List<OnboardingStep>,
    val allRequiredDone: Boolean,
    val systemReady: Boolean,
    val voiceReady: Boolean,
    val totalRequired: Int,
    val doneRequired: Int
) {
    val progressPercent: Int
        get() = if (totalRequired == 0) 100 else (doneRequired * 100 / totalRequired)
}

class OnboardingManager(private val context: Context) {

    companion object {
        /** Langkah onboarding yang diperlukan. */
        private const val STEP_AUDIO = "record_audio"
        private const val STEP_NOTIFICATION_PERM = "post_notifications"
        private const val STEP_TERMUX = "termux"
        private const val STEP_TERMUX_API = "termux_api"
        private const val STEP_SHIZUKU = "shizuku"
        private const val STEP_ACCESSIBILITY = "accessibility"
        private const val STEP_NOTIF_LISTENER = "notif_listener"
        private const val STEP_CALL_SCREENING = "call_screening"
        private const val STEP_SMS = "sms"
        private const val STEP_BIOMETRIC = "biometric"
        private const val STEP_CALENDAR = "calendar"
        private const val STEP_LOCATION = "location"
        private const val STEP_CAMERA = "camera"

        /** URL panduan. */
        const val SHIZUKU_GUIDE_URL = "https://github.com/RikkaApps/Shizuku"
        const val TERMUX_GUIDE_URL = "https://f-droid.org/packages/com.termux/"
        const val TERMUX_API_GUIDE_URL = "https://f-droid.org/packages/com.termux.api/"
    }

    private val _state = MutableStateFlow(createInitialState())
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    private val termuxChecker = TermuxApiChecker(context)

    // ==================== Full Check ====================

    /**
     * Jalankan semua pengecekan dan update state.
     */
    fun runFullCheck() {
        val steps = mutableListOf<OnboardingStep>()

        // 1. RECORD_AUDIO — REQUIRED
        steps.add(
            OnboardingStep(
                id = STEP_AUDIO,
                title = "Izin Mikrofon",
                description = "Diperlukan untuk VAD, STT, dan voice-print.",
                isRequired = true,
                status = checkPermissionStatus(Manifest.permission.RECORD_AUDIO),
                actionLabel = "Berikan Izin"
            )
        )

        // 2. POST_NOTIFICATIONS — REQUIRED (API 33+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            steps.add(
                OnboardingStep(
                    id = STEP_NOTIFICATION_PERM,
                    title = "Izin Notifikasi",
                    description = "Untuk menampilkan notifikasi Z.O dan status agent.",
                    isRequired = true,
                    status = checkPermissionStatus(Manifest.permission.POST_NOTIFICATIONS),
                    actionLabel = "Berikan Izin"
                )
            )
        }

        // 3. Termux — REQUIRED (atau Shizuku)
        steps.add(
            OnboardingStep(
                id = STEP_TERMUX,
                title = "Termux (F-Droid)",
                description = "Build F-Droid, bukan Play Store. Untuk eksekusi command userland.",
                isRequired = true,
                status = if (isPackageInstalled(TermuxExecutor.TERMUX_PACKAGE)) {
                    OnboardingStepStatus.DONE
                } else {
                    OnboardingStepStatus.PENDING
                },
                actionLabel = "Buka F-Droid",
                helpUrl = TERMUX_GUIDE_URL
            )
        )

        // 4. Termux:API — REQUIRED
        steps.add(
            OnboardingStep(
                id = STEP_TERMUX_API,
                title = "Termux:API (F-Droid)",
                description = "Add-on Termux untuk 40+ command Android API.",
                isRequired = true,
                status = if (isPackageInstalled(TermuxExecutor.TERMUX_API_PACKAGE)) {
                    OnboardingStepStatus.DONE
                } else {
                    OnboardingStepStatus.PENDING
                },
                actionLabel = "Buka F-Droid",
                helpUrl = TERMUX_API_GUIDE_URL
            )
        )

        // 5. Shizuku — OPTIONAL tapi sangat direkomendasikan
        steps.add(
            OnboardingStep(
                id = STEP_SHIZUKU,
                title = "Shizuku (God Mode)",
                description = "Sideload dari GitHub. Privilese ADB tanpa root.",
                isRequired = false,
                status = if (checkShizuku()) {
                    OnboardingStepStatus.DONE
                } else {
                    OnboardingStepStatus.OPTIONAL
                },
                actionLabel = "Buka GitHub",
                helpUrl = SHIZUKU_GUIDE_URL
            )
        )

        // 6. Accessibility Service — REQUIRED
        steps.add(
            OnboardingStep(
                id = STEP_ACCESSIBILITY,
                title = "Accessibility Service",
                description = "Untuk membaca layar dan gesture injection.",
                isRequired = true,
                status = if (ZonaAccessibilityService.isServiceActive()) {
                    OnboardingStepStatus.DONE
                } else {
                    OnboardingStepStatus.PENDING
                },
                actionLabel = "Buka Pengaturan"
            )
        )

        // 7. Notification Listener — REQUIRED
        steps.add(
            OnboardingStep(
                id = STEP_NOTIF_LISTENER,
                title = "Akses Notifikasi",
                description = "Z.O perlu membaca notifikasi untuk merespons proaktif. " +
                        "Tidak bisa via dialog biasa — buka Settings.",
                isRequired = true,
                status = if (checkNotificationAccess()) {
                    OnboardingStepStatus.DONE
                } else {
                    OnboardingStepStatus.PENDING
                },
                actionLabel = "Buka Pengaturan"
            )
        )

        // 8. Call Screening — OPTIONAL (API 29+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            steps.add(
                OnboardingStep(
                    id = STEP_CALL_SCREENING,
                    title = "Call Screening",
                    description = "Z.O bisa menyaring panggilan masuk.",
                    isRequired = false,
                    status = if (checkCallScreening()) {
                        OnboardingStepStatus.DONE
                    } else {
                        OnboardingStepStatus.OPTIONAL
                    },
                    actionLabel = "Minta Role"
                )
            )
        }

        // 9. SMS — OPTIONAL (dangerous permission)
        steps.add(
            OnboardingStep(
                id = STEP_SMS,
                title = "Izin SMS",
                description = "Diperlukan untuk kirim/baca SMS. Dangerous permission.",
                isRequired = false,
                status = if (checkPermissionStatus(Manifest.permission.SEND_SMS) == OnboardingStepStatus.DONE) {
                    OnboardingStepStatus.DONE
                } else {
                    OnboardingStepStatus.OPTIONAL
                },
                actionLabel = "Berikan Izin"
            )
        )

        // 10. Biometric — OPTIONAL
        steps.add(
            OnboardingStep(
                id = STEP_BIOMETRIC,
                title = "Biometrik",
                description = "Sidik jari/face untuk verifikasi tool destruktif.",
                isRequired = false,
                status = OnboardingStepStatus.OPTIONAL,
                actionLabel = "Siapkan"
            )
        )

        // 11. Calendar — OPTIONAL
        steps.add(
            OnboardingStep(
                id = STEP_CALENDAR,
                title = "Izin Kalender",
                description = "Untuk membuat event dan pengingat.",
                isRequired = false,
                status = if (checkPermissionStatus(Manifest.permission.READ_CALENDAR) == OnboardingStepStatus.DONE) {
                    OnboardingStepStatus.DONE
                } else {
                    OnboardingStepStatus.OPTIONAL
                },
                actionLabel = "Berikan Izin"
            )
        )

        // 12. Location — OPTIONAL
        steps.add(
            OnboardingStep(
                id = STEP_LOCATION,
                title = "Izin Lokasi",
                description = "Untuk cuaca, lokasi, dan konteks spasial.",
                isRequired = false,
                status = if (checkPermissionStatus(Manifest.permission.ACCESS_FINE_LOCATION) == OnboardingStepStatus.DONE) {
                    OnboardingStepStatus.DONE
                } else {
                    OnboardingStepStatus.OPTIONAL
                },
                actionLabel = "Berikan Izin"
            )
        )

        // 13. Camera — OPTIONAL
        steps.add(
            OnboardingStep(
                id = STEP_CAMERA,
                title = "Izin Kamera",
                description = "Untuk face verification dan capture.",
                isRequired = false,
                status = if (checkPermissionStatus(Manifest.permission.CAMERA) == OnboardingStepStatus.DONE) {
                    OnboardingStepStatus.DONE
                } else {
                    OnboardingStepStatus.OPTIONAL
                },
                actionLabel = "Berikan Izin"
            )
        )

        // Hitung statistik
        val requiredSteps = steps.filter { it.isRequired }
        val doneRequired = requiredSteps.count { it.status == OnboardingStepStatus.DONE }

        // Cek readiness
        val termuxDone = steps.find { it.id == STEP_TERMUX }?.status == OnboardingStepStatus.DONE
        val audioDone = steps.find { it.id == STEP_AUDIO }?.status == OnboardingStepStatus.DONE

        val newState = OnboardingState(
            steps = steps,
            allRequiredDone = doneRequired == requiredSteps.size,
            systemReady = termuxDone || checkShizuku(),
            voiceReady = termuxDone && audioDone,
            totalRequired = requiredSteps.size,
            doneRequired = doneRequired
        )

        _state.value = newState
    }

    // ==================== Action Handlers ====================

    /**
     * Buka pengaturan untuk langkah tertentu.
     * Dipanggil dari UI saat user tap action button.
     *
     * @return true jika intent berhasil di-launch.
     */
    fun openStepSettings(stepId: String): Boolean {
        return when (stepId) {
            STEP_ACCESSIBILITY -> {
                openAccessibilitySettings()
            }
            STEP_NOTIF_LISTENER -> {
                termuxChecker.openNotificationAccessSettings()
                true
            }
            STEP_CALL_SCREENING -> {
                termuxChecker.requestCallScreeningRole(0)
                true
            }
            STEP_SHIZUKU -> {
                openUrl(SHIZUKU_GUIDE_URL)
            }
            STEP_TERMUX -> {
                openFdroidPage("com.termux")
            }
            STEP_TERMUX_API -> {
                openFdroidPage("com.termux.api")
            }
            else -> {
                termuxChecker.openAppSettings()
            }
        }
    }

    /**
     * Update status satu langkah (dipanggil setelah user kembali dari Settings).
     */
    fun refreshStep(stepId: String) {
        val currentSteps = _state.value.steps.toMutableList()
        val index = currentSteps.indexOfFirst { it.id == stepId }
        if (index < 0) return

        val step = currentSteps[index]
        val newStatus = when (stepId) {
            STEP_AUDIO -> checkPermissionStatus(Manifest.permission.RECORD_AUDIO)
            STEP_NOTIFICATION_PERM -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                checkPermissionStatus(Manifest.permission.POST_NOTIFICATIONS)
            } else OnboardingStepStatus.DONE
            STEP_SMS -> checkPermissionStatus(Manifest.permission.SEND_SMS)
            STEP_CALENDAR -> checkPermissionStatus(Manifest.permission.READ_CALENDAR)
            STEP_LOCATION -> checkPermissionStatus(Manifest.permission.ACCESS_FINE_LOCATION)
            STEP_CAMERA -> checkPermissionStatus(Manifest.permission.CAMERA)
            STEP_TERMUX -> if (isPackageInstalled(TermuxExecutor.TERMUX_PACKAGE)) {
                OnboardingStepStatus.DONE
            } else OnboardingStepStatus.PENDING
            STEP_TERMUX_API -> if (isPackageInstalled(TermuxExecutor.TERMUX_API_PACKAGE)) {
                OnboardingStepStatus.DONE
            } else OnboardingStepStatus.PENDING
            STEP_SHIZUKU -> if (checkShizuku()) OnboardingStepStatus.DONE else OnboardingStepStatus.OPTIONAL
            STEP_ACCESSIBILITY -> if (ZonaAccessibilityService.isServiceActive()) {
                OnboardingStepStatus.DONE
            } else OnboardingStepStatus.PENDING
            STEP_NOTIF_LISTENER -> if (checkNotificationAccess()) {
                OnboardingStepStatus.DONE
            } else OnboardingStepStatus.PENDING
            STEP_CALL_SCREENING -> if (checkCallScreening()) {
                OnboardingStepStatus.DONE
            } else OnboardingStepStatus.OPTIONAL
            else -> step.status
        }

        currentSteps[index] = step.copy(status = newStatus)

        val requiredSteps = currentSteps.filter { it.isRequired }
        val doneRequired = requiredSteps.count { it.status == OnboardingStepStatus.DONE }

        _state.value = _state.value.copy(
            steps = currentSteps,
            allRequiredDone = doneRequired == requiredSteps.size,
            doneRequired = doneRequired
        )
    }

    /**
     * Refresh semua langkah.
     */
    fun refreshAll() = runFullCheck()

    // ==================== Permission Helpers ====================

    private fun checkPermissionStatus(permission: String): OnboardingStepStatus {
        return if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            OnboardingStepStatus.DONE
        } else {
            OnboardingStepStatus.PENDING
        }
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
        }
    }

    private fun checkShizuku(): Boolean {
        return try {
            rikka.shizuku.Shizuku.pingBinder()
            true
        } catch (_: Exception) {
            false
        }
        }
    }

    private fun checkNotificationAccess(): Boolean {
        val flat = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: ""
        return flat.contains(context.packageName)
    }

    private fun checkCallScreening(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val roleManager = context.getSystemService(Context.ROLE_SERVICE) as? RoleManager
            ?: return false
        return roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
    }

    // ==================== Intent Launchers ====================

    private fun openAccessibilitySettings(): Boolean {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun openUrl(url: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun openFdroidPage(packageName: String): Boolean {
        val uri = "https://f-droid.org/packages/$packageName/"
        return openUrl(uri)
    }

    // ==================== Initial State ====================

    private fun createInitialState(): OnboardingState {
        return OnboardingState(
            steps = emptyList(),
            allRequiredDone = false,
            systemReady = false,
            voiceReady = false,
            totalRequired = 0,
            doneRequired = 0
        )
    }
}
