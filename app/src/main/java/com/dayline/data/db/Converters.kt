package com.dayline.data.db

import androidx.room.TypeConverter
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * List<String> <-> JSON 字符串。Room 不支持直接存 List，统一走这里。
 * 解析失败返回空列表而不是抛异常：坏数据不该让整条记录读不出来。
 */
class Converters {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val listSerializer = ListSerializer(String.serializer())

    @TypeConverter
    fun fromStringList(value: List<String>?): String =
        json.encodeToString(listSerializer, value ?: emptyList())

    @TypeConverter
    fun toStringList(value: String?): List<String> {
        if (value.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(listSerializer, value) }
            .getOrDefault(emptyList())
    }
}
