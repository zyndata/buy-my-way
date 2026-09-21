package dev.gorny.buymyway.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

@Database(
    version = AppDatabase.VERSION,
    exportSchema = true,
    entities = [
        ListEntity::class,
        ItemEntity::class,
        CategoryEntity::class,
        MemberEntity::class,
        OutboxOpEntity::class,
        ListSyncEntity::class,
        NameHistoryEntity::class,
    ],
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun lists(): ListDao
    abstract fun items(): ItemDao
    abstract fun categories(): CategoryDao
    abstract fun members(): MemberDao
    abstract fun outbox(): OutboxDao
    abstract fun listSync(): ListSyncDao
    abstract fun nameHistory(): NameHistoryDao

    companion object {
        const val NAME = "buymyway.db"

        /** The current schema version; the exported schemas in app/schemas are named after it. */
        const val VERSION = 1

        /**
         * Every migration, oldest first. Schema v1 has none; each new version adds its step
         * here and a case to the migration test, which walks all of them from v1 on the
         * committed schema files. No destructive fallback: a missing migration is a crash in
         * testing, never lost lists.
         */
        val MIGRATIONS: Array<Migration> = emptyArray()

        fun open(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}

class Converters {
    private val stringList = ListSerializer(String.serializer())

    @TypeConverter
    fun fromStringList(value: List<String>): String = Json.encodeToString(stringList, value)

    @TypeConverter
    fun toStringList(value: String): List<String> = Json.decodeFromString(stringList, value)
}
