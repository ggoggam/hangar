package dev.ggoggam.hangar.core

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class FlyVolumesTest {
    @Test
    fun readsAVolumesSnapshotSettings() =
        runTest {
            // A volume as GET /v1/apps/{app}/volumes lists it, fields as fly-go's Volume names them.
            val fly =
                MockFly(
                    HttpStatusCode.OK to
                        """[{"id":"vol_1","name":"data","state":"created","size_gb":3,"region":"nrt","zone":"a1b2",
                            "encrypted":true,"fstype":"ext4","attached_machine_id":"m_1","attached_alloc_id":null,
                            "created_at":"2026-09-01T10:00:00.123Z","host_dedication_id":"","host_status":"ok",
                            "bytes_used":1000,"bytes_total":3000,"snapshot_retention":14,"auto_backup_enabled":false},
                           {"id":"vol_2","name":"older","state":"created","size_gb":1,"region":"nrt"}]""",
                )

            val (data, older) = FlyMachines(fly.http, MIXED_TOKEN, "https://m.test").volumes("web")

            assertEquals("a1b2", data.zone)
            assertEquals(14, data.snapshotRetention)
            assertEquals(false, data.autoBackupEnabled)
            // Left out, it's unknown rather than off.
            assertNull(older.snapshotRetention)
            assertNull(older.autoBackupEnabled)
        }

    @Test
    fun listsSnapshotsNewestFirst() =
        runTest {
            // As flaps.GetVolumeSnapshots reads them; the one being taken has no id yet.
            val fly =
                MockFly(
                    HttpStatusCode.OK to
                        """[{"id":"vs_old","size":2048,"volume_size":3000000000,"digest":"abc","created_at":"2026-10-25T03:12:00Z","status":"created","retention_days":5},
                           {"id":"vs_new","size":4096,"volume_size":3000000000,"digest":"def","created_at":"2026-10-29T03:12:00.5Z","status":"created","retention_days":5},
                           {"id":"","size":0,"volume_size":3000000000,"digest":"","created_at":"2026-10-30T03:12:00Z","status":"running","retention_days":null}]""",
                )

            val snapshots = FlyMachines(fly.http, MIXED_TOKEN, "https://m.test").volumeSnapshots("web", "vol_1")

            val request = fly.requests.single()
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("https://m.test/v1/apps/web/volumes/vol_1/snapshots", request.url.toString())
            assertEquals("FlyV1 fm2_a,fm2_b", request.headers[HttpHeaders.Authorization])

            assertEquals(listOf("", "vs_new", "vs_old"), snapshots.map { it.id })
            val new = snapshots[1]
            assertEquals(4096, new.size)
            assertEquals(3_000_000_000, new.volumeSize)
            assertEquals("def", new.digest)
            assertEquals("created", new.status)
            assertEquals(5, new.retentionDays)
            assertEquals(Instant.parse("2026-11-03T03:12:00.5Z"), new.expiresAt)
            assertNull(snapshots[0].retentionDays)
            assertNull(snapshots[0].expiresAt)
        }

    @Test
    fun aVolumeWithNoSnapshotsHasAnEmptyList() =
        runTest {
            // Go encodes a nil slice as null.
            val fly = MockFly(HttpStatusCode.OK to "[]", HttpStatusCode.OK to "null")
            val machines = FlyMachines(fly.http, MIXED_TOKEN, "https://m.test")

            assertTrue(machines.volumeSnapshots("web", "vol_1").isEmpty())
            assertTrue(machines.volumeSnapshots("web", "vol_1").isEmpty())
        }

    @Test
    fun theDemoSnapshotsItsVolumesButOne() =
        runTest {
            val now = 1_793_000_000L
            val machines = FlyMachines(flyDemoHttpClient { now }, DEMO_TOKEN)

            val (primary, replica) = machines.volumes("api-db")
            assertEquals(true, primary.autoBackupEnabled)
            assertEquals(5, primary.snapshotRetention)
            val snapshots = machines.volumeSnapshots("api-db", primary.id)
            assertEquals(5, snapshots.size)
            assertEquals(snapshots.sortedByDescending { it.createdInstant }, snapshots)
            assertTrue(snapshots.all { it.status == "created" && it.retentionDays == 5 && it.id.startsWith("vs_") })
            assertTrue(snapshots.all { it.createdInstant!! <= Instant.fromEpochSeconds(now) })
            assertTrue(snapshots.all { it.size in 1..<it.volumeSize })

            assertFalse(replica.autoBackupEnabled ?: true)
            assertTrue(machines.volumeSnapshots("api-db", replica.id).isEmpty())
            // A volume the app doesn't have isn't there, as on Fly.io.
            assertFailsWith<FlyApiException.Failed> { machines.volumeSnapshots("api-db", "vol_nope") }
        }
}
