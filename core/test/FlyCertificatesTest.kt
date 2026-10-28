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

class FlyCertificatesTest {
    // The Machines API's example for GET /v1/apps/{app}/certificates/{hostname}, with both sources.
    private val issued =
        """{"hostname":"example.com","configured":true,"acme_requested":true,"status":"active",
            "dns_provider":"enom","rate_limited_until":null,
            "certificates":[
              {"source":"custom","status":"active","created_at":"2024-01-15T10:30:00Z","expires_at":"2039-01-15T10:30:00Z",
               "issuer":"Cloudflare Origin Certificate","issued":[]},
              {"source":"fly","status":"active","created_at":"2024-01-15T12:00:00Z","expires_at":"2024-04-15T12:00:00Z",
               "issuer":null,"issued":[
                 {"type":"rsa","expires_at":"2024-04-15T12:00:00Z","certificate_authority":"lets_encrypt"},
                 {"type":"ecdsa","expires_at":"2024-04-16T12:00:00Z","certificate_authority":"lets_encrypt"}]}],
            "validation":{"dns_configured":true,"alpn_configured":true,"http_configured":true,"ownership_txt_configured":true},
            "dns_requirements":{"a":["203.0.113.7"],"aaaa":["2001:db8::7"],"cname":"web.fly.dev",
              "acme_challenge":{"name":"_acme-challenge.example.com","target":"example.com.x1y2z3.flydns.net"},
              "ownership":{"name":"_fly-ownership.example.com","app_value":"app-abc","org_value":"org-def"}},
            "validation_errors":[]}"""

    @Test
    fun readsOneHostnameWithoutCheckingItAgain() =
        runTest {
            val fly = MockFly(HttpStatusCode.OK to issued)

            val cert = FlyMachines(fly.http, MIXED_TOKEN, "https://m.test").certificate("web", "example.com")

            val request = fly.requests.single()
            // A GET, never `POST …/check`, which validates again and can start issuing.
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("https://m.test/v1/apps/web/certificates/example.com", request.url.toString())
            assertEquals("FlyV1 fm2_a,fm2_b", request.headers[HttpHeaders.Authorization])

            assertEquals(FlyCertState.Verified, cert.state)
            assertEquals("Cloudflare Origin Certificate", cert.custom?.issuer)
            assertEquals(FlyCertState.Issued, cert.flyManaged?.state)
            assertEquals(listOf("rsa", "ecdsa"), cert.flyManaged?.issued?.map { it.type })
            assertEquals("Let's Encrypt", cert.flyManaged?.authority)
            assertNull(cert.custom?.authority)
            assertEquals("2024-04-15T12:00:00Z", cert.flyManaged?.expires)
            assertEquals(listOf("203.0.113.7"), cert.dnsRequirements.a)
            assertEquals("web.fly.dev", cert.dnsRequirements.cname)
            assertEquals("example.com.x1y2z3.flydns.net", cert.dnsRequirements.acmeChallenge.target)
            assertEquals("app-abc", cert.dnsRequirements.ownership.appValue)
            assertTrue(cert.validation.alpnConfigured)
            assertTrue(cert.validationErrors.isEmpty())
            // A custom certificate needs its ownership proven, wildcard or not.
            assertTrue(cert.needsOwnershipRecord)
        }

    @Test
    fun escapesAWildcardsStar() =
        runTest {
            val fly = MockFly(HttpStatusCode.OK to """{"hostname":"*.example.com"}""")

            val cert = FlyMachines(fly.http, MIXED_TOKEN, "https://m.test").certificate("web", "*.example.com")

            assertEquals(
                "/v1/apps/web/certificates/%2A.example.com",
                fly.requests
                    .single()
                    .url.encodedPath,
            )
            assertTrue(cert.isWildcard)
        }

    @Test
    fun anUnverifiedHostnameSaysWhy() =
        runTest {
            // Go's nil slices come as null.
            val fly =
                MockFly(
                    HttpStatusCode.OK to
                        """{"hostname":"*.example.com","configured":false,"acme_requested":true,"status":"pending",
                            "rate_limited_until":"2026-11-01T00:00:00Z","certificates":null,
                            "validation":{"dns_configured":false},
                            "dns_requirements":{"a":null,"aaaa":["2001:db8::7"],"cname":"web.fly.dev",
                              "acme_challenge":{"name":"_acme-challenge.example.com","target":"example.com.x1y2z3.flydns.net"},
                              "ownership":{"name":"_fly-ownership.example.com","app_value":"app-abc"}},
                            "validation_errors":[{"code":null,"message":"No CNAME at _acme-challenge.example.com",
                              "remediation":"Add the CNAME below.","timestamp":"2026-10-28T00:00:00Z"}]}""",
                )

            val cert = FlyMachines(fly.http, MIXED_TOKEN, "https://m.test").certificate("web", "*.example.com")

            assertEquals(FlyCertState.NotVerified, cert.state)
            assertTrue(cert.certificates.isEmpty())
            assertTrue(cert.dnsRequirements.a.isEmpty())
            assertEquals("2026-11-01T00:00:00Z", cert.rateLimitedUntil)
            assertEquals("No CNAME at _acme-challenge.example.com", cert.validationErrors.single().message)
            assertEquals("Add the CNAME below.", cert.validationErrors.single().remediation)
            // A Fly-managed wildcard is proven by the ACME challenge, so `fly certs setup` doesn't ask for the TXT record.
            assertTrue(cert.usesAcme)
            assertFalse(cert.needsOwnershipRecord)
        }

    @Test
    fun statesFollowFlyctl() {
        fun detail(
            configured: Boolean,
            vararg certs: Pair<String, String>,
        ) = FlyJson.decodeFromString(
            FlyCertificateDetail.serializer(),
            """{"hostname":"example.com","configured":$configured,"certificates":[${
                certs.joinToString(",") { (source, status) -> """{"source":"$source","status":"$status"}""" }
            }]}""",
        )
        // Verified, with nothing issued yet.
        assertEquals(FlyCertState.Issuing, detail(true).state)
        assertEquals(FlyCertState.NotVerified, detail(false).state)
        assertEquals(FlyCertState.Issuing, detail(true, "fly" to "pending").state)
        assertEquals(FlyCertState.NotVerified, detail(false, "custom" to "pending_ownership", "fly" to "active").state)

        fun summary(json: String) = FlyJson.decodeFromString(FlyCertificate.serializer(), """{"hostname":"example.com",$json}""")
        assertEquals(FlyCertState.Issued, summary(""""configured":true,"has_fly_certificate":true""").state)
        assertEquals(FlyCertState.Verified, summary(""""configured":true,"has_custom_certificate":true""").state)
        assertEquals(FlyCertState.Issuing, summary(""""configured":false,"acme_alpn_configured":true""").state)
        assertEquals(FlyCertState.NotVerified, summary(""""configured":false,"status":"active"""").state)
    }

    @Test
    fun theDemoHasIssuedCertificatesAndOneNotVerified() =
        runTest {
            val machines = FlyMachines(flyDemoHttpClient(), DEMO_TOKEN)

            val list = machines.certificates("web")
            assertEquals(
                mapOf(
                    "example.com" to FlyCertState.Issued,
                    "www.example.com" to FlyCertState.Issued,
                    "shop.example.com" to FlyCertState.NotVerified,
                ),
                list.associate { it.hostname to it.state },
            )
            for (summary in list) {
                val detail = machines.certificate("web", summary.hostname)
                assertEquals(summary.state, detail.state)
                assertEquals("web.fly.dev", detail.dnsRequirements.cname)
                assertEquals("_acme-challenge.${summary.hostname}", detail.dnsRequirements.acmeChallenge.name)
            }
            val issued = machines.certificate("web", "example.com")
            assertEquals(listOf("rsa", "ecdsa"), issued.flyManaged?.issued?.map { it.type })
            assertEquals("Let's Encrypt", issued.flyManaged?.authority)
            assertTrue(issued.validationErrors.isEmpty())
            assertEquals(1, machines.certificate("web", "shop.example.com").validationErrors.size)
            // A hostname the app doesn't have isn't there, as on Fly.io.
            assertFailsWith<FlyApiException.Failed> { machines.certificate("web", "other.example.com") }
        }
}
