# StickFix for the AYN Odin 3

System-wide analog stick fix: per-stick deadzone, outer deadzone, anti-deadzone and response curve, with per-game profiles.

## How it works

```
MCU ─▶ com.odin.mapping ─▶ /dev/input/event8 "Xbox Wireless Controller" (2020:0112, itself a uinput device)
                                   │  EVIOCGRAB + unlinked from /dev/input (Android stops seeing it)
                                   ▼
                              stickfixd (root, native)  ── reshape ABS_X/Y (left), ABS_Z/RZ (right)
                                   │                         everything else passed through
                                   ▼
                     /dev/uinput "Xbox Wireless Controller" (same IDs ⇒ same key layout), flat=0
                                   ▲
              127.0.0.1:47811 ─────┘  StickFix app: settings, live view, per-game profile switching
```

- `app/src/main/cpp/stickfixd.c`: the remapper. It is built with the NDK clang and shipped as `lib/arm64-v8a/libstickfixd.so` so it is extracted to the app's native lib dir. If AYN's service recreates its device, the daemon follows it. On exit it restores the hidden node.
- The app writes `Android/data/com.wawek.stickfix/files/stickfix.conf` (token, hide flag, default profile). The daemon reads it at start and whenever it changes.
- `StickFixService` (foreground) keeps the socket open and switches profiles by foreground app. It uses UsageStats, so it needs Usage access. It also starts the daemon at boot (`BootReceiver`) and restarts it if it dies, unless you pressed Stop.
- Root comes from AYN's stock `PServerBinder` service (the same no-root technique as Odin Hub, P.U.L.S.E. and ClusterTune): `transact(0)` with `String[]{cmd, "1"}`. PServer only runs one-line commands, so the app writes `stickfix-start.sh` / `stickfix-stop.sh` into its external files dir. It sends exactly `sh /data/media/0/Android/data/com.wawek.stickfix/files/stickfix-start.sh` (or `-stop.sh`), never anything else.

## Use

1. Install the APK and open StickFix once. The remapper starts by itself, now and after every boot.
2. Tune with the sliders while watching the live view (grey = raw, blue = output).
3. **+ Game profile** for per-game settings; grant **Usage access** for automatic switching.
4. **Stop remapper** gives the original controller back and keeps it off (also across reboots) until you press **Start remapper**.

Log: `/data/local/tmp/stickfix/log`.

## Build

JDK 21, Android SDK (platform 36, build-tools 35), NDK 28.2.13676358.

```
gradlew assembleDebug
```

## Findings on this unit (2026-11-06)

- `event8` has `flat 15` of ±32767, so Android adds no meaningful deadzone. Sticks rest at 0.8% / 2.1% raw, so sub-5% values reach evdev.
- shell (uid 2000) is in group `input` and `/dev/uinput` is 0666, so the daemon also runs as shell. Only hiding the original node (unlink `/dev/input/event8`) needs root.
- PServer runs commands as root in the SELinux domain `pservice`, which is permissive. It returns immediately and runs only the first line of a multi-line command. `pm` doesn't work inside it.
