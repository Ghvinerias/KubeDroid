package com.kubedroid.feature.crd.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FavouriteCrdDao {

    @Query("SELECT * FROM favourite_crds ORDER BY name ASC")
    fun observeAll(): Flow<List<FavouriteCrd>>

    @Query("SELECT * FROM favourite_crds")
    suspend fun listAll(): List<FavouriteCrd>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(favouriteCrd: FavouriteCrd)

    @Query("DELETE FROM favourite_crds WHERE `group` = :group AND version = :version AND kind = :kind")
    suspend fun delete(
        group: String,
        version: String,
        kind: String,
    )

    @Query(
        "SELECT EXISTS(SELECT 1 FROM favourite_crds WHERE `group` = :group AND version = :version AND kind = :kind)",
    )
    suspend fun exists(
        group: String,
        version: String,
        kind: String,
    ): Boolean
}
