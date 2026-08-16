package com.zonaosier.security  
  
import ai.picovoice.eagle.*  
import com.zonaosier.BuildConfig  
import kotlinx.coroutines.withTimeoutOrNull  
import java.util.UUID  
  
class VoicePrintPreCheck(  
    private val eagle: Eagle? = null,  
    private val eagleProfiler: EagleProfiler? = null  
) {  
    private val enrolledProfiles = mutableMapOf<String, EagleProfile>()  
  
    fun enroll(pcmData: FloatArray): EnrollResult {  
        val profiler = eagleProfiler  
            ?: return EnrollResult.Error("EagleProfiler tidak diinisialisasi. Cek PICOVOICE_ACCESS_KEY di local.properties.")  
        return try {  
            profiler.enroll(pcmData.toPcmShort())  
            val profile = profiler.export()  
            val speakerId = UUID.randomUUID().toString()  
            enrolledProfiles[speakerId] = profile  
            EnrollResult.Success(speakerId, enrolledProfiles.size)  
        } catch (e: EagleException) {  
            EnrollResult.Error("Enroll gagal: ${e.message}")  
        } catch (e: Exception) {  
            EnrollResult.Error("Error: ${e.message}")  
        }  
    }  
  
    suspend fun verify(pcmData: FloatArray): PreCheckResult {  
        val eagleInstance = eagle ?: return PreCheckResult.FallbackToBiometric("Eagle tidak tersedia")  
        if (enrolledProfiles.isEmpty()) return PreCheckResult.FallbackToBiometric("Tidak ada profil terdaftar")  
  
        return withTimeoutOrNull(TIMEOUT_MS) {  
            val scores = eagleInstance.process(pcmData.toPcmShort())  
            if (scores == null || scores.isEmpty()) {  
                return@withTimeoutOrNull PreCheckResult.FallbackToBiometric("Eagle.process() kosong")  
            }  
            var bestScore = 0f  
            var bestSpeakerId: String? = null  
            enrolledProfiles.entries.forEachIndexed { index, (speakerId, _) ->  
                if (index < scores.size && scores[index] > bestScore) {  
                    bestScore = scores[index]  
                    bestSpeakerId = speakerId  
                }  
            }  
            when {  
                bestScore >= THRESHOLD_MATCH -> PreCheckResult.Match(bestScore, bestSpeakerId!!)  
                bestScore >= THRESHOLD_TOLERANCE -> PreCheckResult.LowConfidence(bestScore, bestSpeakerId!!)  
                else -> PreCheckResult.FallbackToBiometric("Score $bestScore di bawah threshold $THRESHOLD_TOLERANCE")  
            }  
        } ?: PreCheckResult.FallbackToBiometric("Timeout ${TIMEOUT_MS}ms tercapai")  
    }  
  
    fun isAvailable(): Boolean = eagle != null && BuildConfig.PICOVOICE_ACCESS_KEY.isNotBlank()  
  
    fun profileCount(): Int = enrolledProfiles.size  
  
    private fun FloatArray.toPcmShort(): ShortArray =  
        ShortArray(size) { i -> (this[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort() }  
  
    sealed class EnrollResult {  
        data class Success(val speakerId: String, val totalProfiles: Int) : EnrollResult()  
        data class Error(val message: String) : EnrollResult()  
    }  
  
    sealed class PreCheckResult {  
        data class Match(val score: Float, val speakerId: String) : PreCheckResult()  
        data class LowConfidence(val score: Float, val speakerId: String) : PreCheckResult()  
        data class FallbackToBiometric(val reason: String) : PreCheckResult()  
        val isMatch: Boolean get() = this is Match  
        val isFallback: Boolean get() = this is FallbackToBiometric  
    }  
  
    companion object {  
        const val TIMEOUT_MS = 300L  
        const val THRESHOLD_MATCH = 0.65f  
        const val THRESHOLD_TOLERANCE = 0.45f  
    }  
}
