# Security policy

## Supported versions

Only the most recent release of this fork is supported. Fixes are not
backported.

## Reporting a vulnerability

Open a private security advisory on the repository hosting this fork, or email
the maintainer listed in [NOTICE](NOTICE). Please do not open a public issue for
a vulnerability before it has been fixed.

Useful reports include: the affected version and `versionCode`, the Android
version and device, the steps to reproduce, and whether the issue requires a
malicious APK, a malicious element-set source, or physical access.

Expect an acknowledgement within a week. There is no bug bounty.

## Scope

### In scope

- Crashes or memory exhaustion caused by untrusted input: element sets, CSV,
  `.ics`, pasted TLE text, theme JSON, saved-sites JSON.
- Parsing bugs that could write outside the app's own storage, or that could be
  used to make the app import a file it did not intend to.
- Any outbound network request the app makes that is not an element-set or
  transceiver download from the sources named in [NOTICE](NOTICE).

### Out of scope

- **Reverse engineering of the released APK.** That is expected and permitted;
  the source is right here. Release builds use R8 in full mode with aggressive
  renaming to raise the cost, not to make it impossible.
- **Repackaging the app with a different key.** A modified build is a different
  program; GPL-3.0 explicitly allows it. It is not a vulnerability.
- **Accuracy of orbital predictions.** Predictions are estimates by design; the
  error curve is documented in [docs/ACCURACY.md](docs/ACCURACY.md).
- Missing certificate pinning, root detection or anti-debugging. The app talks
  to public element-set services over HTTPS; pinning would break the app when a
  provider rotates certificates, and root detection protects nothing that
  matters here.

## Data the app handles

Station coordinates, saved sites, preferences and the element database are stored
in the app's private storage. There is no analytics, no crash reporting, no
advertising identifier and no telemetry. The app performs no network request
unless the user refreshes data, enables automatic updates, or configures a
reporting connection to a rotator.

## Release integrity

Release APKs are signed with a dedicated key. Signatures can be checked with:

```bash
apksigner verify --print-certs app-release.apk
```

SHA-256 checksums are published next to each release. The signing key and the
R8 mapping file are never published.
