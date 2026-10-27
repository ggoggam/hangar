# Releasing to Google Play and TestFlight

`.github/workflows/release-android.yml` builds the app bundle from a `v*` tag, signs it with the
upload key and uploads it to Google Play. `.github/workflows/release-ios.yml` builds the iOS app
from the same tag and uploads it to TestFlight ([below](#ios-testflight)).
`.github/workflows/tag-release.yml` makes that tag when a new `versionName` reaches `main`. Most of
the work is the one-time setup below; after that, a release is a merged version bump.

## Releasing a version

In a pull request, bump `versionName` in `android-app/module.yaml`. That's the only version you
edit.

`versionCode` isn't kept in the repo. Play turns down a `versionCode` it has seen before, in any
track, so Release Android works it out for each build: the commit count, or one past the highest
code on Play if that's higher, so running it again for the same commit still gets a new one. It's
in the run's notices and the Play release name, as in `0.1.1 (42)`. Local builds get Amper's
default, 1.

Once it's merged and CI passes on `main`, **Tag release** tags the merge commit `v` plus
`versionName` and starts **Release Android** and **Release iOS** from that tag. Release Android builds the signed
bundle, keeps it as the run's artifact and uploads it to the track in the `PLAY_TRACK` repository
variable (closed testing if it's unset). A merge that leaves `versionName` alone releases nothing.

Pushing a `v*` tag yourself still starts a release, and Release Android checks that it matches
`versionName`. If a release fails after its tag is pushed, fix it and run **Release Android** again
from the same tag, since the tags ruleset keeps a `v*` tag from being deleted.

To send a tag somewhere else, run **Release Android** by hand from the Actions tab, pick the tag
under "Use workflow from", and choose a track: `internal`, `alpha` (closed testing), `production`,
or `none` to only build the bundle. A release on a track Play hasn't opened to the app fails at the
upload step, and the bundle is still in the run's artifacts.

To ship `main` again without a version bump, such as a fix during the closed test, run **Release
Android** or **Release iOS** by hand from `main`. It builds `main` as it is, with the `versionName`
it has and a new `versionCode` or build number, so testers see `0.1.1 (57)`, then `0.1.1 (58)`.
Nothing is tagged, so the tag stays on the build that first had that version. Runs from any other
branch are turned down, and the `play-store` and `app-store` environments allow only `main` and
`v*` tags.

## Before production: closed testing

Personal developer accounts made after 13 November 2023 can't publish to production until they've
run a closed test with **at least 12 testers opted in for 14 consecutive days**, then applied for
production access from the Play Console dashboard. Google usually reviews the application within 7
days. A tester who opts out and back in restarts their 14 days. Organization accounts don't have
this rule. See [Play Console Help](https://support.google.com/googleplay/android-developer/answer/14151465).

So the order is:

1. The one-time setup below, with the first bundle uploaded to closed testing by hand.
2. Invite testers (an email list or a Google Group) to the closed testing track, and have each one
   open the opt-in link and install the app. Aim for more than 12, so one dropping out doesn't
   reset the count.
3. Keep releasing to closed testing while the 14 days run. The application asks what testers said
   and what changed because of it, so ship fixes during the test.
4. Apply for production. Once it's granted, promote a tested release from closed testing to
   production in Play Console, or set `PLAY_TRACK` to `production`.

## One-time setup

### 1. Create the app in Play Console

Create the app with the package name `dev.ggoggam.hangar`. Closed testing needs the app's setup
done first: store listing, privacy policy URL, app access, ads, content rating, target audience
and data safety. Answer the data safety form from the README's [Privacy](README.md#privacy)
section.

For **App access**, reviewers need a way past the sign-in. Hangar does nothing without a Fly.io
account, so give them a read-only token for an organization made for review, with a small app in
it, and tell them to choose "paste a token" on the sign-in screen:

```sh
fly tokens create readonly <review-org>
```

### 2. Make the upload key

Play App Signing is on for new apps: Google keeps the key that signs what users install, and you
sign uploads with an upload key that only proves they came from you.

```sh
keytool -genkeypair -v -keystore hangar-upload.jks -alias upload \
  -keyalg RSA -keysize 4096 -validity 10000
```

Keep the keystore and its passwords in a password manager, never in the repo (`.gitignore`
already excludes `*.keystore` and `keystore.properties` under `android-app`). Use passwords
without `\`, `=` or `:`, since they go into a properties file. If the upload key is ever lost or
leaked, Play Console can reset it; the app's own signing key is unaffected.

### 3. Add the secrets to GitHub

Create an environment named `play-store` (Settings → Environments). Under deployment branches and
tags, allow only the `main` branch and tags matching `v*`, so nothing but a release can reach these
secrets. Then add these environment secrets:

| Secret | Value |
|---|---|
| `ANDROID_UPLOAD_KEYSTORE_BASE64` | `base64 -i hangar-upload.jks` (macOS) or `base64 -w0 hangar-upload.jks` (Linux) |
| `ANDROID_UPLOAD_KEYSTORE_PASSWORD` | The keystore's password |
| `ANDROID_UPLOAD_KEY_ALIAS` | `upload` |
| `ANDROID_UPLOAD_KEY_PASSWORD` | The key's password |
| `PLAY_SERVICE_ACCOUNT_JSON` | The service account key from step 5 |

### 4. Upload the first bundle by hand

The Play Developer API takes no uploads for an app until its first bundle has been uploaded in
Play Console. So for the first one, set the repository variable `PLAY_TRACK` to `none`
(Settings → Secrets and variables → Actions → Variables), then tag the current version:

```sh
git tag v0.1.0 && git push origin v0.1.0
```

Download the bundle from the run's artifacts and upload it in Play Console to the closed testing
track. The first upload sets up Play App Signing; keep the default, a signing key Google makes.

### 5. Give the workflow access to Play

1. In Google Cloud, pick or create a project and enable the **Google Play Android Developer API**.
   Hangar's account is `hangar-publisher@daytwo-21fdf.iam.gserviceaccount.com`, in daytwo's
   project, which already had the API on.
2. Create a service account in that project, with no Cloud roles, and add a JSON key. Paste the
   whole file into the `PLAY_SERVICE_ACCOUNT_JSON` secret, then delete the download.
3. In Play Console, **Users and permissions → Invite new users**, enter the service account's email
   address and give it, for Hangar only, **Release apps to testing tracks** (and **Release to
   production, exclude devices, and use Play App Signing** once production is open). Release
   Android also reads the app's bundles and tracks to pick the `versionCode`, which these allow.

Then set `PLAY_TRACK` to `alpha`, or delete it, and later releases upload on their own.

## iOS: TestFlight

**Release iOS** runs fastlane's `beta` lane (`ios-app/fastlane/Fastfile`) on a hosted macOS runner.
It builds `ios-app/module.xcodeproj` (scheme `app`, Release) and uploads it to TestFlight for
internal testers. It doesn't wait for Apple to finish processing the build. Sending a build to
external testers or the App Store is done in App Store Connect.

The version is `versionName` from `android-app/module.yaml`, the same as Android's. The build
number is the commit count, or one past the latest on TestFlight if that's higher. Both reach
xcodebuild as `MARKETING_VERSION` and `CURRENT_PROJECT_VERSION`, so the values in the project file
only matter for local builds.

Signing is Xcode's automatic (cloud) signing: with an App Store Connect API key and
`-allowProvisioningUpdates`, xcodebuild makes or downloads the distribution certificate and
profile itself, so there's no fastlane `match` repo. To run the lane locally, set the three
`ASC_*` variables below and run `bundle exec fastlane beta` in `ios-app/` (Ruby 3.3).

### One-time setup

1. **The app in App Store Connect.** The bundle ID `dev.ggoggam.hangar` is registered, and the app
   is "Hangar - Fly.io Client" (SKU `hangar`), since "Hangar" was taken. The name on the home
   screen is still Hangar, from `CFBundleDisplayName`.
2. **An App Store Connect API key** with the App Manager role or higher, so the first build can
   make the signing certificate (Users and Access → Integrations → App Store Connect API). Hangar
   uses the team's key, `UDDZFB62GL`.
3. **The secrets.** Create an environment named `app-store`, allow only the `main` branch and tags
   matching `v*` under deployment branches and tags, and add these environment secrets:

   | Secret | Value |
   |---|---|
   | `ASC_KEY_ID` | The key's ID |
   | `ASC_ISSUER_ID` | The issuer ID shown above the keys |
   | `ASC_KEY_P8_BASE64` | `base64 -i AuthKey_<key id>.p8` |

4. **TestFlight testers.** Add internal testers to a group in the app's TestFlight tab. Internal
   testers get each build once Apple has processed it.
