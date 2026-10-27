# Contributing to Hangar

Thanks for helping. Hangar is a standalone Kotlin Toolchain project: the committed `./kotlin`
wrapper pins the toolchain and provisions its own JDK, so a clone needs nothing else for Android
and the JVM tests. iOS needs a Mac with Xcode 26.

```sh
mise trust
mise install               # ktlint and prek
mise exec -- prek install  # run the hooks on every commit
mise run test              # core tests on the JVM
mise run test:ios          # core tests on the iOS simulator (macOS)
mise run build:android
mise run build:ios         # macOS
mise run dev:android       # build, install and launch on a phone or emulator
mise run dev:ios           # build, install and launch on an iPhone or simulator (macOS)
mise run kt:fmt            # format with ktlint
mise run fonts             # rebuild the bundled font from its pinned source
mise run icon              # redraw the app icon for Android and iOS
mise run pre-commit        # what CI's pre-commit job runs
```

CI runs pre-commit, the core tests on the JVM and the iOS simulator, and both app builds on every
pull request. Releasing to Google Play is in [RELEASING.md](RELEASING.md).

## Layout

| Module | What goes there |
|---|---|
| `core` | Fly.io's APIs: login, tokens and discharges, GraphQL, Machines API, Managed Postgres, logs, metrics, incidents. Pure Kotlin, no UI and no user-facing strings. |
| `app` | Compose Multiplatform screens and view models, plus the platform services behind small interfaces (`src@android`, `src@ios`). |
| `android-app`, `ios-app` | Entry points. The iOS Keychain and sign-in sheet are Swift bridges in `ios-app/src` that pass only strings. |

New API calls should follow what flyctl and fly-go do, and the code should say which flyctl
command or fly-go function it mirrors. These endpoints are flyctl's, not a documented public API,
so matching flyctl is what keeps them working.

## Privacy rules

Hangar's promise is that it talks to Fly.io and nothing else, and collects nothing about you
(see [Privacy](README.md#privacy)). Pull requests that break it won't be merged:

- No analytics, crash reporting, ads, remote config or any other SDK that phones home.
- No network destination other than Fly.io's own API hosts. A new one needs a reason in the
  pull request and a line in the README's Privacy section.
- Never log, print or persist tokens, emails, app data or log lines. The token is the only thing
  stored, in `TokenStore`.
- Don't send device names or identifiers. The login's name carries the device model and nothing more.
- If a change affects what the app sends, stores or connects to, update the Privacy section in
  the same pull request.

## Tests and checks

- Cover `core` changes with tests that use Ktor's `MockEngine` (see `core/test/MockFly.kt`).
  Build fake tokens with `TestMacaroons`, never from a real one.
- Try UI changes on a real device or a simulator, on both platforms if you can, and attach
  screenshots to the pull request. Redact your email and app names if you'd rather not share them.
- Every user-facing string goes in `app/composeResources/values/strings.xml` and gets a Korean
  entry in `values-ko/strings.xml`.
- Match the code around your change in naming, comments and idiom. ktlint settles formatting.

## Pull requests

- Keep each pull request to one change, with a [Conventional Commits](https://www.conventionalcommits.org)
  title such as `feat(dashboard): show machine restarts` or `fix(login): keep the code after rotation`.
- Fill in the template, including how you tested it.
- Never paste a Fly.io token, the output of `fly auth token`, or a log line containing one into an
  issue, a pull request, a test or a commit. If you do it by accident, revoke the token on
  fly.io straight away.

Security problems go through a private report, not an issue: see [SECURITY.md](SECURITY.md).
