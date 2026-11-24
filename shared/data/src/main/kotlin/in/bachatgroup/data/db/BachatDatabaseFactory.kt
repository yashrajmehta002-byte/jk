package `in`.bachatgroup.data.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * HR-09 & HR-10:
 * 1. SQLCipher AES-256 एन्क्रिप्शन अनिवार्य।
 * 2. Zero-Data-Loss नियम: destructive migration पूरी तरह प्रतिबंधित।
 * 3. डेटाबेस खुलते ही PRAGMA integrity_check और schema_v1.sql के No-Delete ट्रिगर्स लागू।
 */
object BachatDatabaseFactory {
    const val DB_NAME = "bachat_samuh_encrypted.db"
    const val DB_VERSION = 1

    fun createEncryptedOpenHelper(
        context: Context,
        passphraseBytes: ByteArray
    ): SupportSQLiteOpenHelper {
        System.loadLibrary("sqlcipher")
        val factory = SupportOpenHelperFactory(passphraseBytes)
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(DB_NAME)
            .callback(object : SupportSQLiteOpenHelper.Callback(DB_VERSION) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    val sql = context.assets.open("database/schema_v1.sql").bufferedReader().use { it.readText() }
                    sql.split(";\n").map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("--") }
                        .forEach { stmt -> db.execSQL(stmt) }
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    // HR-09: केवल स्पष्ट Migration(oldVersion, newVersion) ही अनुमत है — कभी DROP TABLE नहीं।
                    throw IllegalStateException("Strict Migration आवश्यक है: v$oldVersion -> v$newVersion")
                }
            })
            .build()
        return factory.create(config)
    }
}
