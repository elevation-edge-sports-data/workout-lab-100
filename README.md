# Workout Lab Track

Local-only workout GPS recorder for Android. Sessions start **only** from the watcher. Workout distance and saved coordinates come from on-foot motion.

Sessions stay on the device until you export.

## Stack

Kotlin, Jetpack Compose, Gradle.

## Open in Android Studio

1. Open Android Studio **2026.1.4** (or later).
2. **File → Open** and choose this folder: `workout-lab-track`.
3. Let Gradle sync. If prompted for an SDK, use the default (`%LOCALAPPDATA%\Android\Sdk`).
4. Plug in a phone with USB debugging, or pick one from the device list.
5. Click **Run** (app: `com.workoutlab.track`).
6. **Samsung:** Settings → Apps → Workout Lab Track → Battery → **Unrestricted** so Watching can keep running with the screen off.

First launch asks for activity recognition (steps) and location (the 12-second GPS sample and the session). Background location is requested so a session can start with the screen off.

## Main screen

Tabs: **Current** (live session) and **Recent** (finished sessions with map, time, distance, and points; sort by most recent or most distance).

**Watching** / **Recording** / **Auto Start off** status, plus session buttons:

- Idle + Auto Start on: **Start**. The watcher may still run and can start a session.
- Recording: **Stop** (pauses) and **Finish**.
- Paused: **Resume** and **Finish**. The watcher does not start a new session while paused.
- Auto Start off: **Start** asks to turn Auto Start on. GPS stays off. The watcher does not start a session.

The path on Current starts fitted to the session.

If a session does not start, Watching may show `no steps`, `no permission`, `battery killed`, `auto start off`, or `accuracy`.

**Start** uses the same session path the watcher uses.

## Two layers

1. **Watcher** (on unless Auto Start is off): steps and on-foot activity. No tiles, path, or miles until a session starts. Quiet notice: **Autostart on (not recording)**.
2. **Session:** GPS, OpenStreetMap tiles if map tiles are on, live path, miles, points, recording notice (**Recording — extra battery**). Auto-Finish ends it; the next walk is a new session / new path.

## Auto Start

Settings toggle: **Auto Start**. On (default): the watcher can start a session, and you can tap Start. Off: the watcher does not start a session, Start stays blocked until you turn it on, GPS stays off. Saved across app restart.

## Autostart (Auto Start on)

After walk/jog cadence or on-foot activity holds about **12 seconds**, the app takes one GPS reading. That reading is skipped when motion looks like a vehicle.

GPS reports a point and an error radius (how uncertain that point is), in feet. If the radius is **82 ft or less**, recording starts immediately. If there is no location yet, or the radius is larger than **82 ft**, the app waits about **20 seconds** of walking, then starts recording.

The watcher keeps GPS off. GPS turns on after the 12-second walk, only to sample location before recording starts.

After the session starts: live path, `0.000 mi`, and points at `0.250 mi` = `1.00 pts`. Auto-Finish still uses gait and vehicle rules (no steps + movement, 12 mph backstop, 45 s).

## What a session does

- **Live:** elapsed time, workout distance in **miles (3 decimals)**, GPS error radius in **feet**, Weak/OK GPS banner from that radius, guess (`workout` vs `likely-vehicle`), live path. Distances and accuracy on screen are miles and feet. OpenStreetMap imagery appears when map tiles are on.
- **Last session:** newest *finished* workout. Older finished sessions stay on disk under `Android/data/com.workoutlab.track/files/sessions/`.
- **Export JSON / GPX** of the newest finished session via the system save picker.
- App battery use since last full charge is on the system App info page (Battery).
- Miles use readings whose error radius is **82 ft or less** and movement at least as large as that radius. Jumps farther than 80 ft in under 2 seconds, or farther than 3× the reported radius in that span, are dropped from the saved path and from miles. The drawn path may be lightly smoothed; miles stay on accepted points.

Jog vs drive uses **gait** (step detector/counter and on-foot activity). A GPS reading adds miles/path when a step was seen in the last 5 seconds and the error radius is **82 ft or less**. GPS moving with no steps for 8 seconds is likely-vehicle (omitted from the path). After likely-vehicle holds 45 seconds, the session **Finishes** (JSON/GPX); the next workout is a new path. 12 mph with no recent steps is likely-vehicle immediately. A session also **Finishes** after 60 / 90 / 120 seconds of inactivity (no steps and no accepted movement), chosen on Current. On-device Activity Recognition `IN_VEHICLE` counts as no-steps when available.

## Permissions

Activity recognition (watcher steps). Location for the 12-second GPS sample and for the session. Background location so a session can start with the screen off. Notifications on Android 13+ for the watcher and recording notices. INTERNET loads OpenStreetMap tiles when map tiles are on. The watcher does not use the network.