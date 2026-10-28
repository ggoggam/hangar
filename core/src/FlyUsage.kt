package dev.ggoggam.hangar.core

import io.ktor.client.HttpClient
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.time.Instant

/**
 * What an organization's apps used, and roughly what that costs at [FlyPrices]. flyctl has no usage
 * command and Fly.io's API reports no bills, so this works it out from the same metrics Fly.io's
 * Grafana boards read: how long each Machine ran, from its CPU time in `fly_instance_cpu`, at the
 * size and region `fly_instance_info` gives it; what each app sent out, by edge region, from
 * `fly_edge_data_out`; and the volumes from the Machines API. Fly.io keeps metrics for about 15
 * days, so the window is 14.
 */
class FlyUsage(
    private val http: HttpClient,
    private val token: FlyToken,
    private val apiUrl: String = FLY_API_URL,
    private val machinesUrl: String = FLY_MACHINES_URL,
) {
    /** [org]'s usage over the [windowSeconds] before [nowSeconds], with [apps]' volumes. */
    suspend fun estimate(
        org: String,
        apps: List<String>,
        nowSeconds: Long,
        windowSeconds: Long = WINDOW_SECONDS,
    ): FlyOrgUsage =
        coroutineScope {
            val metrics = FlyMetrics(http, token, apiUrl)
            // Fly.io's Prometheus thins out the samples of a query that looks back more than about two
            // days, and a Machine that runs for a few minutes at a time drops out of the thinned ones,
            // so the window is asked about in pieces of up to two days.
            val pieces =
                (0 until (windowSeconds + PIECE_SECONDS - 1) / PIECE_SECONDS).map { i ->
                    val end = nowSeconds - i * PIECE_SECONDS
                    val length = minOf(PIECE_SECONDS, end - (nowSeconds - windowSeconds))
                    async {
                        // The info series, sampled once a minute, says what size each Machine was while it
                        // ran, and roughly for how long; its CPU time, summed over every mode, idle too, and
                        // every vCPU, says how long it really ran.
                        val sizes =
                            async {
                                metrics.instant(
                                    org,
                                    "sum by (app, region, instance, cpu_kind, cpu_count, memory_mb) (count_over_time(" +
                                        "(max by (app, region, instance, cpu_kind, cpu_count, memory_mb) (fly_instance_info))[${length}s:1m]))",
                                    end,
                                )
                            }
                        val cpu =
                            async { metrics.instant(org, "sum by (app, instance) (increase(fly_instance_cpu[${length}s])) / 100", end) }
                        sizes.await() to cpu.await()
                    }
                }
            val sent = async { metrics.instant(org, "sum by (app, region) (increase(fly_edge_data_out[${windowSeconds}s]))", nowSeconds) }
            val machines = FlyMachines(http, token, machinesUrl)
            val volumes = apps.filter { FlyPrices.billed(it) }.map { app -> async { app to machines.volumes(app) } }

            val usage = mutableMapOf<String, Builder>()

            fun of(app: String) = usage.getOrPut(app) { Builder(app) }
            for ((sizes, cpu) in pieces.awaitAll()) {
                val cpuSeconds = cpu.associate { (it.labels["app"] to it.labels["instance"]) to it.value }
                for ((instance, ran) in sizes.groupBy { it.labels["app"] to it.labels["instance"] }) {
                    val app = instance.first ?: continue
                    if (!FlyPrices.billed(app)) continue
                    // Its CPU time shared out over the sizes it had, by the minutes it had each; the minutes
                    // alone, which run a little long for every start and stop, if there's no CPU time.
                    val vcpuMinutes = ran.sumOf { it.value * (it.labels["cpu_count"]?.toIntOrNull() ?: 1) }
                    val secondsPerMinute = cpuSeconds[instance]?.takeIf { vcpuMinutes > 0 }?.let { it / vcpuMinutes } ?: 60.0
                    for (sample in ran) {
                        val region = sample.labels["region"].orEmpty()
                        val seconds = sample.value * secondsPerMinute
                        val perSecond =
                            FlyPrices.machinePerSecond(
                                sample.labels["cpu_kind"].orEmpty(),
                                sample.labels["cpu_count"]?.toIntOrNull() ?: 1,
                                sample.labels["memory_mb"]?.toIntOrNull() ?: 256,
                                region,
                            )
                        of(app).apply {
                            machineSeconds += seconds
                            computeUsd += seconds * perSecond
                            if (!FlyPrices.knows(region)) unpricedRegion = true
                        }
                    }
                }
            }
            for (sample in sent.await()) {
                val app = sample.labels["app"]?.takeIf { FlyPrices.billed(it) } ?: continue
                val region = sample.labels["region"].orEmpty()
                of(app).apply {
                    dataOutBytes += sample.value
                    egressUsd += sample.value / 1e9 * FlyPrices.egressPerGb(region)
                    if (!FlyPrices.knows(region)) unpricedRegion = true
                }
            }
            val start = nowSeconds - windowSeconds
            for ((app, list) in volumes.awaitAll()) {
                for (volume in list) {
                    // Billed while provisioned: from when it was made, or the window's start.
                    val created = volume.createdAt?.let { runCatching { Instant.parse(it).epochSeconds }.getOrNull() } ?: start
                    val seconds = (nowSeconds - maxOf(created, start)).coerceAtLeast(0)
                    of(app).apply {
                        volumeGb += volume.sizeGb
                        volumeUsd += volume.sizeGb * FlyPrices.VOLUME_GB_PER_30_DAYS * seconds / FlyPrices.SECONDS_PER_30_DAYS
                    }
                }
            }
            FlyOrgUsage(org, windowSeconds, usage.values.map { it.build() }.sortedByDescending { it.estimatedUsd })
        }

    private class Builder(
        val app: String,
    ) {
        var machineSeconds = 0.0
        var computeUsd = 0.0
        var dataOutBytes = 0.0
        var egressUsd = 0.0
        var volumeGb = 0
        var volumeUsd = 0.0
        var unpricedRegion = false

        fun build() = FlyAppUsage(app, machineSeconds, computeUsd, dataOutBytes, egressUsd, volumeGb, volumeUsd, unpricedRegion)
    }

    companion object {
        /** Two weeks: Fly.io keeps metrics for about 15 days. */
        const val WINDOW_SECONDS = 14 * 86_400L

        /** The longest look back Fly.io's Prometheus answers from every sample. */
        const val PIECE_SECONDS = 2 * 86_400L
    }
}

class FlyOrgUsage(
    val org: String,
    val windowSeconds: Long,
    /** Most expensive first. */
    val apps: List<FlyAppUsage>,
) {
    val estimatedUsd: Double get() = apps.sumOf { it.estimatedUsd }
    val machineSeconds: Double get() = apps.sumOf { it.machineSeconds }
    val dataOutBytes: Double get() = apps.sumOf { it.dataOutBytes }
    val volumeGb: Int get() = apps.sumOf { it.volumeGb }

    /** Some usage was in a region [FlyPrices] doesn't list, and was priced as the cheapest. */
    val unpricedRegion: Boolean get() = apps.any { it.unpricedRegion }

    fun app(name: String): FlyAppUsage? = apps.firstOrNull { it.app == name }
}

class FlyAppUsage(
    val app: String,
    /** Started Machine time, summed over the app's Machines. */
    val machineSeconds: Double,
    val computeUsd: Double,
    /** Bytes sent to the internet through Fly.io's edge. */
    val dataOutBytes: Double,
    val egressUsd: Double,
    /** Provisioned volume storage now. */
    val volumeGb: Int,
    val volumeUsd: Double,
    val unpricedRegion: Boolean,
) {
    val estimatedUsd: Double get() = computeUsd + egressUsd + volumeUsd
}
