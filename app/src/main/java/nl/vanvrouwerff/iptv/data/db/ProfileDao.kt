package nl.vanvrouwerff.iptv.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {

    @Query("SELECT * FROM profiles ORDER BY sortIndex ASC, createdAt ASC")
    fun observeProfiles(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles ORDER BY sortIndex ASC, createdAt ASC")
    suspend fun allProfiles(): List<ProfileEntity>

    @Query("SELECT * FROM profiles WHERE id = :id LIMIT 1")
    suspend fun getProfile(id: String): ProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: ProfileEntity)

    @Query("UPDATE profiles SET name = :name, colorArgb = :colorArgb WHERE id = :id")
    suspend fun rename(id: String, name: String, colorArgb: Int)

    /**
     * Delete a profile and all its per-profile rows. We use a transaction so a partial
     * delete can't leave orphaned favorites / progress / episodes pointing at a profile
     * id that no longer exists (the rows wouldn't show in the UI, but they'd waste disk).
     */
    @Transaction
    suspend fun deleteCascade(id: String) {
        deleteFavoritesFor(id)
        deleteProgressFor(id)
        deleteWatchedEpisodesFor(id)
        deleteProfile(id)
    }

    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun deleteProfile(id: String)

    @Query("DELETE FROM favorites WHERE profileId = :id")
    suspend fun deleteFavoritesFor(id: String)

    @Query("DELETE FROM watch_progress WHERE profileId = :id")
    suspend fun deleteProgressFor(id: String)

    @Query("DELETE FROM watched_episodes WHERE profileId = :id")
    suspend fun deleteWatchedEpisodesFor(id: String)
}
