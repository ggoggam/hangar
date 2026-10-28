<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="icon/logo-dark.svg">
    <img src="icon/logo.svg" width="128" height="128" alt="Hangar's icon: a Quonset hangar with an arched door">
  </picture>
</p>

<h1 align="center">Hangar</h1>

<p align="center">
  Watch your Fly.io apps from your phone: machines, logs, metrics, releases and usage.
</p>

<p align="center">
  <a href="https://github.com/ggoggam/hangar/actions/workflows/ci.yml"><img src="https://github.com/ggoggam/hangar/actions/workflows/ci.yml/badge.svg?branch=main" alt="CI"></a>
  <img src="https://img.shields.io/badge/platform-Android%20%7C%20iOS-171717" alt="Platforms: Android and iOS">
  <img src="https://img.shields.io/badge/Kotlin-Compose%20Multiplatform-171717?logo=kotlin&logoColor=white" alt="Kotlin, Compose Multiplatform">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-171717" alt="MIT license"></a>
</p>

An unofficial mobile client for watching Fly.io apps, on Android and iOS. Not made by or
affiliated with Fly.io.

Hangar talks to Fly.io and nothing else. It doesn't collect, intercept or pass on any personally
identifiable information, and it's open source so that you can check that for yourself rather
than take it on trust: see [Privacy](#privacy).

Built with the [Kotlin Toolchain](https://github.com/JetBrains/amper): `./kotlin` pins the
toolchain and its JDK, so a clone builds with nothing else installed except Xcode for iOS.
`mise run` lists the tasks (`test`, `test:ios`, `build:android`, `build:ios`, `dev:android`,
`dev:ios`, `lint`). To run it on a device:

```sh
mise run dev:android   # a USB or wireless phone, else an emulator
mise run dev:ios       # an iPhone, else a simulator (macOS only)
```

CI (`.github/workflows/ci.yml`) runs pre-commit, the core tests on the JVM and on the iOS
simulator, and builds both apps, all on GitHub-hosted runners. Merging a new `versionName` tags it
(`.github/workflows/tag-release.yml`), and a `v*` tag builds the signed Android bundle and uploads
it to Google Play (`.github/workflows/release-android.yml`), and builds the iOS app and uploads it
to TestFlight with fastlane (`.github/workflows/release-ios.yml`): see [RELEASING.md](RELEASING.md).

| Module | What it is |
|---|---|
| `core` | Fly.io's login, GraphQL, Machines API, Managed Postgres, logs, metrics and incidents clients on Ktor. Pure Kotlin, tested on the JVM in CI. |
| `app` | Compose Multiplatform UI and the platform services behind it: token storage, settings, the sign-in sheet. |
| `android-app`, `ios-app` | Entry points. iOS's Keychain and ASWebAuthenticationSession are Swift bridges in `ios-app/src`; background refresh and notifications are called from Kotlin (`app/src@ios/watch`). |

## Login

The same browser login `fly auth login` runs, from `fly-go/cli_session.go` and flyctl's
`internal/command/auth/webauth`. These endpoints are flyctl's, not a documented API.

1. `POST https://api.fly.io/api/v1/cli_sessions` with a PKCE S256 challenge returns the session
   id and the approval page's URL, with `"pkce": true`.
2. The app opens that page in the system's sign-in browser: a Custom Tab on Android,
   ASWebAuthenticationSession on iOS. It never uses a WebView, because Google blocks OAuth in
   embedded WebViews, the user can't see the address there, and the browser's existing fly.io
   session and password manager don't carry over.
3. After approving, Fly.io shows a one-time code. The user pastes it into the app.
4. `POST /api/v1/cli_sessions/{id}/redeem` with the code and the verifier returns the token. It
   works once; Fly.io keeps an unredeemed session for 15 minutes.

The token is stored sealed under an Android Keystore key, or in the iOS Keychain (this device
only). `FlyToken` sends `FlyV1` for macaroons and `Bearer` for older tokens, as fly-go does.

User tokens expire in minutes unless renewed. A token from `fly auth login` is a permission
macaroon with a third-party caveat for `https://api.fly.io/aaa/v1`, plus a discharge for that
caveat and an OAuth token (`fo1_`). The discharge lasts 10 minutes. Before most commands, flyctl
posts the caveat's ticket to `<third party>/.well-known/macfly/3p` with `Bearer <fo1 token>`,
which is superfly/macaroon's `tp` protocol, and swaps in the new discharge. `FlySession` does
the same before every call and stores the result (`FlyDischarges`, `MacaroonInfo`). It sends the
OAuth token only to api.fly.io and auth.fly.io. If Fly.io wants the user in a browser for a
discharge, or rejects the OAuth token, the app signs out.

Calls go out several at a time, so renewal is single-flight: the first call that finds the
discharge due starts it, and the rest wait for the same outcome rather than each asking Fly.io.
A call that Fly.io turns down with a 401 forces a renewal even when the discharge looks good,
since the phone's clock may be off, and runs once more. The app only signs out when the renewed
token is turned down too, and only if it's still the signed-in one, so a late 401 for an old
token can't sign out a newer one.

Pasting a token also works, for anyone who would rather give the app a read-only one
(`fly tokens create readonly <org>`) than a full login. Fly.io turns down the machine actions
for a read-only token, and the app says so rather than signing out.

"Try the demo" signs in with a token of its own (`DEMO_TOKEN`) instead. While it's signed in, the
screens get an HTTP client on Ktor's mock engine that answers each of the calls below with sample
JSON in the shape Fly.io sends (`core/src/FlyDemo.kt`), so the demo runs the same code as a real
session. It's what Google Play's reviewers use, since the app does nothing without a Fly.io
account. `FlyDemoTest` makes every call the screens make against it, so a new call that the demo
can't answer fails CI.

flyctl can also take the code over a loopback callback (`redirect_port`), which would spare the
paste. The app doesn't use it yet: it hasn't been tested whether a mobile browser lets
fly.io call `127.0.0.1`, or whether the app stays alive to answer while the browser is in front.

## Dashboard

Tapping an app opens its dashboard. Each section loads on its own, so one failing call leaves the
rest on screen, and the dashboard reloads every 30 seconds while it's in front. The calls are
the ones flyctl and fly-go make:

| Tab | Source |
|---|---|
| Overview | GraphQL `app(name:)` (fly-go's `GetAppCompact`). Volumes, IPs and certificates come from `api.machines.dev/v1/apps/{app}/volumes`, `/ip_assignments` and `/certificates`; each volume shows how much of it is used, from the `bytes_used` and `bytes_total` the API returns, and one 90% full or more gets a warning at the top. Host issues under the app's machines, when there are any, come from GraphQL `app.hostIssues` (fly-go's `GetAppHostIssues`, behind `fly incidents hosts list`). Restart app does what `fly apps restart` does: it leases the running machines and restarts them one after another, each started and passing its checks before the next, or all at once if you choose. Postgres apps (`app_role` `postgres_cluster` at `/v1/apps/{app}`) are left to `fly pg restart`, as flyctl leaves them. |
| Metrics | Fly.io's managed Prometheus: `api.fly.io/prometheus/{org}/api/v1/query_range` over 1, 6 or 24 hours, for CPU, memory, edge requests and errors per minute, and p95 latency. flyctl has no metrics command; these follow Fly.io's Grafana boards. "Open in Grafana" opens the app's board on fly-metrics.net in the browser, the page `fly dashboard metrics --grafana` opens. |
| Machines | `api.machines.dev/v1/apps/{app}/machines`, filtered the way `fly status` filters it: no destroyed, release-command or console machines, and no `bg_deployments_compat` checks. Each machine's config `env` is never decoded. Start, stop, suspend and restart go through `/v1/apps/{app}/machines/{id}/start`, `/stop`, `/suspend` and `/restart`, each under the machine's lease as flyctl takes one (`POST …/lease`, the action with the `fly-machine-lease-nonce` header, `DELETE …/lease`). A restart waits on `…/wait?state=started` and then for the machine's checks to pass, as `fly machine restart` does. Stopping, suspending and restarting ask first. Tapping a machine opens what `fly machine status` shows, from `/v1/apps/{app}/machines/{id}` (fly-go's `flaps.Client.Get`): its image, size, private IP, version, volumes, services, checks with their output and recent events with exit codes and out-of-memory kills, with the same actions. Of the config, only the guest, metadata, mounts and services are decoded: never the env, init command or files, which can hold secrets. |
| Releases | GraphQL `releasesUnprocessed` (fly-go's `GetAppReleasesMachines`), the last 25, with the user's email and name. Tapping one shows it in full, as `fly releases --image` does, with whether its image changed from the release before it that went out, and the machines running it; all from what the dashboard has loaded, with no more calls. |
| Secrets | `api.machines.dev/v1/apps/{app}/secrets`, which `fly secrets list` reads: names, digests and when each was set. Values are never asked for (no `show_secrets`), so they never reach the phone. Each secret's status is worked out as flyctl does it: on a machine when it was set no later than the release that machine runs was made, so Deployed on every machine, Staged on none, or Partial; none past 100 machines, where flyctl shows none either. |
| Logs | `api.fly.io/api/v1/apps/{app}/logs?next_token=&instance=`, polled the way `fly logs` polls when it can't use NATS: straight away while pages come back full, backing off to 4 seconds when quiet. JSON log lines are shown as their message plus `key=value` fields. The search and the errors-and-warnings filter look through the last 1,000 lines on the phone, as the dashboard's live logs do, since the API has no search; a line's level comes from its JSON or logfmt (`level=`) when it has one. |

The bottom bar switches between the app list and three more screens:

| Screen | Source |
|---|---|
| Fly.io status | `incidents.flyio.net/v1/incidents`, the feed `fly incidents list` reads, with no token. Open incidents are listed at the top of the app list, and a dismissable banner runs across the top of every screen while any is open, as flyctl warns before its commands. Checked every minute while the app is in front. A link opens status.fly.io in the browser, as `fly platform status` does. |
| Data & storage | `fly mpg list` for each organization (GraphQL `organizations`, then `api.machines.dev/v1/postgres?org_slug=` merged with `api.fly.io/api/v1/organizations/{org}/postgres`, the way flyctl merges them), `fly postgres list` (GraphQL `apps(role: "postgres_cluster")`), and `fly redis list` and `fly storage list` (GraphQL `addOns(type:)` for `upstash_redis` and `tigris`). |
| Settings | `fly auth whoami` (GraphQL `viewer`), `fly orgs list` (GraphQL `organizations`), and for each organization `fly orgs show` (its members) and `fly tokens list --scope org` (names, creators and expiry dates; never the tokens, which Fly.io doesn't hand back). `fly platform regions` comes from `api.machines.dev/v1/platform/regions`. Also the background checks below, the theme switch, and signing out, which asks first. All read-only: Hangar doesn't invite, remove or revoke. |

The app list opens with an estimate of each organization's usage over the last 14 days, and each
app's share under its name. Neither flyctl nor Fly.io's API reports bills, so Hangar works it out
(`core/src/FlyUsage.kt`) from Fly.io's Prometheus, where its metrics go back about 15 days: how
long each Machine ran at its size and region (`fly_instance_info`, counted each minute), what each
app sent out by edge region (`fly_edge_data_out`), and its volumes from `api.machines.dev`. Those
are priced with `FlyPrices`, a copy of the per-second rates, region markups and data transfer
prices on docs.fly.io/about/pricing as of 2026-10-26. It's an estimate: it leaves out dedicated
IPv4 addresses, stopped Machines' storage, Managed Postgres, Redis and Tigris, discounts and credits,
and it goes stale when Fly.io changes its prices, so update `FlyPrices` when they do.

With 8 apps or more, a search field above the list narrows it by app or organization name, on
the phone. Touching and holding an app pins it to the top, under Pinned, on this device. Pins are
kept as one-way fingerprints of the apps' names (`FlyPins`), not the names.

Everything except GraphQL takes `FlyV1` with only the macaroons (`FlyToken.machinesHeader`). An
OAuth-only `Bearer` token gets a 401 there. The older Managed Postgres list is the exception: like
flyctl, Hangar sends it every token under `Bearer` (`FlyToken.uiexHeader`).

## Notifications

Turned on in Settings, Hangar checks Fly.io in the background and notifies you of what's new
since it last looked (`core/src/FlyWatch.kt`). There's no push server: the phone itself asks
Fly.io, the same calls the screens make, and posts the notifications. On Android, WorkManager runs
the check about every 15 minutes while there's a network, though Doze and battery saver can hold
it back. On iOS it's a `BGAppRefreshTask`, asked for no sooner than 15 minutes apart, which iOS
runs when it judges best: a few times a day for an app you open often, rarely for one you don't,
and not at all after you force-quit it, until you open it again. Without a server sending pushes
through APNs, that's as often as iOS lets an app look. The Settings switch says so on iOS.

| Notification | Source |
|---|---|
| Fly.io incident opened or resolved | `incidents.flyio.net/v1/incidents`, as the status banner reads it. |
| Host issue | GraphQL `app.hostIssues` for each deployed app (`fly incidents hosts list`). |
| Machine failed | A machine in the `failed` state, from `api.machines.dev/v1/apps/{app}/machines` (`fly status`). |
| Machine crashed or ran out of memory | An `exit` event since the last check with a non-zero exit code or `oom_killed`, leaving out the stops someone asked for (`requested_stop`: autostop, deploys, `fly machine stop`). |
| Health checks failing | A started machine's checks critical at two checks running, since they fail for a moment while a machine starts or deploys. |
| Deploy finished, failed or interrupted | Each app's latest release, whatever its state, from the app list's GraphQL query (`currentReleaseUnprocessed`, so no extra calls): one that turned `complete`, `failed` or `interrupted` since the last check, or that was still `pending` or `running` at it, which catches a long deploy and a phone clock ahead of Fly.io's. Undeployed apps too, for a first deploy that failed. |
| Volume nearly full | A running app's volume 90% full or more, by `bytes_used` and `bytes_total` from `api.machines.dev/v1/apps/{app}/volumes` (`fly volumes list`). Notified once each time it gets there: it counts as cleared under 85%, so one hovering at the line doesn't notify again on every check. One more call per app, made only while its switch is on. |
| Billing problem | An organization's GraphQL `billingStatus` turning `PAST_DUE`, `DELINQUENT`, `SUSPENDED`, `SOURCE_REQUIRED` or `TRIAL_ENDED`, as flyctl's `GetOrganization` reads it and `fly launch` checks it. One call per organization. |
| Over the spend alert | An organization on pace to spend more than the amount set in Settings over 30 days, at its last 2 days' rate, from the same estimate as the app list (`FlyUsage`). Off until an amount is set, and estimated at most every 6 hours, since it's a few Prometheus queries per organization. |
| Signed out | Fly.io turned the login down, so the checks have stopped. |

The first check only takes note of what's already wrong, so turning the checks on doesn't bring a
burst of notifications for problems the app list shows anyway. After that each problem is
notified once, and again only if it clears and comes back. An app or feed that can't be reached
keeps what was known about it, so a network failure never reads as a problem resolved. Apps are
checked four at a time, two calls each, or three with volumes. The demo is never checked.

Tapping a notification opens what it's about: an app's Machines tab for its machines, its Overview
for a host issue or a nearly full volume and its Releases for a deploy, or the app list, with
Fly.io's incidents and the usage estimate, for the rest. The link travels in the notification itself (an intent extra on
Android, `userInfo` on iOS) and holds only the app's and organization's names, as the
notification's text already does.

## Privacy

The privacy policy for the store listings is [PRIVACY.md](PRIVACY.md); this section is the detail
behind it.

The app is a thin client between you and Fly.io's API. It has no server of its own, and no
analytics, crash reporting, ads or other third-party SDKs (the dependencies are in
`libs.versions.toml`).

- **Where it connects.** Only `api.fly.io` (login, GraphQL, logs, metrics, Managed Postgres),
  `api.machines.dev` (machines, volumes, IPs, certificates, Managed Postgres) and
  `incidents.flyio.net` (Fly.io's incident feed, an app Fly.io runs on its own platform, called
  with no token and nothing about you), all set in `core/src/FlyHttp.kt`. "Open in Grafana" and
  "Open status.fly.io" hand a link to your browser; the app itself doesn't connect to either.
  Renewing a token's auth discharge goes to the third party the token names, and
  `FlyDischarges` refuses any host but `api.fly.io` and `auth.fly.io`, so your OAuth token can't
  be sent anywhere else. The background checks call the same hosts. On Android the only
  permissions it asks for are `INTERNET`, and `POST_NOTIFICATIONS` when you turn the background
  checks on; WorkManager adds the ones it needs to run and reschedule them (`WAKE_LOCK`,
  `ACCESS_NETWORK_STATE`, `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE`). On iOS it asks to
  send notifications at the same point, and declares the `fetch` background mode for background
  refresh. "Try the
  demo" connects to nothing: while it's signed in, the screens get a client that answers every
  call from sample data built into the app (`core/src/FlyDemo.kt`).
- **What it sends.** Your Fly.io token, to Fly.io. When you sign in, a name for the login
  ("Hangar on SM-A235F", "Hangar on iPhone"), which Fly.io shows on its approval page and in your
  token list. That's the device model, never the name you gave the phone or any device
  identifier. Every request carries the User-Agent `hangar/0.1 (unofficial Fly.io client)`.
- **What it sees.** Your password and your Google or GitHub sign-in never reach the app: you
  sign in on fly.io in the system's sign-in browser (Custom Tabs, ASWebAuthenticationSession),
  and the app only gets the code Fly.io shows afterwards.
- **What it keeps.** The token, on the device: sealed under an Android Keystore key and
  excluded from backups (`allowBackup="false"`), or in the iOS Keychain for this device only.
  Signing out deletes it. Besides that, only whether you picked dark or light mode, which
  notifications you want and the spend alert's amount, in SharedPreferences or NSUserDefaults, and for the background checks,
  what the last one saw (`FlyWatchState`): the names of Fly.io's open incidents, which are public,
  and for your apps only one-way fingerprints (truncated SHA-256) of the problems already
  notified, never an app's, machine's, volume's or organization's name. The apps you pin to the top
  of the list are kept the same way, as one-way fingerprints of their names (`FlyPins`). Your email, apps, logs, metrics and secrets' names are held in
  memory while they're on screen: the app never writes them to disk, logs them or sends them
  anywhere. A notification does name the app it's about, and the system keeps it until you
  dismiss it. Its HTTP clients
  keep no response cache and no cookies (`core/src@android`, `core/src@ios`).

## Contributing

Issues and pull requests are welcome: see [CONTRIBUTING.md](CONTRIBUTING.md), including the
privacy rules every change has to keep. Report security problems privately, as
[SECURITY.md](SECURITY.md) describes.

## License

MIT. Fly.io is a trademark of Fly.io, Inc.; this project isn't endorsed by them.

The font ships in the app rather than loading from the web. Hangar Sans is Pretendard (Kil
Hyung-jin) cut down to common Hangul, under the SIL Open Font License 1.1, which reserves
Pretendard's name, so it's renamed. `fonts/build.py` makes it from the pinned upstream release
(`mise run fonts`), and the licence ships in `app/composeResources/files/licenses`.
