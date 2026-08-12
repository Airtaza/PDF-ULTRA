package com.example.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ManhwaDao {
    @Query("SELECT * FROM manhwas ORDER BY lastOpened DESC")
    fun getAllManhwas(): Flow<List<Manhwa>>

    @Query("SELECT * FROM manhwas WHERE id = :id")
    suspend fun getManhwaById(id: Long): Manhwa?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertManhwa(manhwa: Manhwa): Long

    @Update
    suspend fun updateManhwa(manhwa: Manhwa)

    @Delete
    suspend fun deleteManhwa(manhwa: Manhwa)

    // Bookmarks / Titles (Outline)
    @Query("SELECT * FROM bookmarks WHERE manhwaId = :manhwaId ORDER BY pageIndex ASC")
    fun getBookmarksForManhwa(manhwaId: Long): Flow<List<Bookmark>>

    @Query("SELECT * FROM bookmarks WHERE manhwaId = :manhwaId AND pageIndex = :pageIndex LIMIT 1")
    suspend fun getBookmarkByPage(manhwaId: Long, pageIndex: Int): Bookmark?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmark(bookmark: Bookmark)

    @Delete
    suspend fun deleteBookmark(bookmark: Bookmark)

    // Page Notes
    @Query("SELECT * FROM page_notes WHERE manhwaId = :manhwaId ORDER BY pageIndex ASC")
    fun getPageNotesForManhwa(manhwaId: Long): Flow<List<PageNote>>

    @Query("SELECT * FROM page_notes WHERE manhwaId = :manhwaId AND pageIndex = :pageIndex LIMIT 1")
    suspend fun getPageNoteByPage(manhwaId: Long, pageIndex: Int): PageNote?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPageNote(note: PageNote)

    @Delete
    suspend fun deletePageNote(note: PageNote)

    @Query("DELETE FROM page_notes WHERE manhwaId = :manhwaId AND pageIndex = :pageIndex")
    suspend fun deletePageNoteByPage(manhwaId: Long, pageIndex: Int)

    // Plugins configuration
    @Query("SELECT * FROM plugins")
    fun getAllPlugins(): Flow<List<PluginConfig>>

    @Query("SELECT * FROM plugins WHERE id = :id")
    suspend fun getPluginById(id: String): PluginConfig?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlugin(plugin: PluginConfig)

    // Reading events / statistics
    @Insert
    suspend fun insertReadingEvent(event: ReadingEvent)

    @Query("SELECT * FROM reading_events ORDER BY timestamp ASC")
    fun getAllReadingEvents(): Flow<List<ReadingEvent>>

    @Query("DELETE FROM reading_events")
    suspend fun clearAllReadingEvents()
}
