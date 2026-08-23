# FlyShare for Android

A native peer that speaks [protocol v2](../docs/PROTOCOL.md). Planning and
decisions live in `SPEC.md` at the repository root (local, not published).

## Layout

```
core/   pure Kotlin, no Android imports — protocol, discovery, crypto
app/    the Android application: permissions, storage, interface
```

The split is deliberate and worth keeping. `core` runs on a plain JVM, so it is
unit-tested in milliseconds and can be pointed at a real desktop without an
emulator. Only `app` may touch the Android SDK.

## Running the tests

```
./gradlew :core:test
```

## Checking discovery against a real desktop

This is how the discovery code was verified before any phone was involved. Start
the desktop app, then run the same engine the phone will run:

```
./gradlew :core:probe -PprobeName="Kotlin phone" -PprobeSeconds=20
```

It prints the interfaces it found, best first, and every peer that answers. The
desktop's own list should show it too — that is both directions confirmed.

The only piece a JVM does not exercise is `MulticastPermit`. On Android, without
a held multicast lock the system drops multicast and broadcast datagrams before
they reach the socket, so discovery finds nothing while looking entirely
healthy. That part can only be confirmed on a device.

## Building the app

```
./gradlew :app:assembleDebug
```

Needs an Android SDK; `local.properties` points at it and is not committed.

## Notes for whoever picks this up

- AGP 9 supplies Kotlin itself. Adding `org.jetbrains.kotlin.android` makes the
  build fail rather than being merely redundant.
- `local.properties` is a Java properties file, so a Windows SDK path needs
  forward slashes — backslashes are read as escape sequences and produce
  "Invalid file path".
