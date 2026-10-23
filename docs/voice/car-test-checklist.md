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
