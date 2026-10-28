# Privacy policy

Effective 28 October 2026.

Hangar is an unofficial mobile client for Fly.io, made by ggoggam (ggoggam.dev@gmail.com). It isn't
made by or affiliated with Fly.io. This policy covers the Hangar app for Android and iOS.

## The short version

Hangar talks to Fly.io and nothing else. The developer runs no server for it and receives nothing
from it: no analytics, crash reports, ads or third-party SDKs. The app is open source, so you can
check this in the code at <https://github.com/ggoggam/hangar>.

## What the app sends, and where

- **Your Fly.io token, to Fly.io.** When you sign in, Fly.io gives the app an access token, or you
  paste one. The app sends it to Fly.io with each request, to show your apps, machines, logs,
  metrics, releases, databases, organizations and Fly.io's status. It goes only to Fly.io's own
  hosts: `api.fly.io`, `api.machines.dev` and `auth.fly.io`.
- **A name for the login, to Fly.io.** When you sign in, the app names the login after your
  device's model, such as "Hangar on iPhone", so you can recognize it on Fly.io's approval page and
  in your token list. It never sends the name you gave your device or any device identifier.
- **Nothing about you, to Fly.io's status feed.** The app reads `incidents.flyio.net` without a
  token.

If you turn on background checks in Settings, the app makes the same requests to the same hosts
while it isn't open, to notify you of Fly.io incidents, problems with your apps, and billing
problems or spend over an amount you set. The
notifications come from your device; no push service or server is involved.

Every request is sent over HTTPS. Fly.io handles what it receives under its own privacy policy, at
<https://fly.io/legal/privacy-policy>.

Your password and your Google or GitHub sign-in never reach the app: you sign in on fly.io in your
device's browser, and the app only receives the code Fly.io shows afterwards.

## What the app keeps on your device

- Your Fly.io token: sealed under an Android Keystore key and left out of backups, or in the iOS
  Keychain for this device only, so it never moves to another device.
- Whether you picked dark or light mode, which notifications you want, and the spend alert's
  amount if you set one.
- For background checks, what the last check saw: the names of Fly.io's open incidents, which
  Fly.io publishes, and one-way fingerprints of the problems it has already told you about, which
  don't contain your app, machine or organization names.

Your email address, apps, logs and metrics are held in memory while they're on screen. The app
doesn't write them to disk, log them or send them anywhere else. A notification names the app it's
about, and your device keeps it until you dismiss it.

## Deleting your data

Signing out deletes the token from your device. Uninstalling the app deletes everything it kept. To
end the login on Fly.io's side as well, revoke the token in your Fly.io account or with
`fly tokens revoke`. The developer holds none of your data, so there is nothing else to delete.

## Children

Hangar is for developers who use Fly.io and isn't directed at children.

## Changes

Changes to this policy are made in this file, and its history is public in the repository. The date
at the top says when it last changed.

## Contact

Questions about this policy: ggoggam.dev@gmail.com.
