/**
 * ZONA-OSIER — Application class.
 * Inisialisasi Room database dan notification channels.
 * ObjectBox di-inisialisasi saat pertama kali dibutuhkan (lazy).
 *
 * PENTING: API keys dibaca dari BuildConfig yang di-populate dari local.properties.
 * Jangan pernah hardcode API key di source code.
 */
package com.zonaosier

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.zonaosier.memory.CommitCacheDao
import com.zonaosier.memory.CommitCacheEntry
import com.zonaosier.memory.dao.*
import com.zonaosier.memory.entity.*
import io.objectbox.BoxStore

open class ZonaOsierApp : Application() {

    lateinit var database: ZonaDatabase
        private set

    private var _boxStore: BoxStore? = null
    val boxStore: BoxStore
        get() = _boxStore ?: initObjectBox().also { _boxStore = it }

    override fun onCreate() {
        super.onCreate()
        instance = this
        initRoom()
        createNotificationChannels()
    }

    private fun initRoom() {
        database = Room.databaseBuilder(
            applicationContext,
            ZonaDatabase::class.java,
            "zona_osier.db"
        )
            .addTypeConverter(Converters())
            .fallbackToDestructiveMigration()
            .build()
    }

    private fun initObjectBox(): BoxStore {
        return MyObjectBox.builder()
            .androidContext(applicationContext)
            .build()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channels = listOf(
                NotificationChannel(
                    CHANNEL_VOICE,
                    "Layanan Suara",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = "Pipeline suara berjalan di latar" },
                NotificationChannel(
                    CHANNEL_SYNC,
                    "Sinkronisasi",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = "Sinkronisasi memori ke GitHub" },
                NotificationChannel(
                    CHANNEL_SECURITY,
                    "Keamanan",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply { description = "Peringatan keamanan" },
                NotificationChannel(
                    CHANNEL_AGENT,
                    "Aktivitas Agent",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply { description = "Notifikasi dari AI Agent" }
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannels(channels)
        }
    }

    companion object {
        lateinit var instance: ZonaOsierApp
            private set

        const val CHANNEL_VOICE = "zona_voice"
        const val CHANNEL_SYNC = "zona_sync"
        const val CHANNEL_SECURITY = "zona_security"
        const val CHANNEL_AGENT = "zona_agent"
    }
}

/**
 * Room Database — deklarasikan semua entity dan DAO.
 * TypeConverters dideklarasikan di level database.
 */
@Database(
    entities = [
        CharacterCard::class,
        ConversationEntry::class,
        AuditEntry::class,
        CommitCacheEntry::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class ZonaDatabase : RoomDatabase() {
    abstract fun characterDao(): CharacterDao
    abstract fun conversationDao(): ConversationDao
    abstract fun auditDao(): AuditDao
    abstract fun commitCacheDao(): CommitCacheDao
}
