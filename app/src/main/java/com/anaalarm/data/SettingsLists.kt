package com.anaalarm.data

object SettingsLists {

    fun join(items: List<String>): String =
        items.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(",")

    fun split(raw: String?): List<String> =
        raw?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
}
