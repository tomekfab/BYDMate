# Google Maps and Waze capture for the car test

This debug build collects the head unit signals needed to implement Google Maps and Waze HUD guidance. It does not yet send guidance from either app to the HUD. The existing Yandex parser remains separate.

## Before the session

Install the supplied debug APK as an update. Keep the existing app data. While parked, open BYDMate settings and check notification access and accessibility access. Enable either permission only if it is missing. These permissions let the recorder observe navigator notifications and accessibility text.

Open the navigation recorder from Settings diagnostics. Select Google Maps or Waze. Read the route-data notice before starting. Recording stays off until Start and does not resume after a process restart.

## Capture each navigator separately

Use one navigator at a time. A passenger operates the recorder during driving. For each app, collect two short routes and mark these states when they occur: guiding, foreground, background, turn, reroute, arrived, and cancelled. Stop the current capture before changing app. Stop before Save. Save each capture through Android's document picker, then start a separate capture for the other app.

A session stops at 20 minutes, 10 MiB, or explicit Stop. A record over 16 KiB is skipped and replaced with a small `oversized_dropped` marker, subject to the session byte limit. The skipped count is the third number on the recorder screen and also includes queue drops. The queue holds at most 32 records. Accessibility trees contain at most 300 nodes. Long trees and notification text are cut by serialized size and tagged `truncated`. A stopped capture remains private until Save. Cancelling the picker or a failed copy keeps the private file available. Delete removes the private capture. If deletion or reset fails, the app shows an error and keeps the existing evidence. Export failures can be retried.

## Data handling

Captures can include street names and route text. They remain in `files/nav-capture/` until an explicit Save and do not go to normal diagnostics, logcat, backups, or Telegram. The app has `android:allowBackup="false"`. Keep exported captures local. Remove street and location text before making reusable test fixtures.

The recorder copies primitive notification extras and bounded accessibility text and trees. It does not invoke notification intents, inspect RemoteViews contents, keep bitmaps, or store screenshots, audio, tokens, or PendingIntents. Capture events never publish guidance into the HUD hub.

## What this test can establish

Synthetic emulator tests check only BYDMate's capture and file lifecycle. They do not establish Google Maps or Waze compatibility. The car session must establish what each installed navigator actually exposes, including app/version details, turns, reroutes, arrival, and cancellation. The record metadata fields `appVersion` and `installSource` describe BYDMate, not the selected navigator. Supply the navigator version, language, units and Android version separately with the capture. Adapter implementation follows that evidence.

The car microphone, floating voice status, car-to-Mac-mini connection, and diagnostic uploads also need a physical-car check. Polish recognition has one known word-ending error in the unchanged 16-clip test corpus; negation is retained. See the packaged Polish instructions for that limit and the separate voice and OpenClaw controls.
