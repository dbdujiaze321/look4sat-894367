# Smoke test

The release build is minified with R8 in full mode, with class repackaging and
string adaptation. That class of build fails in ways a compiler cannot catch: a
screen that renders locally can crash on a device because a reflective lookup no
longer matches. The three checks below are the ones that historically break, so
they are what a build has to pass before it ships.

**Status of 4.4.9-fork.1: not yet run on a device.** The APK was verified
statically only — signature, manifest, and a scan of the dex for surviving
symbols. Treat that build as untested until this page has a result recorded for
it.

## Setup

```bash
adb devices                          # expect one device, state "device"
adb install -r look4sat-4.4.9-fork.1-release.apk
adb logcat -c
```

A device that had the Play or F-Droid build installed will refuse the install
with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`: the signing keys differ. Uninstalling
first destroys the app's database, so export anything you care about.

## The three checks

### 1. Workbench renders

Open the app and wait for the first position fix.

Pass: the data card shows satellite count, next pass and current selection; no
blank screen, no crash.
Fails if R8 broke a Room query or a Hilt binding — the screen stays empty and
`logcat` shows a `NullPointerException` or an unresolved `Room` generated class.

### 2. Pass list → chart icon → analysis screen

From the pass list, tap the chart icon on any row.

Pass: the analysis screen opens with geometry values, the elevation profile and
the orbital elements.
This exercises the `kotlinx.serialization` navigation keys: a bad rename shows
up as `SerializationException: Serializer for class '…' is not found` at
navigation time.

### 3. Saved-state restore

With the analysis screen open, press Home, then:

```bash
adb shell am kill com.rtbishop.look4sat
```

Reopen the app from Recents.

Pass: the analysis screen comes back, not the workbench, and nothing crashes.
This is the check that catches serializer-renaming damage, because the restored
state is deserialised with the same keys.

## Recording the result

```bash
adb logcat -d > logcat.txt
```

Note the device, the Android version and the outcome of all three checks in the
release notes. If something fails, the first flag to relax is
`-adaptclassstrings` in `app/proguard-rules.pro`, the second is
`-repackageclasses`; both are one-line changes and the build is about three
minutes.

## What static verification does cover

For the record, so a missing device does not get confused with no verification
at all:

- `apksigner verify --print-certs --v4-signature-file …idsig` — v2 and v3
  verify, certificate digest matches the one in the release notes.
- `aapt2 dump badging` — package name, version code and name, target SDK, and
  no `debuggable` flag.
- A scan of the dex for identifiers that should have been renamed; the report is
  in `dist/open-source/RELEASE-NOTES.md` of the build that produced the artefact.
