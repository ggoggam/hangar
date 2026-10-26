# Hangar

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
simulator, and builds both apps, all on GitHub-hosted runners.

| Module | What it is |
|---|---|
| `core` | Fly.io's login, GraphQL, Machines API, logs and metrics clients on Ktor. Pure Kotlin, tested on the JVM in CI. |
| `app` | Compose Multiplatform UI and the platform services behind it: token storage, settings, the sign-in sheet. |
| `android-app`, `ios-app` | Entry points. iOS's Keychain and ASWebAuthenticationSession are Swift bridges in `ios-app/src`. |

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
(`fly tokens create readonly <org>`) than a full login.

flyctl can also take the code over a loopback callback (`redirect_port`), which would spare the
paste. The app doesn't use it yet: it hasn't been tested whether a mobile browser lets
fly.io call `127.0.0.1`, or whether the app stays alive to answer while the browser is in front.

## Dashboard

Tapping an app opens its dashboard. Each section loads on its own, so one failing call leaves the
rest on screen, and the dashboard reloads every 30 seconds while it's in front. The calls are
the ones flyctl and fly-go make:

| Tab | Source |
|---|---|
| Overview | GraphQL `app(name:)` (fly-go's `GetAppCompact`). Volumes, IPs and certificates come from `api.machines.dev/v1/apps/{app}/volumes`, `/ip_assignments` and `/certificates`. |
| Metrics | Fly.io's managed Prometheus: `api.fly.io/prometheus/{org}/api/v1/query_range` over 1, 6 or 24 hours, for CPU, memory, edge requests and errors per minute, and p95 latency. flyctl has no metrics command; these follow Fly.io's Grafana boards. |
| Machines | `api.machines.dev/v1/apps/{app}/machines`, filtered the way `fly status` filters it: no destroyed, release-command or console machines, and no `bg_deployments_compat` checks. Each machine's config `env` is never decoded. |
| Releases | GraphQL `releasesUnprocessed` (fly-go's `GetAppReleasesMachines`), the last 25. |
| Logs | `api.fly.io/api/v1/apps/{app}/logs?next_token=&instance=`, polled the way `fly logs` polls when it can't use NATS: straight away while pages come back full, backing off to 4 seconds when quiet. JSON log lines are shown as their message plus `key=value` fields. |

Everything except GraphQL takes `FlyV1` with only the macaroons (`FlyToken.machinesHeader`). An
OAuth-only `Bearer` token gets a 401 there.

## Privacy

The app is a thin client between you and Fly.io's API. It has no server of its own, and no
analytics, crash reporting, ads or other third-party SDKs (the dependencies are in
`libs.versions.toml`).

- **Where it connects.** Only `api.fly.io` (login, GraphQL, logs, metrics) and
  `api.machines.dev` (machines, volumes, IPs, certificates), both set in `core/src/FlyHttp.kt`.
  Renewing a token's auth discharge goes to the third party the token names, and
  `FlyDischarges` refuses any host but `api.fly.io` and `auth.fly.io`, so your OAuth token can't
  be sent anywhere else. On Android the only permission it asks for is `INTERNET`.
- **What it sends.** Your Fly.io token, to Fly.io. When you sign in, a name for the login
  ("Hangar on SM-A235F", "Hangar on iPhone"), which Fly.io shows on its approval page and in your
  token list. That's the device model, never the name you gave the phone or any device
  identifier. Every request carries the User-Agent `hangar/0.1 (unofficial Fly.io client)`.
- **What it sees.** Your password and your Google or GitHub sign-in never reach the app: you
  sign in on fly.io in the system's sign-in browser (Custom Tabs, ASWebAuthenticationSession),
  and the app only gets the code Fly.io shows afterwards.
- **What it keeps.** The token, on the device: sealed under an Android Keystore key and
  excluded from backups (`allowBackup="false"`), or in the iOS Keychain for this device only.
  Signing out deletes it. Besides that, only whether you picked dark or light mode, in
  SharedPreferences or NSUserDefaults. Your email, apps, logs and metrics are held in memory while they're on
  screen: the app never writes them to disk, logs them or sends them anywhere. Its HTTP clients
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
