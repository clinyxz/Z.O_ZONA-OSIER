/**
 * ZONA-OSIER — Room Type Converters.
 * Mengkonversi tipe kompleks (enum, data class) ke String/JSON untuk Room storage.
 */
package com.zonaosier.memory.dao

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.zonaosier.memory.entity.*

class Converters(private val gson: Gson = Gson()) {

    // ==================== ModelMode ====================
    @TypeConverter
    fun fromModelMode(mode: ModelMode): String = mode.name

    @TypeConverter
    fun toModelMode(name: String): ModelMode =
        try { ModelMode.valueOf(name) } catch (_: IllegalArgumentException) { ModelMode.AUTO_TIER }

    // ==================== ContextStrategy ====================
    @TypeConverter
    fun fromContextStrategy(strategy: ContextStrategy): String = strategy.name

    @TypeConverter
    fun toContextStrategy(name: String): ContextStrategy =
        try { ContextStrategy.valueOf(name) } catch (_: IllegalArgumentException) { ContextStrategy.STANDARD }

    // ==================== MemoryScope ====================
    @TypeConverter
    fun fromMemoryScope(scope: MemoryScope): String = scope.name

    @TypeConverter
    fun toMemoryScope(name: String): MemoryScope =
        try { MemoryScope.valueOf(name) } catch (_: IllegalArgumentException) { MemoryScope.ISOLATED }

    // ==================== ToolPolicy (JSON) ====================
    @TypeConverter
    fun fromToolPolicy(policy: ToolPolicy): String = gson.toJson(policy)

    @TypeConverter
    fun toToolPolicy(json: String): ToolPolicy =
        try { gson.fromJson(json, ToolPolicy::class.java) }
        catch (_: Exception) { ToolPolicy.DEFAULT }

    // ==================== ModelBinding (JSON) ====================
    @TypeConverter
    fun fromModelBinding(binding: ModelBinding): String = gson.toJson(binding)

    @TypeConverter
    fun toModelBinding(json: String): ModelBinding =
        try { gson.fromJson(json, ModelBinding::class.java) }
        catch (_: Exception) { ModelBinding.DEFAULT }

    // ==================== String List (untuk field tambahan) ====================
    private val stringListType = object : TypeToken<List<String>>() {}.type

    @TypeConverter
    fun.fromStringList(list: List<String>): String = gson.toJson(list)

    @TypeConverter
    fun toStringList(json: String): List<String> =
        try { gson.fromJson(json, stringListType) }
        catch (_: Exception) { emptyList() }
}