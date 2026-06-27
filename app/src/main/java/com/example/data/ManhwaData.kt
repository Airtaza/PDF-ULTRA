package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "manhwas")
data class Manhwa(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val filePath: String,
    val totalPages: Int,
    val lastReadPage: Int = 0,
    val scrollOffset: Int = 0,
    val lastOpened: Long = System.currentTimeMillis()
)

@Entity(tableName = "bookmarks")
data class Bookmark(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val manhwaId: Long,
    val pageIndex: Int,
    val title: String
)

@Entity(tableName = "plugins")
data class PluginConfig(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val enabled: Boolean
)
