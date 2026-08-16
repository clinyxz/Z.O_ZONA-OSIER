/**
 * ZONA-OSIER — Call Screening Service (Full Implementation).
 * Menyaring panggilan masuk berdasarkan kebijakan agent.
 *
 * Arsitektur:
 * 1. Terima Call.Details
 * 2. Parse nomor telepon
 * 3. Cek kontak lokal (ContentProvider)
 * 4. Terapkan policy (ALLOW_ALL, BLOCK_UNKNOWN, AGENT_DECIDES, CONTACTS_ONLY)
 * 5. Jika AGENT_DECIDES: kirim ke agent dengan timeout 5 detik
 * 6. Respond ke CallScreeningService API
 *
 * ⚠️ Waktu respons dibatasi — harus memanggil respondToCall() secepatnya.
 * Agent consultation bersifat best-effort dengan timeout.
 */
package com.zonaosier.system

import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import android.telecom.CallResponse
import android.telephony.PhoneNumberUtils
import com.zonaosier.security.AuditLogger
import kotlinx.coroutines.*

/**
 * Policy penyaringan panggilan masuk.
 */
enum class CallScreeningPolicy(
    val label: String,
    val description: String
) {
    /** Terima semua panggilan. */
    ALLOW_ALL(
        label = "Terima Semua",
        description = "Semua panggilan diteruskan tanpa penyaringan."
    ),
    /** Hanya kontak yang dikenal. */
    CONTACTS_ONLY(
        label = "Kontak Saja",
        description = "Hanya panggilan dari nomor di kontak yang diteruskan."
    ),
    /** Blokir nomor tidak dikenal. */
    BLOCK_UNKNOWN(
        label = "Blokir Tidak Dikenal",
        description = "Nomor tidak ada di kontak akan diblokir dan dihapus dari log."
    ),
    /** Agent memutuskan (consult LLM). */
    AGENT_DECIDES(
        label = "Agent Memutuskan",
        description = "Z.O akan menganalisis panggilan dan memutuskan apakah meneruskan."
    );

    companion object {
        fun fromString(value: String?): CallScreeningPolicy =
            entries.firstOrNull { it.name == value } ?: ALLOW_ALL
    }
}

/**
 * Hasil screening untuk logging dan UI.
 */
data class CallScreeningResult(
    val phoneNumber: String,
    val callerName: String?,
    val policy: CallScreeningPolicy,
    val action: CallAction,
    val reason: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Aksi yang diambil terhadap panggilan.
 */
enum class CallAction {
    ALLOW,
    REJECT,
    SILENCE_REJECT,
    SKIP_LOG
}

class ZonaCallScreeningService : CallScreeningService() {

    companion object {
        private const val TAG = "ZonaCallScreen"
        private const val PREFS_NAME = "zona_call_screening"
        private const val KEY_POLICY = "screening_policy"
        private const val KEY_BLOCKED_NUMBERS = "blocked_numbers"
        private const val KEY_HISTORY = "call_screening_history"
        private const val MAX_HISTORY = 50

        /** Timeout untuk agent consultation (ms). */
        private const val AGENT_TIMEOUT_MS = 5000L

        @Volatile
        private var instance: ZonaCallScreeningService? = null

        /** Callback untuk agent consultation — di-set dari Activity. */
        @Volatile
        var agentConsultant: (suspend (callerInfo: CallerInfo) -> CallAction)? = null
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var prefs: SharedPreferences
    private val gson = com.google.gson.Gson()

    /** Daftar nomor yang diblokir manual. */
    private val blockedNumbers: MutableSet<String> = mutableSetOf()

    /** History screening untuk UI. */
    private val history: MutableList<CallScreeningResult> = mutableListOf()

    // ==================== Lifecycle ====================

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        loadBlockedNumbers()
        loadHistory()
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    // ==================== Main Screening ====================

    override fun onScreenCall(callDetails: Call.Details) {
        val callerInfo = extractCallerInfo(callDetails)
        val policy = getCurrentPolicy()

        serviceScope.launch {
            val result = screenCall(callerInfo, policy)
            respond(callDetails, result)
        }
    }

    /**
     * Proses screening panggilan berdasarkan policy.
     */
    private suspend fun screenCall(
        callerInfo: CallerInfo,
        policy: CallScreeningPolicy
    ): CallScreeningResult {
        val number = callerInfo.phoneNumber

        // 1. Cek nomor darurat — SELALU terima
        if (isEmergencyNumber(number)) {
            return CallScreeningResult(
                phoneNumber = number,
                callerName = callerInfo.contactName,
                policy = policy,
                action = CallAction.ALLOW,
                reason = "Nomor darurat"
            )
        }

        // 2. Cek daftar blokir manual
        if (number in blockedNumbers) {
            logAndAudit(callerInfo, "BLOCKED_MANUAL")
            return CallScreeningResult(
                phoneNumber = number,
                callerName = callerInfo.contactName,
                policy = policy,
                action = CallAction.SILENCE_REJECT,
                reason = "Nomor ada di daftar blokir manual"
            )
        }

        // 3. Terapkan policy
        return when (policy) {
            CallScreeningPolicy.ALLOW_ALL -> {
                CallScreeningResult(
                    phoneNumber = number,
                    callerName = callerInfo.contactName,
                    policy = policy,
                    action = CallAction.ALLOW,
                    reason = "Policy: Terima semua"
                )
            }

            CallScreeningPolicy.CONTACTS_ONLY -> {
                if (callerInfo.isInContacts) {
                    CallScreeningResult(
                        phoneNumber = number,
                        callerName = callerInfo.contactName,
                        policy = policy,
                        action = CallAction.ALLOW,
                        reason = "Kontak dikenal: ${callerInfo.contactName}"
                    )
                } else {
                    logAndAudit(callerInfo, "BLOCKED_UNKNOWN_CONTACT")
                    CallScreeningResult(
                        phoneNumber = number,
                        callerName = callerInfo.contactName,
                        policy = policy,
                        action = CallAction.SILENCE_REJECT,
                        reason = "Bukan kontak — policy Contacts Only"
                    )
                }
            }

            CallScreeningPolicy.BLOCK_UNKNOWN -> {
                if (callerInfo.isInContacts) {
                    CallScreeningResult(
                        phoneNumber = number,
                        callerName = callerInfo.contactName,
                        policy = policy,
                        action = CallAction.ALLOW,
                        reason = "Kontak dikenal"
                    )
                } else {
                    logAndAudit(callerInfo, "BLOCKED_UNKNOWN")
                    CallScreeningResult(
                        phoneNumber = number,
                        callerName = callerInfo.contactName,
                        policy = policy,
                        action = CallAction.SILENCE_REJECT,
                        reason = "Nomor tidak dikenal — policy Blokir Tidak Dikenal"
                    )
                }
            }

            CallScreeningPolicy.AGENT_DECIDES -> {
                consultAgent(callerInfo, policy)
            }
        }
    }

    /**
     * Konsultasi ke agent dengan timeout.
     * Jika timeout, default: reject jika tidak dikenal, allow jika kontak.
     */
    private suspend fun consultAgent(
        callerInfo: CallerInfo,
        policy: CallScreeningPolicy
    ): CallScreeningResult {
        val consultant = agentConsultant
        if (consultant == null) {
            // Tidak ada agent — fallback ke kontak check
            return CallScreeningResult(
                phoneNumber = callerInfo.phoneNumber,
                callerName = callerInfo.contactName,
                policy = policy,
                action = if (callerInfo.isInContacts) CallAction.ALLOW else CallAction.SILENCE_REJECT,
                reason = "Agent tidak tersedia — fallback ke kontak check"
            )
        }

        return try {
            withTimeout(AGENT_TIMEOUT_MS) {
                val action = consultant(callerInfo)
                CallScreeningResult(
                    phoneNumber = callerInfo.phoneNumber,
                    callerName = callerInfo.contactName,
                    policy = policy,
                    action = action,
                    reason = "Keputusan agent: ${action.name}"
                )
            }
        } catch (_: TimeoutCancellationException) {
            logAndAudit(callerInfo, "AGENT_TIMEOUT")
            CallScreeningResult(
                phoneNumber = callerInfo.phoneNumber,
                callerName = callerInfo.contactName,
                policy = policy,
                action = if (callerInfo.isInContacts) CallAction.ALLOW else CallAction.REJECT,
                reason = "Agent timeout ${AGENT_TIMEOUT_MS}ms — fallback"
            )
        } catch (e: Exception) {
            CallScreeningResult(
                phoneNumber = callerInfo.phoneNumber,
                callerName = callerInfo.contactName,
                policy = policy,
                action = if (callerInfo.isInContacts) CallAction.ALLOW else CallAction.REJECT,
                reason = "Agent error: ${e.message}"
            )
        }
    }

    // ==================== Response Builder ====================

    /**
     * Bangun CallResponse berdasarkan hasil screening.
     */
    private fun respond(callDetails: Call.Details, result: CallScreeningResult) {
        val builder = CallResponse.Builder()

        when (result.action) {
            CallAction.ALLOW -> {
                // Izinkan panggilan, tampilkan notifikasi
                builder.setDisallowCall(false)
                    .setRejectCall(false)
                    .setSilenceCall(false)
                    .setSkipNotification(false)
                    .setSkipCallLog(false)
            }

            CallAction.REJECT -> {
                // Tolak, tapi tetap di log
                builder.setDisallowCall()
                    .setRejectCall()
                    .setSkipNotification(true)
            }

            CallAction.SILENCE_REJECT -> {
                // Senyapkan, tolak, hapus dari log
                builder.setDisallowCall()
                    .setRejectCall()
                    .setSkipCallLog()
                    .setSkipNotification()
            }

            CallAction.SKIP_LOG -> {
                // Izinkan tapi hapus dari log
                builder.setDisallowCall(false)
                    .setSkipCallLog()
            }
        }

        respondToCall(callDetails, builder.build())

        // Simpan ke history
        addToHistory(result)

        // Audit log
        serviceScope.launch {
            AuditLogger.log(
                toolName = "CallScreening",
                action = "SCREEN_${result.action.name}",
                status = if (result.action == CallAction.ALLOW) {
                    com.zonaosier.memory.entity.AuditStatus.APPROVED
                } else {
                    com.zonaosier.memory.entity.AuditStatus.REJECTED
                },
                detail = "${result.phoneNumber} (${result.callerName ?: "unknown"}): ${result.reason}"
            )
        }
    }

    // ==================== Contact Lookup ====================

    /**
     * Informasi panggilan yang sudah di-enrich dengan kontak.
     */
    data class CallerInfo(
        val phoneNumber: String,
        val contactName: String?,
        val isInContacts: Boolean,
        val callType: Int
    )

    /**
     * Ekstrak info pemanggil dari Call.Details.
     */
    private fun extractCallerInfo(details: Call.Details): CallerInfo {
        val handle = details.handle
        val uri = handle?.schemeSpecificPart ?: "unknown"

        // Normalisasi nomor
        val normalizedNumber = PhoneNumberUtils.normalizeNumber(uri)
        val formattedNumber = PhoneNumberUtils.formatNumber(normalizedNumber, getDefaultCountryIso())

        // Cek kontak
        val contactName = lookupContact(normalizedNumber)

        return CallerInfo(
            phoneNumber = formattedNumber ?: normalizedNumber,
            contactName = contactName,
            isInContacts = contactName != null,
            callType = details.callDirection
        )
    }

    /**
     * Cari nama kontak berdasarkan nomor telepon.
     */
    private fun lookupContact(phoneNumber: String): String? {
        val number = phoneNumber.removePrefix("+").replace("[^0-9]", "")
        if (number.length < 5) return null

        val uri = Uri.withAppendedPath(
            android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(number)
        )

        var cursor: Cursor? = null
        return try {
            cursor = contentResolver.query(uri, null, null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME)
                if (nameIndex >= 0) cursor.getString(nameIndex) else null
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            cursor?.close()
        }
    }

    /**
     * Cek apakah nomor adalah nomor darurat.
     */
    private fun isEmergencyNumber(number: String): Boolean {
        return PhoneNumberUtils.isEmergencyNumber(number) ||
                number in listOf("112", "911", "110", "113", "115", "118", "119", "123", "021-110")
    }

    private fun getDefaultCountryIso(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            android.telecom.TelecomManager.from(this).simCountryIso
                ?.uppercase()
                ?: "ID"
        } else {
            @Suppress("DEPRECATION")
            android.telephony.TelephonyManager(this).simCountryIso
                ?.uppercase()
                ?: "ID"
        }
    }

    // ==================== Policy & Block Management ====================

    /**
     * Ambil policy saat ini.
     */
    fun getCurrentPolicy(): CallScreeningPolicy {
        val value = prefs.getString(KEY_POLICY, null)
        return CallScreeningPolicy.fromString(value)
    }

    /**
     * Set policy screening.
     */
    fun setPolicy(policy: CallScreeningPolicy) {
        prefs.edit().putString(KEY_POLICY, policy.name).apply()
    }

    /**
     * Tambah nomor ke daftar blokir.
     */
    fun blockNumber(phoneNumber: String) {
        val normalized = phoneNumber.removePrefix("+").replace("[^0-9]", "")
        blockedNumbers.add(normalized)
        saveBlockedNumbers()
    }

    /**
     * Hapus nomor dari daftar blokir.
     */
    fun unblockNumber(phoneNumber: String) {
        val normalized = phoneNumber.removePrefix("+").replace("[^0-9]", "")
        blockedNumbers.remove(normalized)
        saveBlockedNumbers()
    }

    /**
     * Ambil semua nomor yang diblokir.
     */
    fun getBlockedNumbers(): Set<String> = blockedNumbers.toSet()

    /**
     * Ambil history screening.
     */
    fun getHistory(count: Int = 20): List<CallScreeningResult> {
        return history.takeLast(count).reversed()
    }

    /**
     * Cek apakah Call Screening role aktif.
     */
    fun isCallScreeningActive(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val roleManager = getSystemService(Context.ROLE_SERVICE) as? android.app.role.RoleManager
            ?: return false
        return roleManager.isRoleHeld(android.app.role.RoleManager.ROLE_CALL_SCREENING)
    }

    // ==================== Persistence ====================

    private fun loadBlockedNumbers() {
        val json = prefs.getString(KEY_BLOCKED_NUMBERS, null) ?: return
        try {
            val type = object : com.google.gson.reflect.TypeToken<Set<String>>() {}.type
            val set: Set<String> = gson.fromJson(json, type)
            blockedNumbers.clear()
            blockedNumbers.addAll(set)
        } catch (_: Exception) { }
    }

    private fun saveBlockedNumbers() {
        prefs.edit().putString(KEY_BLOCKED_NUMBERS, gson.toJson(blockedNumbers)).apply()
    }

    private fun loadHistory() {
        val json = prefs.getString(KEY_HISTORY, null) ?: return
        try {
            val type = object : com.google.gson.reflect.TypeToken<List<CallScreeningResult>>() {}.type
            val list: List<CallScreeningResult> = gson.fromJson(json, type)
            history.clear()
            history.addAll(list)
        } catch (_: Exception) { }
    }

    private fun addToHistory(result: CallScreeningResult) {
        history.add(result)
        if (history.size > MAX_HISTORY) {
            val trimmed = history.takeLast(MAX_HISTORY)
            history.clear()
            history.addAll(trimmed)
        }
        prefs.edit()
            .putString(KEY_HISTORY, gson.toJson(history))
            .apply()
    }

    // ==================== Audit ====================

    private fun logAndAudit(callerInfo: CallerInfo, action: String) {
        serviceScope.launch {
            AuditLogger.log(
                toolName = "CallScreening",
                action = action,
                status = com.zonaosier.memory.entity.AuditStatus.REJECTED,
                detail = "${callerInfo.phoneNumber} (${callerInfo.contactName ?: "unknown"})"
            )
        }
    }
}
