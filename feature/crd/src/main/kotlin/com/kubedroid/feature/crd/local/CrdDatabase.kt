package com.kubedroid.feature.crd.local

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import android.content.Context

@Database(
    entities = [FavouriteCrd::class],
    version = 1,
    exportSchema = false,
)
abstract class CrdDatabase : RoomDatabase() {
    abstract fun favouriteCrdDao(): FavouriteCrdDao

    companion object {
        private const val DATABASE_NAME = "crd.db"

        fun create(context: Context): CrdDatabase {
            return Room.databaseBuilder(
                context,
                CrdDatabase::class.java,
                DATABASE_NAME,
            ).build()
        }
    }
}
