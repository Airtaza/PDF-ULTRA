package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(entities = [Manhwa::class, Bookmark::class, PluginConfig::class], version = 1, exportSchema = false)
abstract class ManhwaDatabase : RoomDatabase() {
    abstract fun manhwaDao(): ManhwaDao

    companion object {
        @Volatile
        private var INSTANCE: ManhwaDatabase? = null

        fun getDatabase(context: Context, scope: CoroutineScope): ManhwaDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ManhwaDatabase::class.java,
                    "manhwa_database"
                )
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        scope.launch(Dispatchers.IO) {
                            val dao = getDatabase(context, scope).manhwaDao()
                            dao.insertPlugin(
                                PluginConfig(
                                    id = "view_enhancer",
                                    name = "View Enhancer Tool",
                                    description = "Adjust brightness, contrast, grayscale, and invert colors for optimal reading comfort.",
                                    enabled = true
                                )
                            )
                            dao.insertPlugin(
                                PluginConfig(
                                    id = "manhwa_editor",
                                    name = "Manhwa Sketch Editor",
                                    description = "Markup and edit pages directly! Draw notes, highlights, and annotations on pages.",
                                    enabled = false
                                )
                            )
                            dao.insertPlugin(
                                PluginConfig(
                                    id = "metadata_bookmark",
                                    name = "Chapter Title Outline",
                                    description = "Allows naming specific pages as chapter boundaries, creating a dynamic Table of Contents.",
                                    enabled = true
                                )
                            )
                        }
                    }
                })
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
