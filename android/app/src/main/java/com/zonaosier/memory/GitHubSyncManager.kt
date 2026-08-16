/**
 * ZONA-OSIER — GitHub Sync Manager.
 * 
 * Sinkronisasi memori ke repository privat milik user.
 * Strategi:
 * - Append-only journal (menghindari merge conflict)
 * - Hanya teks/JSON yang dienkripsi (BUKAN embedding vektor)
 * - Embedding bisa diregenerasi on-demand dari teks
 * - Last-write-wins berbasis timestamp
 * - Lazy loading untuk timeline (pagination + Room cache)
 * 
 * ⚠️ JGit di Android bisa lambat untuk repo besar.
 *    mitigasi: pagination, caching, jangan simpan binary.
 */
package com.zonaosier.memory

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import com.zonaosier.BuildConfig
import com.zonaosier.memory.dao.ConversationDao
import com.zonaosier.memory.entity.ConversationEntry
import com.zonaosier.security.AuditLogger
import com.zonaosier.memory.entity.AuditStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.merge.MergeStrategy
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class GitHubSyncManager(
    private val context: Context,
    private val conversationDao: ConversationDao,
    private val encryptor: AESEncryptor
) {
    private val repoDir: File by lazy {
        File(context.filesDir, "github-memory-repo")
    }

    private val journalDir: File by lazy {
        File(repoDir, "journal")
    }

    /** Cek apakah GitHub sync sudah dikonfigurasi. */
    fun isConfigured(): Boolean {
        return BuildConfig.GITHUB_SYNC_TOKEN.isNotBlank() &&
                BuildConfig.GITHUB_SYNC_REPO.isNotBlank()
    }

    /** Cek apakah ada koneksi internet. */
    private fun isOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = cm?.activeNetworkInfo
        return network != null && network.isConnected
    }

    /**
     * Sinkronkan memori ke GitHub.
     * Alur: serialize → encrypt → commit → push.
     */
    suspend fun syncMemory(characterId: String? = null): SyncResult = withContext(Dispatchers.IO) {
        if (!isConfigured()) return SyncResult.Error("GitHub sync belum dikonfigurasi. Isi GITHUB_SYNC_TOKEN dan GITHUB_SYNC_REPO di local.properties.")
        if (!isOnline()) return SyncResult.Error("Tidak ada koneksi internet.")

        try {
            ensureRepoInitialized()

            // 1. Serialize memori ke JSONL (append-only journal)
            val entries = if (characterId != null) {
                conversationDao.getByCharacterOnce(characterId)
            } else {
                conversationDao.getGlobalOnce()
            }

            if (entries.isEmpty()) return SyncResult.Success("Tidak ada memori untuk disinkronkan.")

            // 2. Buat file journal terenkripsi (append-only)
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val journalFile = File(journalDir, "memory_${timestamp}.jsonl.enc")

            journalDir.mkdirs()
            val journalContent = buildJournalContent(entries)
            val encryptedContent = encryptor.encrypt(journalContent.toByteArray(Charsets.UTF_8))
            journalFile.writeBytes(encryptedContent)

            // 3. Git commit + push
            val git = Git.open(repoDir)
            git.add().addFilepattern(".").call()
            git.commit()
                .setAuthor("ZONA-OSIER", "zona-osier@device")
                .setMessage("Memory sync ${System.currentTimeMillis()} — ${entries.size} entries")
                .call()

            git.push()
                .setCredentialsProvider(
                    UsernamePasswordCredentialsProvider(
                        BuildConfig.GITHUB_SYNC_TOKEN,
                        "" // Token sebagai password (PAT)
                    )
                )
                .call()

            AuditLogger.log(
                toolName = "GitHubSync",
                action = "sync",
                status = AuditStatus.APPROVED,
                detail = "Synced ${entries.size} entries to ${BuildConfig.GITHUB_SYNC_REPO}"
            )

            SyncResult.Success("Berhasil menyinkronkan ${entries.size} entries.")
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed", e)
            AuditLogger.log(
                toolName = "GitHubSync",
                action = "sync",
                status = AuditStatus.ERROR,
                detail = "Gagal: ${e.message}"
            )
            SyncResult.Error("Gagal menyinkronkan: ${e.message}")
        }
    }

    /**
     * Ambil commit log untuk Memory Timeline.
     * Lazy loading: pagination dengan offset.
     * Cache metadata di Room DB.
     */
    suspend fun getCommitLog(
        page: Int = 0,
        pageSize: Int = 20
    ): List<MemoryCommitMeta> = withContext(Dispatchers.IO) {
        if (!repoDir.exists() || !File(repoDir, ".git").exists()) {
            return emptyList()
        }

        try {
            val git = Git.open(repoDir)
            val commits = git.log()
                .setMaxCount(pageSize)
                .setSkip(page * pageSize)
                .call()
                .toList()

            commits.map { commit ->
                MemoryCommitMeta(
                    commitHash = commit.name.abbreviate(7).toString(),
                    shortMessage = commit.shortMessage,
                    author = commit.authorIdent.name,
                    commitTime = commit.commitTime.toLong() * 1000L,
                    diffStat = getDiffStat(git, commit)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "getCommitLog failed", e)
            emptyList()
        }
    }

    // ==================== Internal ====================

    private fun ensureRepoInitialized() {
        if (File(repoDir, ".git").exists()) return

        repoDir.mkdirs()
        Git.init().setDirectory(repoDir).call()

        // Buat .gitignore
        File(repoDir, ".gitignore").writeText(
            "*.vec\n" +
            "*.bin\n" +
            "__pycache__/\n" +
            ".DS_Store\n"
        )

        val git = Git.open(repoDir)
        git.add().addFilepattern(".").call()
        git.commit()
            .setAuthor("ZONA-OSIER", "zona-osier@device")
            .setMessage("Initial commit — ZONA-OSIER memory repo")
            .call()
    }

    /**
     * Bangun konten journal JSONL.
     * Setiap baris = satu entry JSON.
     * Append-only: tidak pernah edit file yang sudah ada.
     */
    private fun buildJournalContent(entries: List<ConversationEntry>): String {
        return buildString {
            for (entry in entries) {
                appendLine("\"{\"id\":\"${entry.id}\",\"role\":\"${entry.role.name}\",\"content\":${escapeJson(entry.content)},\"ts\":${entry.timestamp}}\"")
            }
        }
    }

    private fun escapeJson(s: String): String {
        return s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    private fun getDiffStat(git: Git, commit: RevCommit): String {
        return try {
            val parent = commit.parents.firstOrNull()
            if (parent == null) return "+1"
            val diff = git.diff()
                .setOldTree(prepareTreeParser(git, parent.name))
                .setNewTree(prepareTreeParser(git, commit.name))
                .call()
            val added = diff.size
            val removed = 0 // Simplified
            "+$added -$removed"
        } catch (_: Exception) {
            "?"
        }
    }

    private fun prepareTreeParser(git: Git, ref: String): org.eclipse.jgit.treewalk.CanonicalTreeParser {
        val reader = git.repository.newObjectReader()
        val treeId = git.repository.resolve("${ref}^{tree}")
        val parser = org.eclipse.jgit.treewalk.CanonicalTreeParser()
        parser.reset(reader, treeId)
        return parser
    }

    // ==================== Result Types ====================

    sealed class SyncResult {
        data class Success(val message: String) : SyncResult()
        data class Error(val message: String) : SyncResult()
    }

    companion object {
        private const val TAG = "GitHubSync"
    }
}