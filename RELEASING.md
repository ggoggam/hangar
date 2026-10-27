# Releasing to Google Play

`.github/workflows/release-android.yml` builds the app bundle from a `v*` tag, signs it with the
upload key and uploads it to Google Play. `.github/workflows/tag-release.yml` makes that tag when
a new `versionName` reaches `main`. Most of the work is the one-time setup below; after that, a
release is a merged version bump.

## Releasing a version

In a pull request, bump `versionName` and `versionCode` in `android-app/module.yaml`. Play turns
down a `versionCode` it has seen before, in any track, so it goes up by one every time, even for a
build that never left closed testing.

Once it's merged and CI passes on `main`, **Tag release** tags the merge commit `v` plus
`versionName` and starts **Release Android** from that tag. Release Android builds the signed
bundle, keeps it as the run's artifact and uploads it to the track in the `PLAY_TRACK` repository
variable (closed testing if it's unset). A merge that leaves `versionName` alone releases nothing.

Tag release stops without tagging if `versionCode` isn't above the last release tag's, since the
tags ruleset keeps a `v*` tag from being deleted once it's pushed. Pushing a `v*` tag yourself
still starts a release, and Release Android checks that it matches `versionName`.

To send a tag somewhere else, run **Release Android** by hand from the Actions tab, pick the tag
under "Use workflow from", and choose a track: `internal`, `alpha` (closed testing), `production`,
or `none` to only build the bundle. A release on a track Play hasn't opened to the app fails at the
upload step, and the bundle is still in the run's artifacts.

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
tags, allow only tags matching `v*`, so nothing but a release tag can reach these secrets. Then
add these environment secrets:

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
2. Create a service account in that project, with no Cloud roles, and add a JSON key. Paste the
   whole file into the `PLAY_SERVICE_ACCOUNT_JSON` secret, then delete the download.
3. In Play Console, **Users and permissions → Invite new users**, enter the service account's email
   address and give it, for Hangar only, **Release apps to testing tracks** (and **Release to
   production, exclude devices, and use Play App Signing** once production is open).

Then set `PLAY_TRACK` to `alpha`, or delete it, and later releases upload on their own.
