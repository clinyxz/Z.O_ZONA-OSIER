/**
 * ZONA-OSIER — Shell Security Policy (Layer 1-2).
 * 
 * Defense-in-depth 6 lapisan:
 *   Layer 1: Input Sanitization — hapus ;|&$ (di AgentLoop.sanitizeToolCall)
 *   Layer 2: Argument-level Policy — validasi ini (ShellSecurityPolicy)
 *   Layer 3: Character Tool Filter — FilteredToolRegistry
 *   Layer 4: Voice-Print Pre-Check — Picovoice Eagle
 *   Layer 5: Biometric Prompt — BiometricToolGate
 *   Layer 6: Audit Log — AuditLogger
 *
 * PRINSIP: default DENY, whitelist eksplisit, tanpa shell interpreter.
 * Semua proses eksternal WAJIB ProcessBuilder dengan argumen array.
 */
package com.zonaosier.security

import com.zonaosier.memory.entity.AuditStatus

object ShellSecurityPolicy {

    /**
     * Binary yang diizinkan tanpa argumen berbahaya.
     * Ini adalah perintah read-only atau administratif level sistem.
     */
    private val ALLOWED_BINARIES = setOf(
        "ls", "cat", "cp", "mv", "mkdir", "rm",      // file ops
        "pm", "am", "svc", "settings", "appops",     // Android system
        "getprop", "dumpsys", "input", "wm",          // system introspection
        "grep", "find", "head", "tail", "wc",       // text utils
        "date", "uptime", "id", "whoami", "uname"   // system info
    )

    /** Flag yang tidak boleh muncul di argumen manapun. */
    private val DANGEROUS_FLAGS = setOf(
        "-c", "-e", "--eval", "--execute", "-E", "-i"
    )

    /**
     * Interpreter yang diizinkan HANYA untuk menjalankan file skrip
     * dari direktori yang sudah di-allowlist.
     * Tidak boleh menerima inline code (-c, -e, stdin).
     */
    private val INTERPRETERS = setOf(
        "python", "python3", "node", "sh", "bash", "zsh"
    )

    /**
     * Direktori skrip yang diizinkan untuk interpreter.
     * User harus me-review skrip di direktori ini sebelum dieksekusi.
     */
    private val ALLOWED_SCRIPT_DIRS = listOf(
        "/data/data/com.termux/files/home/scripts/"
    )

    /**
     * Argumen path yang berbahaya (path traversal, dll).
     */
    private val DANGEROUS_PATH_PATTERNS = listOf(
        "..", "/proc/", "/sys/", "/dev/", "/system/", "/vendor/"
    )

    /**
     * Validasi perintah sebelum eksekusi.
     * Mengembalikan [ValidationResult] yang menentukan apakah
     * perintah boleh dijalankan.
     *
     * @param command List argumen perintah (sudah di-sanitize ;|&$ oleh AgentLoop)
     * @return [ValidationResult.Approved] atau [ValidationResult.Rejected] dengan alasan
     */
    fun validate(command: List<String>): ValidationResult {
        if (command.isEmpty()) return ValidationResult.Rejected("Perintah kosong")

        val binary = command[0]

        // Cek binary di allowlist
        if (binary !in ALLOWED_BINARIES) {
            if (binary in INTERPRETERS) {
                return validateInterpreterCommand(command)
            }
            return ValidationResult.Rejected(
                "Binary tidak diizinkan: $binary. " +
                "Hanya perintah berikut: ${ALLOWED_BINARIES.joinToString(", ")}"
            )
        }

        // Cek argumen berbahaya
        for (arg in command.drop(1)) {
            if (arg in DANGEROUS_FLAGS) {
                return ValidationResult.Rejected("Flag berbahaya terdeteksi: $arg")
            }
        }

        // Cek path berbahaya di semua argumen
        for (arg in command.drop(1)) {
            for (pattern in DANGEROUS_PATH_PATTERNS) {
                if (arg.contains(pattern)) {
                    return ValidationResult.Rejected("Path berbahaya terdeteksi: $arg")
                }
            }
        }

        return ValidationResult.Approved
    }

    /**
     * Validasi khusus untuk interpreter (python, node, sh, dll).
     * Hanya izinkan menjalankan file skrip dari ALLOWED_SCRIPT_DIRS.
     * Menolak -c, -e, --eval, dan semua inline code execution.
     */
    private fun validateInterpreterCommand(command: List<String>): ValidationResult {
        val interpreter = command[0]

        // Tolak inline execution flags
        if (command.any { it in DANGEROUS_FLAGS }) {
            return ValidationResult.Rejected(
                "Eksekusi inline dilarang untuk $interpreter. " +
                "Gunakan file skrip dari direktori yang diizinkan."
            )
        }

        // Interpreter butuh minimal 2 argumen: nama interpreter + path skrip
        if (command.size < 2) {
            return ValidationResult.Rejected(
                "$interpreter memerlukan path skrip sebagai argumen"
            )
        }

        val scriptPath = command[1]

        // Tolak jika argumen pertama setelah interpreter adalah flag
        if (scriptPath.startsWith("-")) {
            return ValidationResult.Rejected(
                "Flag interpreter tidak diizinkan: $scriptPath"
            )
        }

        // Validasi path skrip harus di dalam allowlist
        if (ALLOWED_SCRIPT_DIRS.none { scriptPath.startsWith(it) }) {
            return ValidationResult.Rejected(
                "Path skrip tidak diizinkan: $scriptPath. " +
                "Skrip harus di: ${ALLOWED_SCRIPT_DIRS.joinToString(", ")}"
            )
        }

        return ValidationResult.Approved
    }

    /**
     * Eksekusi perintah yang sudah tervalidasi.
     * Menggunakan ProcessBuilder dengan argumen array (BUKAN string tunggal).
     *
     * @return Output stdout atau pesan error.
     */
    fun executeValidated(command: List<String>): ExecutionResult {
        return try {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()

            if (exitCode == 0) {
                ExecutionResult.Success(output)
            } else {
                ExecutionResult.Error("Exit code $exitCode: $output")
            }
        } catch (e: Exception) {
            ExecutionResult.Error("Gagal menjalankan: ${e.message}")
        }
    }

    // ==================== Result Types ====================

    sealed class ValidationResult {
        /** Perintah diizinkan. */
        data object Approved : ValidationResult()

        /** Perintah ditolak dengan alasan. */
        data class Rejected(val reason: String) : ValidationResult()

        val isApproved: Boolean get() = this is Approved
        val rejectionReason: String? get() = (this as? Rejected)?.reason
    }

    sealed class ExecutionResult {
        data class Success(val output: String) : ExecutionResult()
        data class Error(val message: String) : ExecutionResult()

        val isSuccess: Boolean get() = this is Success
    }
}
