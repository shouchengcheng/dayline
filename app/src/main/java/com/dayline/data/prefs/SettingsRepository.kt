package com.dayline.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "dayline_settings")

data class SettingsState(
    val beneficialLabel: String = DEFAULT_BENEFICIAL,
    val badLabel: String = DEFAULT_BAD,
    /** 该键为 true 说明用户改过名字，导入时不覆盖 */
    val beneficialCustom: Boolean = false,
    val badCustom: Boolean = false,
    val trashRetentionDays: Int = DEFAULT_RETENTION_DAYS
) {
    companion object {
        const val DEFAULT_BENEFICIAL = "有益"
        const val DEFAULT_BAD = "糟糕"
        const val DEFAULT_RETENTION_DAYS = 30
    }
}

class SettingsRepository(private val context: Context) {

    private object Keys {
        val BENEFICIAL_LABEL = stringPreferencesKey("beneficial_label")
        val BAD_LABEL = stringPreferencesKey("bad_label")
        val BENEFICIAL_CUSTOM = booleanPreferencesKey("beneficial_custom")
        val BAD_CUSTOM = booleanPreferencesKey("bad_custom")
        val RETENTION = stringPreferencesKey("trash_retention_days")
    }

    val state: Flow<SettingsState> = context.dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { p ->
            SettingsState(
                beneficialLabel = p[Keys.BENEFICIAL_LABEL] ?: SettingsState.DEFAULT_BENEFICIAL,
                badLabel = p[Keys.BAD_LABEL] ?: SettingsState.DEFAULT_BAD,
                beneficialCustom = p[Keys.BENEFICIAL_CUSTOM] ?: false,
                badCustom = p[Keys.BAD_CUSTOM] ?: false,
                trashRetentionDays = p[Keys.RETENTION]?.toIntOrNull()
                    ?: SettingsState.DEFAULT_RETENTION_DAYS
            )
        }

    suspend fun snapshot(): SettingsState = state.first()

    suspend fun setBeneficialLabel(value: String) {
        val trimmed = value.trim().ifBlank { SettingsState.DEFAULT_BENEFICIAL }
        context.dataStore.edit {
            it[Keys.BENEFICIAL_LABEL] = trimmed
            it[Keys.BENEFICIAL_CUSTOM] = trimmed != SettingsState.DEFAULT_BENEFICIAL
        }
    }

    suspend fun setBadLabel(value: String) {
        val trimmed = value.trim().ifBlank { SettingsState.DEFAULT_BAD }
        context.dataStore.edit {
            it[Keys.BAD_LABEL] = trimmed
            it[Keys.BAD_CUSTOM] = trimmed != SettingsState.DEFAULT_BAD
        }
    }

    suspend fun setRetentionDays(days: Int) {
        context.dataStore.edit { it[Keys.RETENTION] = days.coerceIn(0, 365).toString() }
    }

    /**
     * 导入合并设置：保留本地；只有本地该项没被自定义过，才采用导入值（且导入值本身是自定义过的）。
     */
    suspend fun mergeFromImport(imported: SettingsSnapshot) {
        val local = snapshot()
        context.dataStore.edit { p ->
            if (!local.beneficialCustom && imported.beneficialCustom) {
                p[Keys.BENEFICIAL_LABEL] = imported.beneficialLabel
                p[Keys.BENEFICIAL_CUSTOM] = true
            }
            if (!local.badCustom && imported.badCustom) {
                p[Keys.BAD_LABEL] = imported.badLabel
                p[Keys.BAD_CUSTOM] = true
            }
        }
    }

    data class SettingsSnapshot(
        val beneficialLabel: String,
        val badLabel: String,
        val beneficialCustom: Boolean,
        val badCustom: Boolean
    ) {
        fun toCurrent(): SettingsSnapshot = this
    }
}
