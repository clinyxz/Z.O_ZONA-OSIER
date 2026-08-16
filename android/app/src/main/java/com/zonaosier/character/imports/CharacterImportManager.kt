/**
 * ZONA-OSIER — Character Import Manager.
 * 
 * Mengorkestrasi alur import karakter dari file eksternal:
 * 1. User memilih file (DocumentPicker)
 * 2. Auto-detect format via CharacterParserRegistry
 * 3. Parse ke CharacterCard
 * 4. Preview (opsional edit)
 * 5. Simpan ke Room DB via CharacterRepository
 * 
 * Flow diagram (dari naskah §11.4):
 *   User tap "Impor" → DocumentPicker → ParserRegistry → Preview → Simpan
 */
package com.zonaosier.character.imports

import android.content.Context
import android.net.Uri
import com.zonaosier.character.store.CharacterRepository
import com.zonaosier.memory.entity.AuditStatus
import com.zonaosier.memory.entity.CharacterCard
import com.zonaosier.security.AuditLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * State alur import untuk UI.
 */
sealed class ImportState {
    /** Menunggu user memilih file. */
    data object Idle : ImportState()
    
    /** Sedang membaca dan mem-parse file. */
    data class Parsing(val fileName: String) : ImportState()
    
    /** Parse berhasil — menampilkan preview sebelum simpan. */
    data class Preview(
        val card: CharacterCard,
        val detectedFormat: String?,
        val warnings: List<String>,
        val avatarPreview: ByteArray?
    ) : ImportState()
    
    /** Sedang menyimpan ke database. */
    data class Saving(val cardName: String) : ImportState()
    
    /** Import berhasil. */
    data class Success(val card: CharacterCard, val format: String?) : ImportState()
    
    /** Import gagal. */
    data class Error(val message: String, val step: String) : ImportState()
}

class CharacterImportManager(
    private val context: Context,
    private val characterRepository: CharacterRepository
) {
    private val _state = kotlinx.coroutines.flow.MutableStateFlow<ImportState>(ImportState.Idle)
    
    /** Flow state import untuk UI. */
    val state: kotlinx.coroutines.flow.StateFlow<ImportState> = _state

    /**
     * Mulai import dari URI.
     * Menghasilkan flow state yang bisa diamati UI.
     */
    fun importFromUri(uri: Uri): Flow<ImportState> = flow {
        val fileName = uri.lastPathSegment ?: "unknown"
        
        // Step 1: Parsing
        emit(ImportState.Parsing(fileName))
        
        val parseResult = withContext(Dispatchers.IO) {
            CharacterParserRegistry.autoDetectAndParse(uri, context)
        }
        
        if (!parseResult.success || parseResult.card == null) {
            emit(ImportState.Error(
                message = parseResult.error ?: "Format tidak dikenali.",
                step = "parse"
            ))
            return@flow
        }
        
        // Step 2: Preview
        emit(ImportState.Preview(
            card = parseResult.card,
            detectedFormat = parseResult.detectedFormat,
            warnings = parseResult.warnings,
            avatarPreview = parseResult.avatarBytes
        ))
        
        // Flow berhenti di Preview — UI akan memanggil confirmSave()
    }.flowOn(Dispatchers.Main)

    /**
     * Konfirmasi simpan karakter yang sudah di-preview.
     * Dipanggil dari UI setelah user tap "Simpan".
     *
     * @param card CharacterCard yang sudah di-preview (bisa diedit user).
     * @param detectedFormat Format yang terdeteksi.
     */
    suspend fun confirmSave(card: CharacterCard, detectedFormat: String?) {
        _state.value = ImportState.Saving(card.name)
        
        try {
            // Cek duplikat nama
            val existing = characterRepository.getAll()
            val duplicate = existing.find { 
                it.name.equals(card.name, ignoreCase = true) && it.id != card.id 
            }
            
            val finalCard = if (duplicate != null) {
                // Tambahkan suffix unik
                card.copy(
                    id = java.util.UUID.randomUUID().toString(),
                    name = "${card.name} (${System.currentTimeMillis() % 10000})"
                )
            } else {
                card
            }

            characterRepository.create(finalCard)
            
            AuditLogger.log(
                toolName = "CharacterImport",
                action = "import_${detectedFormat}",
                status = AuditStatus.APPROVED,
                detail = "Karakter '${finalCard.name}' diimpor dari $detectedFormat"
            )
            
            _state.value = ImportState.Success(finalCard, detectedFormat)
        } catch (e: Exception) {
            AuditLogger.log(
                toolName = "CharacterImport",
                action = "import_error",
                status = AuditStatus.ERROR,
                detail = "Gagal menyimpan: ${e.message}"
            )
            
            _state.value = ImportState.Error(
                message = "Gagal menyimpan ke database: ${e.message}",
                step = "save"
            )
        }
    }

    /**
     * Reset state ke Idle.
     */
    fun reset() {
        _state.value = ImportState.Idle
    }

    /**
     * Batal import.
     */
    fun cancel() {
        _state.value = ImportState.Idle
    }
}