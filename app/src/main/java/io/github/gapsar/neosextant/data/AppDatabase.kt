package io.github.gapsar.neosextant.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE position_history ADD COLUMN estimatedLatitude REAL NOT NULL DEFAULT 0.0")
        db.execSQL("ALTER TABLE position_history ADD COLUMN estimatedLongitude REAL NOT NULL DEFAULT 0.0")
        db.execSQL("ALTER TABLE position_history ADD COLUMN imagesJson TEXT DEFAULT NULL")
    }
}

@Database(entities = [PositionEntryEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "neosextant_database"
                )
                .addMigrations(MIGRATION_1_2)
                .allowMainThreadQueries()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
