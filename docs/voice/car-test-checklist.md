# Parked-car checks

These checks are pending on the car. Host tests do not prove that the seat moves,
Tailscale connects, or the steering-wheel button wakes the app.

## Install and background access

1. Install `BYDMate-v5.0.apk` from the USB stick as an update. Keep the app data.
2. Open BYDMate once after installation.
3. In Settings, find **Autostart** and tap **Ustawienia autostartu**. Check that
   BYDMate can start and open from the background in the car's system menu.
4. Confirm that the local Polish recognition and speech models are installed.
5. Open Tailscale once, sign in, and accept Android's VPN permission if needed.
   Confirm that the assistant is enabled and paired in BYDMate.

## Offline driver-seat massage

Keep the car parked. Turn off Wi-Fi and mobile data for this check.

1. Say **„Włącz masaż fotela kierowcy”**. Check whether the driver's seat starts
   massaging. Recognition must use the local command path.
2. Say **„Wyłącz masaż fotela kierowcy”**. Check whether it stops.
3. Say **„Nie włącz masażu fotela kierowcy”**. Nothing must start.
4. Repeat on/off with the assistant disabled in BYDMate.

The on command selects the driver's pulse mode. Intensity and passenger massage
are outside this change. A spoken confirmation means that the write was accepted;
there is no massage read-back, so check the seat itself. Record the car model and
whether both on and off worked.

## Internet that arrives after startup

1. Start the car without Wi-Fi internet. Leave Tailscale disconnected.
2. Wait at least three minutes, then make the usual Wi-Fi internet available.
3. Do not open BYDMate or Tailscale to help it reconnect. Check whether Tailscale
   connects and BYDMate's assistant becomes available.
4. Ask a general question through the assistant. Record the time and its result.
5. Repeat after a normal car sleep/wake cycle.

If recovery fails, note whether manually connecting Tailscale restores the
assistant. A sent Tailscale request alone does not prove that its VPN connected.

## Steering-wheel microphone after startup

Before opening BYDMate manually, press the steering-wheel microphone button.
Check whether BYDMate's voice interface opens. Then try one local command. If
opening the app manually restores the button, record that separately from the
network check; the delayed-network fix does not establish a button-startup fix.


## Microphone, podcasts and queued replies

These checks are pending on a physical car for the independent-request change.
Keep the car parked and play a podcast at an audible volume.

1. Press the steering-wheel microphone button. Check that the podcast is muted
   while capture is open and that **Słucham** appears only after the ready cue.
2. Ask a question that takes OpenClaw several seconds to answer. After the phrase
   is accepted, check that the microphone stops and the podcast returns to its
   previous volume while **Myślę** is shown. Cabin speech during this wait must
   not become another request.
3. Let the reply arrive with listening still enabled. Check that the podcast
   becomes quiet during the whole reply, then listening opens again. The reply
   must not be recognized as a new command.
4. Repeat, but press the microphone button once during the wait to turn listening
   off. The podcast must be restored and the submitted request must still finish.
   Its reply must be spoken, with the podcast quiet during speech. Listening must
   remain off afterwards.
5. While another request waits, turn listening off and on, then speak a second
   request. Check that each request is submitted once and both replies finish in
   order. If the first reply arrives while recording the second request, its
   speech must wait until that capture window closes.
6. Repeat the off/on sequence but say nothing in the new capture window. Its
   silence timeout must close capture and permit the pending reply to speak.
7. Turn listening off during preparation and during capture. Check that media
   volume recovers and the old capture cannot open later by itself. Turning
   listening off during a spoken reply must leave that reply playing.
8. Try an explicit media-volume command and check the restored level. The app
   must keep capture muted until its owner releases; no old reply may restore
   podcast audio over a new capture.

Record the APK version, car model, head-unit version, podcast player and the
observed volume/capture behavior. Unit tests do not establish the physical
microphone or BYD audio-stream behavior.

## Polish activation and heating shortcuts

Keep the car parked. Turn seat and steering-wheel heating off before each check.

1. Say **„Łącz ogrzewanie fotela kierowcy”**. Check that it works like
   **„Włącz ogrzewanie fotela kierowcy”**.
2. Say **„Dawaj ciepełko”**. Check the driver's seat is at level **1 out of 2**
   and steering-wheel heating is on. The front passenger seat must stay off.
3. Say **„Dawaj ciepełko oba fotele”**. Check both front seats are at level **1**
   and steering-wheel heating is on.
4. Say **„Nie dawaj ciepełko”** and **„Nie łącz ogrzewania fotela”**. Neither
   phrase may turn heating on. Check that a quoted phrase does not run either.

A successful dispatch or spoken confirmation does not prove that a heater changed
state. Check the car's controls and the physical result; report each separately.
