package dev.ggoggam.hangar.core

/**
 * A Fly.io access token as flyctl stores it: one or more comma-separated tokens, each a macaroon
 * (`fm1r_`, `fm1a_`, `fm2_`) or an older user token, optionally behind a `FlyV1 ` or `Bearer `
 * scheme. The headers follow fly-go's `tokens` package, so a token that works for flyctl works here.
 */
class FlyToken(
    raw: String,
) {
    /** The tokens without a scheme, the form to store. */
    val value: String = stripScheme(raw.trim())

    private val parts = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    private val macaroons = parts.filter { it.substringBefore('_') in MACAROON_PREFIXES }
    private val userTokens = parts - macaroons.toSet()

    val isBlank: Boolean get() = parts.isEmpty()

    /**
     * The same account's tokens without their discharges, which change every few minutes: two
     * tokens with one identity are one sign-in.
     */
    val identity: String
        get() = (macaroons.filterNot(::isDischarge) + userTokens).joinToString(",")

    private fun isDischarge(token: String) = MacaroonInfo.parse(token)?.let { it.location != MacaroonInfo.LOCATION_PERMISSION } ?: false

    /** The OAuth tokens (`fo1_`) that third parties take to hand out discharges, or null for none. */
    internal val oauth: String? get() = userTokens.joinToString(",").ifEmpty { null }

    /**
     * The third-party caveats on the permission tokens with no discharge that's still good
     * [marginSeconds] after [nowSeconds]. A user token from `fly auth login` carries one for
     * Fly.io's authentication service, whose discharges only last minutes; flyctl renews them
     * before most commands, and so must Hangar.
     */
    internal fun undischarged(
        nowSeconds: Long,
        marginSeconds: Long = 60,
    ): List<ThirdPartyCaveat> {
        val live =
            macaroons.mapNotNull { MacaroonInfo.parse(it) }.filter {
                it.location != MacaroonInfo.LOCATION_PERMISSION &&
                    (it.expiresAt ?: Long.MAX_VALUE) > nowSeconds + marginSeconds
            }
        return thirdParty.filter { caveat -> live.none { it.keyId.contentEquals(caveat.ticket) } }
    }

    /** Every third-party caveat on the permission tokens, discharged or not. */
    internal val thirdParty: List<ThirdPartyCaveat>
        get() =
            macaroons
                .mapNotNull { MacaroonInfo.parse(it) }
                .filter { it.location == MacaroonInfo.LOCATION_PERMISSION }
                .flatMap { it.thirdParty }

    /** These tokens with [discharges] added and the discharges they replace, or that expired, dropped. */
    internal fun withDischarges(
        discharges: List<String>,
        nowSeconds: Long,
        marginSeconds: Long = 60,
    ): FlyToken {
        val fresh = discharges.mapNotNull { d -> MacaroonInfo.parse(d)?.let { d to it } }
        val kept =
            macaroons.filter { tok ->
                val m = MacaroonInfo.parse(tok) ?: return@filter true
                m.location == MacaroonInfo.LOCATION_PERMISSION ||
                    (
                        (m.expiresAt ?: Long.MAX_VALUE) > nowSeconds + marginSeconds &&
                            fresh.none { (_, f) -> f.keyId.contentEquals(m.keyId) }
                    )
            }
        return FlyToken((kept + fresh.map { it.first } + userTokens).joinToString(","))
    }

    /** For api.fly.io/graphql: every token, under `FlyV1` when any of them is a macaroon. */
    val graphqlHeader: String get() = scheme() + (macaroons + userTokens).joinToString(",")

    /** For the Machines API, which wants only the macaroons when there are any. */
    val machinesHeader: String get() = scheme() + (macaroons.ifEmpty { userTokens }).joinToString(",")

    private fun scheme() = if (macaroons.isEmpty()) "Bearer " else "FlyV1 "

    override fun equals(other: Any?): Boolean = other is FlyToken && other.value == value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "FlyToken(${parts.size} part(s))"

    private companion object {
        val MACAROON_PREFIXES = setOf("fm1r", "fm1a", "fm2")

        val SCHEME = Regex("^(FlyV1|Bearer)(\\s+|$)", RegexOption.IGNORE_CASE)

        fun stripScheme(token: String): String = token.replace(SCHEME, "").trim()
    }
}
