package dev.ggoggam.hangar.app.watch

import dev.ggoggam.hangar.app.generated.resources.*
import dev.ggoggam.hangar.app.ui.usd
import dev.ggoggam.hangar.core.FlyAlert
import dev.ggoggam.hangar.core.FlyApiException
import dev.ggoggam.hangar.core.FlyMachine
import dev.ggoggam.hangar.core.FlySession
import dev.ggoggam.hangar.core.FlyWatch
import dev.ggoggam.hangar.core.FlyWatchOptions
import dev.ggoggam.hangar.core.isDemo
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import org.jetbrains.compose.resources.getString
import kotlin.time.Clock

/**
 * One background check: [FlyWatch] against the last one's state, then a notification for each new
 * problem the user wants to hear about. [http] is the live client; the demo is never checked.
 */
class Watcher(
    private val http: HttpClient,
    private val session: FlySession,
    private val settings: WatchSettings,
    private val notifier: Notifier,
    private val nowSeconds: () -> Long = { Clock.System.now().epochSeconds },
) {
    /**
     * Checks once. False when there's nothing left to check, because the checks are off or nobody
     * is signed in, so the platform can stop scheduling them; a check that fails on the network is
     * true, to try again next time.
     */
    suspend fun run(): Boolean {
        val token = session.token.value
        if (!settings.enabled.value || token == null || token.isDemo) return false
        val result =
            try {
                val billing = settings.billing.value
                FlyWatch(http, session).check(
                    settings.state(),
                    nowSeconds(),
                    FlyWatchOptions(billing = billing, monthlyBudgetUsd = settings.budget.value?.takeIf { billing }),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: FlyApiException.Unauthorized) {
                // Fly.io turned the login down, and the session has signed out: say so, since the
                // checks stop here and nothing else would tell the user.
                settings.saveState(null)
                notifier.post(sessionEnded())
                return false
            } catch (e: Exception) {
                return true
            }
        settings.saveState(result.state)
        for (alert in result.alerts) {
            val wanted =
                when (alert) {
                    is FlyAlert.IncidentOpened, is FlyAlert.IncidentResolved -> settings.incidents.value
                    is FlyAlert.BillingProblem, is FlyAlert.OverBudget -> settings.billing.value
                    else -> settings.apps.value
                }
            if (wanted) notifier.post(notification(alert))
        }
        return true
    }

    private suspend fun sessionEnded() =
        HangarNotification(
            "session",
            NotificationChannel.Account,
            getString(Res.string.channel_account),
            getString(Res.string.notify_signed_out_title),
            getString(Res.string.notify_signed_out_body),
        )

    private suspend fun notification(alert: FlyAlert): HangarNotification {
        val channel =
            when (alert) {
                is FlyAlert.IncidentOpened, is FlyAlert.IncidentResolved -> Incidents
                is FlyAlert.BillingProblem, is FlyAlert.OverBudget -> Billing
                else -> Apps
            }
        val (title, body) =
            when (alert) {
                is FlyAlert.IncidentOpened -> {
                    getString(Res.string.notify_incident_title, alert.incident.name) to
                        alert.incident.components
                            .joinToString(", ") { it.name }
                            .ifEmpty { getString(Res.string.notify_incident_body) }
                }

                is FlyAlert.IncidentResolved -> {
                    getString(Res.string.notify_resolved_title, alert.name) to getString(Res.string.notify_resolved_body)
                }

                is FlyAlert.HostIssue -> {
                    getString(Res.string.notify_host_title, alert.app) to alert.issue.message
                }

                is FlyAlert.MachineFailed -> {
                    getString(Res.string.notify_failed_title, alert.app) to
                        getString(Res.string.notify_failed_body, machine(alert.machine))
                }

                is FlyAlert.ChecksFailing -> {
                    getString(Res.string.notify_checks_title, alert.app) to
                        getString(Res.string.notify_checks_body, machine(alert.machine), alert.checks.joinToString(", "))
                }

                is FlyAlert.BillingProblem -> {
                    val (title, body) =
                        when (alert.status) {
                            "PAST_DUE" -> Res.string.notify_past_due_title to Res.string.notify_past_due_body
                            "DELINQUENT" -> Res.string.notify_delinquent_title to Res.string.notify_delinquent_body
                            "SUSPENDED" -> Res.string.notify_suspended_title to Res.string.notify_suspended_body
                            "TRIAL_ENDED" -> Res.string.notify_trial_ended_title to Res.string.notify_payment_needed_body
                            else -> Res.string.notify_payment_needed_title to Res.string.notify_payment_needed_body
                        }
                    getString(title, alert.name) to getString(body)
                }

                is FlyAlert.OverBudget -> {
                    getString(Res.string.notify_spend_title, alert.org, usd(alert.projectedUsd)) to
                        getString(Res.string.notify_spend_body, usd(alert.budgetUsd))
                }

                is FlyAlert.MachineCrashed -> {
                    if (alert.oomKilled) {
                        getString(Res.string.notify_oom_title, alert.app) to
                            getString(Res.string.notify_oom_body, machine(alert.machine))
                    } else {
                        getString(Res.string.notify_crashed_title, alert.app) to
                            getString(Res.string.notify_crashed_body, machine(alert.machine), alert.exitCode?.toString() ?: "?")
                    }
                }
            }
        return HangarNotification(alert.key, channel.first, getString(channel.second), title, body)
    }

    /** A machine as the Machines tab names it: its name and region, else its id. */
    private fun machine(machine: FlyMachine): String =
        listOf(machine.name.ifEmpty { machine.id }, machine.region).filter { it.isNotEmpty() }.joinToString(" · ")

    private companion object {
        val Incidents = NotificationChannel.Incidents to Res.string.channel_incidents
        val Apps = NotificationChannel.Apps to Res.string.channel_apps
        val Billing = NotificationChannel.Billing to Res.string.channel_billing
    }
}
