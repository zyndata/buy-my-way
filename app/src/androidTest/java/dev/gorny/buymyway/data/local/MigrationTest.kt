package dev.gorny.buymyway.data.local

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Walks the database from v1, built from the committed schema file, through every migration to
 * the current version, and checks the result against the current schema. With v1 the only
 * version this proves the committed schema is the one the code builds; each new version adds
 * its migration to [AppDatabase.MIGRATIONS] and this test keeps working unchanged.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val name = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test
    fun fromV1ToTheCurrentVersionKeepsTheData() = runTest {
        helper.createDatabase(name, 1).use { db ->
            db.execSQL(
                """
                INSERT INTO lists (id, name, ownerUid, shared, categoryOrder, createdAt, updatedAt, updatedBy, clearedAt, deletedAt)
                VALUES ('l1', 'Sobota', NULL, 0, '["nabial","warzywa"]', 1, 1, NULL, NULL, NULL)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO items (id, listId, name, quantity, unit, categoryId, note, photoAt, sortKey, checked,
                    checkedAt, checkedBy, createdAt, createdBy, updatedAt, updatedBy, deletedAt)
                VALUES ('i1', 'l1', 'Mleko', 2.0, 'l', 'nabial', NULL, NULL, 1.0, 0, NULL, NULL, 1, NULL, 1, NULL, NULL)
                """.trimIndent(),
            )
            db.execSQL("INSERT INTO name_history (`key`, name, categoryId, lastUsedAt, useCount) VALUES ('mleko', 'Mleko', 'nabial', 1, 1)")
        }

        val latest = AppDatabase.VERSION
        helper.runMigrationsAndValidate(name, latest, true, *AppDatabase.MIGRATIONS).close()

        val db = Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
            name,
        ).addMigrations(*AppDatabase.MIGRATIONS).build()
        try {
            val summary = db.lists().observeSummaries().first().single()
            assertEquals(listOf("nabial", "warzywa"), summary.list.categoryOrder)
            assertEquals(1, summary.total)
            assertEquals("Mleko", db.items().get("i1")?.name)
            assertEquals("nabial", db.nameHistory().get("mleko")?.categoryId)
        } finally {
            db.close()
        }
    }
}
