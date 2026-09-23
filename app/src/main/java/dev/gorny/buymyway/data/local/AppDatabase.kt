package dev.gorny.buymyway.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
        OwnProductEntity::class,
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
    abstract fun ownProducts(): OwnProductDao

    companion object {
        const val NAME = "buymyway.db"

        /** The current schema version; the exported schemas in app/schemas are named after it. */
        const val VERSION = 3

        /**
         * Every migration, oldest first. Each new version adds its step here and a case to the
         * migration test, which walks all of them from v1 on the committed schema files. No
         * destructive fallback: a missing migration is a crash in testing, never lost lists.
         */
        val MIGRATIONS: Array<Migration> = arrayOf(
            // v2: the „Ręcznie" order (STATE.md decision 67). Null = not placed yet.
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE items ADD COLUMN manualKey REAL")
                }
            },
            // v3: „Moje produkty" (PLAN.md Phase 8b, STATE.md decisions 88 and 89).
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `own_product` (
                            `key` TEXT NOT NULL,
                            `name` TEXT NOT NULL,
                            `categoryId` TEXT NOT NULL,
                            `at` INTEGER NOT NULL,
                            `deletedAt` INTEGER,
                            PRIMARY KEY(`key`)
                        )
                        """,
                    )
                }
            },
        )

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
