package app.lianxu.independent

import android.content.ContextWrapper
import com.google.gson.JsonParser
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OperationDiagnosticTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var journal: DiagnosticJournal
    private val secret = "sensitive-canary-body-path-key"
    private val turn = ChatTurn(UUID.randomUUID().toString(), secret, 1, 2, TurnState.COMPLETED, 2, "", null)

    @Before fun start() {
        journal = DiagnosticJournal(temp.newFolder(), "test")
        Diagnostics.installForTest(journal)
        Diagnostics.detailed = true
    }
    @After fun stop() { Diagnostics.installForTest(null); journal.close() }

    private fun results(event: String, requireTurn: Boolean = false): List<String> {
        fun read(detailed: Boolean): List<String> {
            val raw = journal.export(detailed).decodeToString()
            assertFalse(raw.contains(secret))
            return raw.lineSequence().filter { it.isNotBlank() }.map { JsonParser.parseString(it).asJsonObject }
                .filter { it["event"]?.asString == event }.map { row ->
                    assertTrue(row["elapsed_ms"].asLong >= 0)
                    val data = row["data"].asJsonObject
                    if (requireTurn) {
                        assertEquals(turn.id, data["turn"].asString)
                        assertEquals(turn.attempt, data["attempt"].asInt)
                    }
                    data["stage"].asString + "/" + data["result"].asString
                }.toList()
        }
        return read(false).also { assertEquals(it, read(true)) }
    }

    @Test fun snapshotSucceedsAndInjectedCancellationAndIoKeepOriginalError() {
        val root = JsonParser.parseString("""{"scope":"all","roles":[],"preferences":{},"models":[],"selections":{},"includesKeys":false}""").asJsonObject
        BackupArchive.snapshot(root, emptyMap(), temp.root) {}.use { assertTrue(it.all) }
        for (error in listOf(IOException(secret), CancellationException(secret))) {
            assertSame(error, runCatching { BackupArchive.snapshot(root, emptyMap(), temp.root) { throw error } }.exceptionOrNull())
        }
        assertEquals(listOf("SNAPSHOT/COMPLETED","SNAPSHOT/IO_FAILED","SNAPSHOT/CANCELLED"), results("backup.step"))
    }

    @Test fun backupPasswordVerificationKeepsNeitherPasswordNorKeyInEitherExport() {
        val root = JsonParser.parseString("""{"includesKeys":true,"models":[{"config":{"id":"test"},"key":"$secret"}]}""").asJsonObject
        val password = ("correct-" + secret).toCharArray()
        val sealed = BackupKeySeal.seal(root, password, iterations=100_000)
        assertTrue(runCatching { BackupKeySeal.open(sealed, null) }.exceptionOrNull() is BackupKeySeal.NeedsPassword)
        val failure = runCatching { BackupKeySeal.open(sealed, ("wrong-" + secret).toCharArray()) }.exceptionOrNull()
        assertTrue(failure is BackupKeySeal.WrongPassword)
        BackupKeySeal.open(sealed, password)
        assertEquals(listOf("UNSEAL/PASSWORD_REQUIRED", "UNSEAL/WRONG_PASSWORD", "UNSEAL/COMPLETED"),results("backup.step"))
    }

    @Test fun realMediaPrepareFailsAtReadOncePerBatchAndIgnoresEmptyBatches() = runBlocking {
        val ctx = object: ContextWrapper(null) { override fun getCacheDir(): File = temp.root }
        val ref = ChatImageRef(secret, 1, secret, "image/jpeg", secret, 1, 1)
        val io = IOException(secret)
        withContext(Diagnostics.linkedContext(mapOf("turn" to turn.id, "attempt" to turn.attempt, "request_number" to 1L))) {
            WholeImageDelivery.prepare(ctx, emptyList(), { throw io }) {}.close()
            VideoDelivery.prepare(ctx, emptyList(), true, { throw io }, {}) {}.close()
            assertSame(io, runCatching { WholeImageDelivery.prepare(ctx, listOf(ref,ref), { throw io }) {} }.exceptionOrNull())
            for (frames in listOf(false,true)) {
                assertSame(io, runCatching { VideoDelivery.prepare(ctx,listOf(ref),frames,{ throw io },{}) {} }.exceptionOrNull())
            }
        }
        assertEquals(listOf("IMAGE_BATCH/IO_FAILED","VIDEO_ORIGINALS/IO_FAILED","VIDEO_FRAMES/IO_FAILED"),results("attachment.prepare",true))
    }

    @Test fun notificationSkipsRaceAfterLookupAndReportsSubmissionOnlyAfterPostReturns() = runBlocking {
        var posts = 0
        var errors = 0
        suspend fun attempt(visible: ((String)->Boolean)?, saved: Long? = 7,
            action: ()->LocalResult = { LocalResult.SUBMITTED }) {
            savedReplyNotification(turn,visible,{saved},{posts++;action()},{errors++})
        }
        attempt(null)
        attempt({true})
        var reads=0
        attempt({ ++reads==2 })
        attempt({false},null)
        assertEquals(0,posts)
        for (state in listOf(LocalResult.APP_DISABLED,LocalResult.CHANNEL_DISABLED,LocalResult.SUBMITTED)) attempt({false}) { state }
        attempt({false}) { throw SecurityException(secret) }
        val stop=CancellationException(secret)
        assertSame(stop,runCatching { attempt({false}) { throw stop } }.exceptionOrNull())
        assertEquals(5,posts)
        assertEquals(1,errors)
        assertEquals(listOf("VISIBILITY_UNAVAILABLE","VISIBLE","VISIBLE","NO_SAVED_MESSAGE","APP_DISABLED","CHANNEL_DISABLED",
            "SUBMITTED","ACCESS_DENIED","CANCELLED").map { "SAVED_REPLY/$it" }, results("reply.notification",true))
    }

    @Test fun settingWriterKeepsNegativeInsertReturnDistinctFromThrownFailure() {
        fun write(block: ()->Long) = LocalStep.TOOLBOX_ORDER.observe(result=LocalResult::insert,block=block)
        assertEquals(-1L,write { -1L })
        assertEquals(9L,write { 9L })
        val exc = IOException(secret)
        assertSame(exc, runCatching { write { throw exc } }.exceptionOrNull())
        assertEquals(listOf("TOOLBOX_ORDER/WRITE_REJECTED","TOOLBOX_ORDER/COMPLETED","TOOLBOX_ORDER/IO_FAILED"),results("settings.save"))
    }

    @Test fun basicProjectionRejectsUnknownStageResultAndForeignFields() {
        fun project(payload: String) = LocalStep.project("backup.step", JsonParser.parseString(payload).asJsonObject)
        assertNull(project("""{"stage":"VIDEO_MODE","result":"COMPLETED"}"""))
        assertNull(project("""{"stage":"SNAPSHOT","result":"$secret"}"""))
        assertNull(project("""{"stage":"SNAPSHOT","result":"SUBMITTED"}"""))
        assertEquals("""{"stage":"SNAPSHOT","result":"COMPLETED"}""",
            project("""{"stage":"SNAPSHOT","result":"COMPLETED","attempt":"1","turn":"$secret","path":"$secret"}""").toString())
    }
}
