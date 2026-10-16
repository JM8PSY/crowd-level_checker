package com.crowdlevel.node.settings

/** ノードを置く部屋。ID は Supabase の rooms.id と同じ（増やすときはサーバ側も private.set_room で足す） */
data class RoomOption(val id: String, val label: String)

object Rooms {
    val OPTIONS = listOf(
        RoomOption("rest-3f", "3F休憩室"),
        RoomOption("rest-2f", "2F休憩室"),
    )
    val DEFAULT: String = OPTIONS.first().id

    fun isValid(id: String): Boolean = OPTIONS.any { it.id == id }

    fun labelOf(id: String): String = OPTIONS.firstOrNull { it.id == id }?.label ?: id
}
