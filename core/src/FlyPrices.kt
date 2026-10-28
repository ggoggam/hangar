package dev.ggoggam.hangar.core

/**
 * Fly.io's list prices, for estimating what an app costs. Fly.io's API doesn't report bills, so
 * these are copied from docs.fly.io/about/pricing as it stood on 2026-10-26: the per-second rates
 * and region markups behind its Machines price table, and its data transfer and volume prices.
 * They go out of date when Fly.io changes its prices, and they know nothing of plans, discounts or
 * credits, so anything built on them is an estimate.
 */
object FlyPrices {
    const val SHARED_VCPU_PER_SECOND = 8.465e-7
    const val PERFORMANCE_VCPU_PER_SECOND = 1.2732e-5

    /** RAM above what each vCPU includes: 256 MB for a shared one, 2 GB for a performance one. */
    const val RAM_GB_PER_SECOND = 2.316e-6

    /** Provisioned volume storage, per GB for 30 days. */
    const val VOLUME_GB_PER_30_DAYS = 0.15

    const val SECONDS_PER_30_DAYS = 2_592_000L

    /**
     * Each region's markup on the Machines prices (`iad` and `ewr` are 1) and its price per GB sent
     * to the internet: $0.02 in North America and Europe, $0.04 in Asia Pacific, Oceania and South
     * America, $0.12 in Africa and India.
     */
    private val REGIONS: Map<String, Pair<Double, Double>> =
        mapOf(
            "ams" to (1.038461538 to 0.02),
            "arn" to (1.038461538 to 0.02),
            "cdg" to (1.134615385 to 0.02),
            "dfw" to (1.25 to 0.02),
            "ewr" to (1.0 to 0.02),
            "fra" to (1.153846154 to 0.02),
            "gru" to (1.615384615 to 0.04),
            "iad" to (1.0 to 0.02),
            "jnb" to (1.302884615 to 0.12),
            "lax" to (1.199519231 to 0.02),
            "lhr" to (1.134615385 to 0.02),
            "nrt" to (1.307692308 to 0.04),
            "ord" to (1.25 to 0.02),
            "sin" to (1.269230769 to 0.04),
            "sjc" to (1.192307692 to 0.02),
            "syd" to (1.269230769 to 0.04),
            "yyz" to (1.115384615 to 0.02),
        )

    /** Whether Fly.io charges for [app]: it doesn't for the remote builders flyctl makes, `fly-builder-…`. */
    fun billed(app: String): Boolean = !app.startsWith("fly-builder-")

    /** Whether [region] is in the table; one that isn't is priced as `iad`, the cheapest. */
    fun knows(region: String): Boolean = region in REGIONS

    /** A started Machine's price per second, as the docs' table works it out. */
    fun machinePerSecond(
        cpuKind: String,
        cpus: Int,
        memoryMb: Int,
        region: String,
    ): Double {
        val performance = cpuKind == "performance"
        val vcpu = if (performance) PERFORMANCE_VCPU_PER_SECOND else SHARED_VCPU_PER_SECOND
        val includedGb = cpus * (if (performance) 2.0 else 0.25)
        val extraGb = (memoryMb / 1024.0 - includedGb).coerceAtLeast(0.0)
        return (cpus * vcpu + extraGb * RAM_GB_PER_SECOND) * (REGIONS[region]?.first ?: 1.0)
    }

    /** The price per GB (10⁹ bytes) sent to the internet from [region]'s edge. */
    fun egressPerGb(region: String): Double = REGIONS[region]?.second ?: 0.02
}
