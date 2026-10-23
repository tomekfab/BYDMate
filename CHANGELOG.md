# Changelog

All notable BYDMate changes are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/ru/1.1.0/), and versioning follows [Semantic Versioning](https://semver.org/lang/ru/).

Full releases with APKs: <https://github.com/AndyShaman/BYDMate/releases>.

## [Unreleased]

## [4.0.1] - 2026-10-14

### Added
- A completed log can be sent to your Telegram bot. After log recording stops, Settings shows a “Wyślij do Telegrama” button. With “Wyślij zapisany log do Telegrama” selected, the log is sent automatically when recording ends (#324).

### Fixed
- The “edytuj” link next to the fuel price on Home opens the tariffs screen instead of general Settings (#184).
- Android 14 head units: fixed an issue that prevented the app from understanding some vehicle responses (#329).
- The “car is off” Telegram report no longer arrives when the car is started. If someone entered an already-on car and pressed the brake and Start button, the app treated that moment as shutdown and sent an extra report. Shutdown is now determined the same way as by the car itself.

## [4.0.0] - 2026-10-11

### Added
- DM-i and DM-o hybrids (Song Pro, Song Plus, Tang L, Seal U, Leopard 5, Leopard 7, Leopard 8, Sealion 06, and others): the app identifies a hybrid from the car’s own counters; no setting or profile is required. On Home, the battery range reported by the car appears under the charge gauge instead of our estimate. A “Paliwo” card shows tank level and fuel range; tap for engine RPM, ICE and battery mileage, and lifetime litres. The widget also shows tank level and fuel range. “Pojazd” shows RPM and the “Paliwo” card. Period consumption is calculated like the instrument panel: l/100 and kWh/100 across all kilometres. Trips has a new “l” column. For petrol trips, the “/100” column shows litres per 100 km; for electric trips, it still shows kWh. Litres are read from the car’s counter at trip boundaries; if the app did not see a boundary, a dash is shown and another vehicle’s fuel is not attributed to the trip. Set the litre price in tariff periods, like the electricity tariff: if you refuel at a higher price, add a period from the refuelling date. Trip cost combines electricity and fuel. On a hybrid, ABRP receives battery power rather than the instrument-panel needle; the voice assistant and Telegram report know about the tank and fuel range. This is initial support: some values can show a dash until dumps from more cars are collected. Nothing changes for electric vehicles. Thanks to baileileicn and bolatbekb for the dumps (#184).
- Automation and voice assistant: “Ogrzewanie baterii: włącz” and “wyłącz” actions. This is the same heating available in the car’s standard “Energia” > “Ładowanie i rozładowanie” menu; it helps the battery charge faster in frost. It works on electric vehicles that have this menu item. The car itself will not enable it if the battery is warm or a charging cable is connected, and the rule will state the reason. It has not yet been tested in a car during frost; feedback is welcome (#311).
- Automation and voice assistant: “Ekran: wyłącz” and “Ekran: włącz” actions. Only the central screen turns off; music and the app keep running, and touching the screen turns it on again. A rule can use this to turn off the screen when the car is closed. It has not yet been tested in cars (#275).
- ABRP: restored the “Wysyłaj współrzędne do ABRP” setting, disabled by default. It is for tracking the car in ABRP from a phone when ABRP is not running in the car, for example when someone else is using it. Do not enable this setting when ABRP is open in the car: it obtains GPS itself and the map marker starts switching between the two sources. Thanks to @ptuchik (#302).

### Fixed
- BYD Cloud over Wi-Fi: when the BYD server rejects the car, the text below the setting now says so and advises that a Chinese VPN is required. Previously this was indistinguishable from “chmura nie odpowiedziała”. The app now waits up to 30 seconds for the cloud response instead of 5. The setting hint is corrected: Wi-Fi is required, and cars on newer BYD servers also need a Chinese VPN, preferably on the router (#310).
- Navigation prompts on the instrument panel in DiLink 3.0 cars: distance to the manoeuvre and remaining route are now shown instead of “-1”, and travel time is shown as “3 min”, without the Chinese sign “分” (#301).
- The manoeuvre arrow on the HUD and instrument panel no longer remains next to the next-manoeuvre distance after a completed turn. In a stationary car, it remains until the navigator speaks again (#294, #301).
- Widget on cars where the instrument panel and projection displays run in their own apps (Leopard 8, DiLink 5.1): the app treated their background windows as an app change on the central screen. As a result, the widget could disappear in “tylko na ekranie głównym” mode, or without that mode appear over YouTube or an app in the “ukryj w tych aplikacjach” list. An app change now counts only when its window is actually visible on the central screen; camera behaviour is unchanged (#303).

## [3.20.0] - 2026-10-08

### Added
- A Telegram bot can now be connected to a private group, not only a personal chat. Add the bot to the group and send `/start@nazwa_bota KOD`. Reports and data copies then reach everyone in the group, for example a second driver (#307).
- Automation: climate temperature, fan, seat heating and ventilation, windows, refrigerator, and volume are configured in one row with a slider and “minus” and “plus” buttons instead of separate tiles. Temperature is 16–33 °C (33 is available on Seagull; thanks to BASM for PR #300), fan 1–7, seats “off” and 1–5, window 0–100% in steps of 10, refrigerator −6 to +6 °C for cooling and 35–50 °C for heating. Existing rules open in the new form with their previous value (#305).
- Waze as the route navigator: select it in Settings under “Nawigator tras”. The “Trasa” action, voice commands, and map search open it by direct link; if Waze is not installed, the route goes to Yandex Navigator. The voice agent now waits for the app receiving the route; it previously treated a route built in 2GIS as failed (#305).
- BYD Cloud over Wi-Fi: a new setting below “Przywróć ADB” for cars with a native Chinese SIM but no mobile connection. While the car has Wi-Fi internet, the BYD phone app can see it: OTA updates, NFC key, and phone control work. The setting does not affect cars with their own mobile connection and restores the previous connection settings when disabled (#310).

### Fixed
- 2GIS output to the instrument panel from split screen: if 2GIS restarted during transfer, the panel stayed dark, output was still marked enabled, and on return 2GIS went back to half-screen. After 3 seconds, the app now verifies that 2GIS is on the panel; otherwise it disables output, and on return opens the app full-screen (#288).
- Blind-spot camera on Yuan Up (DiLink 3.0): the app no longer treats the camera as unavailable when its classes are in a separate firmware file. It loads them automatically; the camera log shows whether the file was found (#299).
- The “Media: play” action no longer wakes the native Chinese player left paused after another app, such as radio, began playing. If that app is no longer running, the action reports “Brak uruchomionego odtwarzacza” (#275).
- A voice agent using a female voice no longer says male-form confirmations: predefined confirmation phrases now match the selected assistant gender (#306).
- Cars without an HUD gateway (no windscreen output, for example DiLink 3.0): with “Projekcja na szybę (HUD)” enabled, Yandex Navigator prompts now go to the native navigation card on the instrument panel. This switch previously did nothing on these cars. Cars with an HUD gateway are unchanged (#301).

## [3.19.9] - 2026-10-06

### Added
- Windscreen HUD can be enabled and disabled from automation (“HUD: włącz” and “HUD: wyłącz” in “Światła”) and by voice: “włącz HUD”, “wyłącz HUD”, “włącz projekcję na szybie”, or “wyłącz projekcję na szybie”. It works while driving, for example to turn off HUD on a dark road or by time of day. On a car without HUD, the action reports that HUD is unavailable (#292).

### Fixed
- “Media: pause” and “Media: play” automation actions no longer start the native Chinese player when nothing is playing; when it is paused, they control it (#275).
- Navigator output to the instrument panel from split screen: if Navigator occupied half the split screen, the panel stayed dark while output was marked enabled. The app now transfers Navigator to the panel with a separate command and disables output if the transfer fails (#288).
- The blind-spot camera now activates only with a turn signal and no longer runs continuously while driving, reducing head-unit load.
- When output to the instrument panel is requested before the app starts, the panel image no longer enlarges after a failed first start and the app window no longer remains partial on the main screen. If the app does not remain on the panel, it opens full-screen on the main screen and output is disabled.

## [3.19.8] - 2026-10-06

### Added
- The floating widget can be shown only on the Home screen; it hides itself in other apps (#296).

### Fixed
- TRIP 1 and TRIP 2 are included in Telegram reports even without reset. A counter never reset is labelled “od początku”, as on Home. It previously did not appear in the report despite being selected.
- Currency selection moved to Settings > “Aplikacja” > “Jednostki i waluta”, where users expect it; it was previously in “Bateria i taryfy” (#293).
- Android 10: “Uruchom BYDMate” rules no longer run a second time when the app restarts itself to restore steering-wheel buttons.
- The track is now visible on the instrument panel in cars with the new panel interface (Leopard 3 and others after update); the card was previously empty. Other cars are unchanged.
- Windscreen HUD no longer keeps an old arrow after a street change, or shows Chinese text instead of metres at the turn (#294).
- The diagnostic log has more detail on windscreen and instrument-panel prompts, automation, and steering-wheel buttons, while being much smaller and reducing head-unit load during recording.

## [3.19.7] - 2026-10-05

### Added
- The instrument-panel track is shown not only for Yandex: title and artist come from any player for which native firmware leaves the card empty (Spotify, internet radio, Yandex Music mods, and others). Native players and Bluetooth are still filled by the firmware itself. The switch is renamed “Utwór na zegarach”. Thanks to @vladh91 (#289).

### Fixed
- On hybrids, “Pojazd” now shows a dash instead of remaining 0 kWh when the car does not report this data.
- The “Zasilanie” automation condition was removed because its rules almost never ran. Saved rules were migrated: “włączone” and “jazda” became “Uruchom BYDMate”, and “Roleta podczas jazdy” waits for gear D. Rules that could run only while the car was off were removed. “Zimowy start” and “Letnie chłodzenie” templates now run when BYDMate starts.
- The instrument-panel music card no longer flickers between playing internet radio and paused Yandex Music, or shows an old paused Yandex track while another player is playing or has just played. When no player is playing, the card remains with the last player. Thanks to @vladh91 (#96, #289).
- While native 360 view is open, the blind-spot camera turns off and does not load the head unit. It previously kept running in the background and could slow 360 view.
- In “Rozszerzony” projection mode, shrinking the instrument-panel image is no longer disabled after one accidental app close. The app first restarts with shrinking; only a second close outputs without it.
- When Yandex Navigator is on the instrument panel, the windscreen arrow and distance are now readable even when Navigator shows a different prompt form before a turn. Previously, the arrow reset and distance froze then (#199).
- The description of one-third/two-thirds screen split now states that the native split button still divides the screen in half (#284).

## [3.19.6] - 2026-10-02

### Added
- Track title and artist from Yandex Music and music in Yandex Navigator (Alice) appear in the instrument-panel music card. The car previously left this card empty. Enable it with the “Utwór Yandex na zegarach” switch in Settings > “Wyświetlacz”; it is disabled by default. Thanks to @vladh91 (#272). The final result has not yet been verified in cars.
- HUD method 3 sends more to the car. In addition to arrow, metres, and street, it now sends remaining route time and distance and arrival time; it also sends arrow, metres, and street by a second route. If method 2 shows metres and street but no arrow on the windscreen, try method 3. Methods 1 and 2 are unchanged. Not yet verified in cars.
- HUD understands more Navigator manoeuvres: English prompts, a roundabout exit stated in words (“drugi zjazd”), “ostro w lewo”, “łagodnie w prawo”, “rondo”, and others. Existing supported manoeuvres are read as before. Not yet verified in cars.
- Logs record more detail on why a windscreen prompt disappeared or an arrow did not appear.

### Fixed
- HUD no longer turns off 10 seconds after the app stops seeing a prompt in the Navigator window; it now waits up to 90 seconds. After route cancellation, the last distance and street can therefore remain on the windscreen for up to 90 seconds. Not yet verified in cars (#199).
- On cars without Google services, the Navigator notification “Zainstaluj usługi Google Play” no longer enables HUD without a route or appears on the windscreen instead of the street (#198).
- “Test HUD”: if the test does not start or is interrupted (route active, car moving, or no car connection), the app reports this in a separate window and explains what to do. Previously only small text below the button reported it.

## [3.19.5] - 2026-10-01

### Added
- Optional TRIP 1 and TRIP 2 counters in Telegram reports (separate checkboxes).
- An automation rule can now contain up to 20 actions, increased from 10.
- New “Zamknij aplikację” automation action: the rule fully closes the selected app. Any unsaved route or music in it is lost (#280).
- New “Media: play” and “Media: pause” automation actions: a rule pauses or resumes what is playing in an already-running player. If no player is running, the step does not run and the log shows this. To start a player, put “Uruchom aplikację” before this step (#212, #275).

### Fixed
- HUD methods 2 and 3: if the app closed during a route, on its next start it now clears navigation status even on cars where it could not read the instrument-panel layout. The navigation card could previously remain over the projection.
- HUD methods 2 and 3: the “łagodnie w prawo” manoeuvre now appears as a right arrow on the instrument panel and windscreen. The car previously drew it as “łagodnie w lewo”.
- Instrument-panel output on Android 12 cars where the app cannot itself see the panel screen (for example Han and Tang 2024): the screen is now found. Output previously stopped immediately because it was not found. Not yet verified in cars (#199).

## [3.19.4] - 2026-09-30

### Added
- The Polish commands “włącz Yandex Music”, “uruchom Yandex Music”, and “Yandex Music” start “My Wave” directly, without the cloud agent.
- HUD settings now provide three windscreen-guidance methods instead of two. Method 1 is the default and works as before: guidance goes to the windscreen and the app does not touch the instrument panel. Method 2 also sends the arrow, distance, and street (in Latin script) to the panel navigation card. Experimental method 3 adds the path OpenBYD uses by default. Methods 2 and 3 have not yet been tested in cars. The app clears all windscreen and panel output when a route ends, the method changes, or HUD is disabled.
- HUD Check has a fourth step for the additional output method. The windscreen shows 111, 222, 333, and 444 in turn; the check asks which number was visible and selects the method. If nothing appeared, the app asks for a log.

### Fixed
- After HUD Check, the test distance of 333 m is cleared from the windscreen as the car expects. It could remain until the car was switched off. The arrow at the 333 m step now also points left as intended.
- A window no longer lowers itself after parking when the car is switched on again. Repeated Polish “wentylacja” and “połowa” commands now work first time: after every partial-window command, the app clears it from the car as the stock BYD voice assistant does.

## [3.19.3] - 2026-09-29

### Added
- Voice-agent settings now include the off-by-default “Close agent after a completed command” switch. When enabled, the agent closes after completing a command or replying; if it asks a question, it waits for the answer (#226).
- The agent can be closed with Polish phrases such as “zamknij agenta”, “wyłącz się”, or “wszystko, dziękuję, odpocznij”. The request goes to the model, so it needs internet access.

### Fixed
- Log recordings now include blind-spot camera activity: switch changes, camera open and close events and reasons, received camera events, and lane-change-assist unavailability. Camera-journal times now use ordinary clock time.
- Repeating a partial-window command (vent or half-open) now works where the car previously ignored it.
- The Polish phrases “klimat auto” and “klimat automatyczny” now switch climate to automatic mode, rather than only turning it on (#268).
- HUD Check now clears its test road name on cars that previously did not clear it.
- App and camera projection to the instrument panel works again during a route, as in 3.19.0. The HUD card now offers a mode: default mode 1 leaves the panel untouched; mode 2 puts the car into navigation mode for the route and may help if mode 1 leaves the windscreen empty (#266).
- A steering-wheel button assigned only to automation works again after an ignition cycle on Android 10 head units (#262).
- On Android 10 head units, the widget no longer disappears for half a minute after the car starts; the app returns within seconds.

## [3.19.2] - 2026-09-29

### Added
- The assistant now answers simple vehicle questions immediately from car data: outside and cabin temperature, charge, range, and climate cost. If the car does not provide a value, the question still goes to the cloud assistant.

### Fixed
- A steering-wheel button that sends two key codes per press is now assigned to the assistant as one button. The app learns both codes; the first starts the assistant and the second no longer reaches the stock system. Reassign the button in Voice settings.
- Log recordings now include every steering-wheel button press, including presses the app does not intercept, so the sent codes are visible.
- OpenRouter keys, like other connection values, are saved without accidental leading or trailing whitespace and line breaks.
- Logs no longer repeatedly report releasing a long-gone old screen when projecting a navigator to the instrument panel (#259).
- The automation “Project to instrument panel” step now waits up to 15 seconds for an image. It previously waited 5 seconds and reported an error during a slow navigator launch even though projection worked (#263).
- The HUD Check button moved to HUD settings beside the “Navigation on HUD” switch. Its description states that windscreen text during the check is temporary and disappears afterwards.
- HUD settings now explain that the left side of the instrument panel shows a navigation arrow while a route is active.
- After HUD Check, log recordings show whether the car accepted removal of the test text.
- Log recordings exclude typing keys on a connected keyboard: letters, digits, and symbols. Steering-wheel buttons, volume, and music controls are recorded as before.
- Log recordings now show what launched the app and when it restarted itself to restore the steering-wheel button, so delayed startup after ignition is visible.

## [3.19.1] - 2026-09-28

### Added
- Telegram reports now use separate blocks for vehicle state, last trip, and open doors, with an unexpanded map link at the end.
- Telegram reports now include the odometer by default, including existing power-off reports. Remove it in “What is included in the report”; existing automations are unchanged.
- A silent map pin now follows a location report and opens in phone maps with one tap. The report’s map link remains.
- Log recording now retains a short pre-recording event history, so an earlier error cause is visible.
- The assistant finds nearby and along-route chargers. In Belarus it reports availability, power, and price; select one to navigate to it.
- Car & Battery settings now let you select GB/T, CCS2, Type2, or CHAdeMO; the assistant uses that connector when searching.
- Service & Data now has HUD Check: it shows test guidance on the windscreen for 90 seconds without a route. Record logs, film the windscreen, and send both to identify the working method.

### Fixed
- Empty day and week trip lists now say there are no trips yet and the current trip appears after it ends.
- Trip lists show the end time again at every text size.
- Unqualified seat heat and ventilation commands use level 1 (#254).
- The assistant again hears a phrase started immediately after its reply. Naming it can interrupt while it is thinking, not while it is speaking.
- The widget and blind-spot-camera window no longer disappear behind the parking-sensor window.
- “Where am I?” and nearby-charger search work again. When the primary map server is overloaded, the fallback reports locality and street without distance to the centre.
- On DiLink 3.0/4.0, the ready tone now plays only when the assistant is ready to listen; “Thinking” is shown before that.
- On DiLink 3.0/4.0, assistant speech uses navigation volume and no longer changes music volume.
- On DiLink 3.0/4.0, “Disable stock voice assistant” also disables BYD’s stock voice window; use the same switch to restore it.
- The app starts itself after an update.
- Before disabling the stock assistant, the app explains how to restore it before uninstalling BYDMate or after reinstalling.
- The app enables HUD navigation mode during a route and restores the previous state afterwards.
- Windscreen manoeuvre arrows no longer vanish after 30 seconds while stopped at a light or in traffic.
- HUD Check stops when the car moves, immediately removes test guidance, and restores HUD settings. It also restores the prior HUD state after an ignition interruption or an ending panel camera, even when HUD navigation is disabled or unavailable.

## [3.19.0] - 2026-09-27

### Added
- The assistant answers “where am I?” and “what is nearby?” with mapped nearby villages and cities, distance, and direction; it says when it cannot determine a location.
- Name the assistant at the start of a phrase to interrupt its speech and make it listen; speech spoken during its reply is ignored.
- Drive mode can be changed from automations, Polish voice commands, and the assistant: ECO, Normal, and Sport anytime; Snow, Sand, Mud, Mountain, and Smart below 15 km/h. The app verifies the change and reports unsupported modes. The “Eco at low charge” template returns (#253).
- Power-off Telegram reports can be enabled and configured in Settings. They send seconds after power-off, or at the next start without connectivity. Automations now include “Send Telegram report”.
- “Both cameras on instrument panel” shows both blind-spot cameras on the panel and leaves the main screen free (#240).
- Automation is clearer: rules are written out, show their last result, and have individual logs; the condition/action editor and confirmation dialog are larger and simpler.
- The one-time “Date and time” condition runs a rule once, then disables it.
- Conditions now include rear-passenger seat belts.

### Fixed
- The assistant now knows the current time.
- When it no longer remembers the start of a long conversation, it says so instead of denying the question existed.
- Short phrases before a long search are simpler and clearer; saved online-voice clips are resynthesized once.
- Outside temperature is retained for all trips, including trips closed at the next start (#250).
- App-recorded trips no longer disappear if the app restarts during the journey.
- The automation Drive Mode condition no longer labels Snow as Off-road.
- Android 10 now clearly states that post-reboot ADB restoration is unavailable and points to the ADB guide (#251).
- The Boot condition distinguishes open, closed, and moving.
- The Leopard 3 front-passenger seat-belt condition reads the same signal as the panel indicator.
- Opening windows, sunroof, and boot via automations, voice, or the assistant checks current speed and refuses if it cannot read it.
- Diagnostic logs no longer contain phone numbers, coordinates, or links from your rules.

## [3.18.2] - 2026-09-25

### New
- You can arrange automations in your preferred order: long-press a rule to lift it, then drag it to a new position. This works in both list and grid views, and the order is saved (#249).
- The floating widget hides while vehicle settings are open and returns when they close.
- The voice assistant responds faster. With a direct MiniMax connection, it starts speaking the reply before the whole sentence is synthesized. Service connections are prepared as soon as you press the microphone button. Short assistant phrases with the online voice are synthesized once and stored in vehicle memory; they are regenerated after you change the voice or persona.
- Before commands that take longer than usual, the assistant says a short phrase to avoid a silent pause.
- For new users, the default assistant model is Gemini 3.5 Flash Lite.

### Fixed
- The assistant on Gemini models responds about one second faster: an unnecessary delay on each question was removed.
- If a model returns an empty response with an error, the assistant retries the request automatically.
- The “ADB is not enabled” hint and the ADB step in the first-run wizard described enabling it through Android settings, which does not work in BYD vehicles. They now link to the GitHub instructions and clearly say that only trip history is available without ADB. The ADB-recovery help after a restart no longer asks you to press ALLOW manually: when recovery is enabled, BYDMate confirms this dialog itself.
- Automation error reasons (test run, rule log, and the start button for an individual action) are shown in the interface language; they were previously always in Russian (#242).
- The “Week”, “Month”, and “Year” periods on Home, Trips, and Charging are now calendar periods: the week starts on Monday, the month on the first day, and the year on 1 January. Previously, the week started on the same weekday as today, while the month and year on Home and Trips were calculated from today’s date.
- Trip window: group labels were replaced with thin lines, the number column needs less space, and scrolling it is needed less often with large text.

## [3.18.1] - 2026-09-25

### New
- Home: there are always six recent trips with normal and large text, and the list ends level with the TRIP cards. The bottom tab bar is shorter, leaving more space on every screen.
- Automation: rules can be viewed as a compact list or three-column cards; the switch is in the header and your choice is remembered. Each rule shows how often and when it ran. The “Edytuj”, “Duplikuj”, “Udostępnij”, and “Usuń” buttons are large and labeled; on a narrow screen, labels change to icons.
- Trips: the trip window was redesigned. Its header shows the date and weekday and includes a close button. The map is narrower, the number column is wider, and rows are grouped by distance, time, energy, money, and weather. New data: odometer at start and finish, driving time, cost per 100 km, charge change in percent, and outside temperature at the start and finish. The odometer is recorded only for new trips (#246).

### Fixed
- The “Start BYDMate” trigger runs every time the vehicle starts, including after a short stop. If the app starts before the display turns on, the trigger waits for the display.
- The “BYDMate assistant did not start” window shows where to enable log recording instead of a button that does not exist in the app.
- Settings → Widget: if split mode is disabled and the widget’s left tap remains in split mode, you can switch it back to launch the app (#188).
- Windows: on vehicles where the window position cannot be read, the command to open a window to a percentage is no longer repeated with a 1% adjustment (#242).

## [3.18.0] - 2026-09-24

### New
- Automation and voice: an “Ogrzewanie kierownicy” action can turn the steering-wheel heating on, off, or toggle it (on vehicles with steering-wheel heating; the result is checked by reading its state, and on a vehicle without heating the action clearly reports this). The command has not been tested on a real vehicle with steering-wheel heating; feedback is welcome.
- Technical: “maks. moc rozładowania” was added to “Limity BMS” (the same value shown by diagnostic scanners), “dostępna moc baterii” remains, and the hint explains that all three values are read from the BMS and how they differ (#241).
- The status line on Home and in Settings now shows why vehicle control is unavailable: ADB is not enabled, ADB was disabled after a restart, ADB access is unavailable, or the assistant did not start; tapping it opens a hint with an action. The first-run wizard now has an “ADB” step that checks the connection.
- Settings → Text size: normal, large, or extra large for app screens and voice-agent windows (the widget and instrument panel do not change).
- Home: TRIP 1 and TRIP 2 counters can reset automatically after charging: after any session, AC only, DC only, or only after charging to 100%. Each counter has its own setting, selected directly in its window; long-press manual reset remains (#235).
- Settings → Configuration: one “Zapisz konfigurację” button replaces CSV export, configuration export, and manual backup. You can select what to save: tables (trips, charging), vehicle and app settings with tariffs, and keys and tokens; keys are not saved by default. The file is placed in Download, and a copy is also sent there if a Telegram bot is connected. During restore, you can restore only the required parts and leave the rest unchanged (#238).
- Scheduled automatic backup (daily, weekly, or monthly) when the vehicle starts: Download keeps the five latest copies, each is sent to your Telegram bot, and chips select the backup contents (#237, #238).
- The Telegram bot connects step by step: token, code, done; errors appear directly under the button.
- Log recording, the sensor recorder, and the FID catalog now have “?” hints that explain their purpose and use.
- After restoring a backup on a clean installation, the app restores permission to show the widget over other apps and displays the remaining items (microphone, contacts, voice models, and head-unit restart) in one window.
- Automation: the “Miejsca” button moved from Settings to the screen header next to “Dziennik”; you can now add and edit points there.
- You can share an automation: the “Udostępnij” button on the card and in the editor first shows what will go into the file, then “Kontynuuj” saves the rule as bydmate_rule_<name>.json in Download and opens the system “Udostępnij” window; a toast shows the file name. Phone numbers, contact names, and links to your places are excluded. Links have logins with passwords and key-like parameters removed (token, api_key, sig, and similar; normal parameters remain); check the address before sending. javascript:, data:, empty, and unparseable links are saved empty. Notification text and phrases, agent requests, and route points remain and should be checked before sending.
- The “Importuj” button adds an automation from this file: it first shows the rule with its actual parameters—“wszystkie warunki” or “dowolny warunek”—and, for actions, the number and auto-dial, link address, vehicle command, request text, where the route will actually go (home, work, search, or point), and what happens after it starts (“Jedziemy” is promised only where the app taps it itself). It asks you to select a place and contact if they were not found, and adds the rule disabled by default. A rule with a link whose address must be entered again is added only disabled; if key-like parameters were removed from the link, the window asks you to check the address. Files with excessive nesting or overly long lines are rejected.
- The automation editor now has a “Test run” button: it executes all actions immediately without waiting for the condition and does not count the run. Opening windows, the sunroof, and the front trunk, and unlocking doors without fresh speed data (no older than 10 seconds while the service is running) are skipped; an unrecognized command is skipped with an explanation; closing the editor stops the run.
- Voice: the voice-command log is now retained between restarts and included in the dump with what the head unit heard, the command it became, and why it was refused.
- Voice: an automation phrase runs only when spoken in full; words such as “proszę” do not interfere.
- Voice: offline commands are now only in Polish; the English vocabulary was removed.
- Voice: built-in commands are now a fixed list of frequent Polish phrases: windows and sunroof, seat heating and ventilation, steering wheel, mirrors, climate, fan, volume, locks, trunk, and lights. A phrase runs offline only if spoken in full and exactly as in the list; polite words such as “proszę” do not interfere. Everything else goes to the cloud agent when it is enabled; without it, the response is “nie rozumiem”.
- Voice: “tryb wietrzenia” (also with “włącz”) opens all windows for ventilation and raises the sunroof; “wyłącz tryb wietrzenia” closes the windows and sunroof.
- Voice: windows and the sunroof open by a fraction (“do połowy”, “na 50 procent”, “odrobinę”, “całkowicie”); the percentage is rounded down to the nearest supported position, and a window never opens wider than stated.

### Fixed
- Voice: a command from the list went to the cloud if the previous agent response ended with a question: an agent joke after a completed command opened the clarification window. The clarification window now opens only for a real question, when the agent asked instead of performing an action.
- Windows: the command to open a window to a percentage (“ventilate”, “halfway”) disappeared if the previous command for that window had the same percentage: the vehicle accepted the write but the glass did not move. If the glass does not move, the command is now repeated once with a value 1% higher, and the window moves.
- Home: the recent-trip list shows only fully visible rows; there is no longer a cut-off bottom row at any text size.
- The “?” hint icon in Technical and Settings no longer protrudes from its circle with large text (a vector icon is used instead of a letter).
- Voice: the built-in command vocabulary did not load on the vehicle (the loader’s regular expression was accepted by the JVM but rejected by the Android engine), so all phrases went to the agent; this is fixed, and the log records the load result, startup self-check, and words for which no command was found.
- Widget: if no kWh has yet been counted for the trip, the previous trip’s average is shown instead of “0.0 kWh/100 km”, as it is when the BMS counter fails.
- ADB recovery: the system “Allow Wi-Fi debugging” dialog is now confirmed automatically when the recovery toggle is enabled (on phone hotspots the MAC address changes at every activation, so “Always allow” did not help).
- Technical: the “Remaining” and “Battery health” hints explain that the vehicle calculates both values itself and why they do not match.
- Old update APKs in the Download folder are removed when the app starts. The installed version’s file and newer files remain.
- Backup now includes the ADB-recovery toggle, blind spots, split mode, and HUD; internal projection markers for the instrument panel are no longer included.
- Statuses and messages generated by the app in the background (Telegram-bot check, log recording, automations, and voice) are now in the app language rather than the system language.
- Automation: editing a disabled rule no longer enables it, and saving a rule that was already deleted no longer recreates it silently: the app says “Rule already deleted” and offers to save the draft as a new rule or close the editor.
- The “Start BYDMate” trigger runs again after every vehicle start: vehicle shutdown is recorded by the ACC_OFF signal, startup is determined by the tablet display turning on rather than system sleep, and a long pause in rule processing while the display is on no longer starts it mid-trip (#177).
- Voice: offline commands work with any interface language; the model understands Polish speech, and the “choose language: Polish” message was removed.

## [3.17.5] - 2026-09-22

### New
- First-run wizard: select the app language first, then only battery capacity, currency, and two base tariffs (home and fast charging); autostart is the final step (#227).

### Fixed
- Settings: restoring from a backup works again on DiLink 3.0. Instead of the system file picker, the app shows its own archives from the “Pobrane” folder (#223).
- Automations: a rule with the “Start BYDMate” trigger no longer runs again when the system restarts the app mid-trip; it still runs again after an actual vehicle start, even if the app survived sleep (#177).
- Battery: “Full cycles” on the Technical and Charging screens is calculated with battery health (SoH) taken into account; the label shows the capacity used for the division (#224).
- Diagnostics: the log records every frame sent to the HUD when the maneuver changes, with the firmware response code; previously a single log could not show whether frames reached the windshield (#198).

## [3.17.4] - 2026-09-20

### New
- Remaining battery energy in kWh from BMS data.
- A sample of internal-combustion-engine parameters for hybrids was added to the diagnostic dump (#184).

### Fixed
- Downloading the speech-recognition model no longer looks stuck at 99%. Archive extraction is now a separate stage with its own progress and “Rozpakowywanie…” label: on DiLink 3 it genuinely takes several minutes, whereas the bar previously stayed at 99% throughout without any sign of activity.
- Free space is checked before downloading a model. If there is too little, the app immediately says how much is required and available instead of downloading 226 MB and failing with an unclear error at the end.
- Blind spots: camera tags are requested from the firmware itself if known tags do not work; the camera-search log is in the dump.
- Blind spots: after a camera error, the 3-second pause remains while the turn signal is held (windows no longer flicker); windows are not shown on vehicles without cameras.

## [3.17.3] - 2026-09-18

### Fixed
- Blind spots: the camera opens again on vehicles where the firmware keeps speed at a different address (Song with DiLink 4.0). Previously it stayed silent and wrote nothing to the log on these vehicles.
- Battery: “Full cycles” is now calculated from the BMS counter rather than charging sessions recorded by the app, and the row moved to the “Battery · history” card. The value was underestimated several times on vehicles with high mileage.
- Windows: the right rear window on DiLink 3.0 (Yuan UP) can be read again; its address from the firmware catalog is no longer discarded.

## [3.17.2] - 2026-09-18

### Fixed
- Automations: when selecting a value from a list (gear, turn signal, and others), the selected operator no longer resets to “equals”.
- Voice: a rule started by voice no longer stops when the assistant window disappears; the pause and subsequent actions complete, and the assistant says “Wykonuję” at the start.
- Diagnostics: the dump now contains a “blind spots” block (enabled, speed threshold, armed state and reason, last turn signal).
- Trips: outside temperature at the start and finish is now saved for trips from the vehicle’s onboard database (Leopard 3 and other vehicles with energydata); it was previously not recorded, so the “Consumption and temperature” screen had no trips.

## [3.17.1] - 2026-09-17

### New
- Settings: Yandex Maps was added to “Navigator for routes”. Voice routes, searches, and points without the words “in Maps” go to the selected app (#200).

### Fixed
- Automations: rules for gear, turn signals, doors, windows, trunks, seat belts, and other sensors are checked when the vehicle event occurs rather than by polling; hazard lights for reverse gear turn on without a delay of up to five seconds.
- Settings: “Tariffs and periods” opens as a separate Technical-style screen with all fields visible without scrolling and closes with “Back”.

## [3.17.0] - 2026-09-16

### New
- Tariffs are now a price history: Settings → “Tariffs and periods” opens in a separate window, with each period having its own start date, home and fast-charging price, losses, and trip-calculation rule.
- You can change a tariff retrospectively: add a period with the required date, and all later charging sessions and trips recalculate automatically.
- Charging cost includes losses: less energy enters the vehicle than the meter records—10% for home charging and 5% for fast charging by default.
- The charging card now has a “Meter” field: enter a reading and this charging session is calculated from it. From several such measurements, the app suggests your actual losses.
- The “Calculate by period” button in the charging card returns a record with a manual price to the general calculation.
- CSV export: trips and charging sessions now include the tariff and losses; tariff periods export to a separate file.
- Automations: “Toggle” is available for security mode: one action arms and disarms the vehicle.
- Automations: “Toggle” is no longer a separate item in the action list. It is now a third option next to the action itself: the command list shows “Trunk: toggle”, while security mode and instrument-panel output have a “Toggle” button next to “On” and “Off”. Previously created rules keep working as before.
- Home: one slot shows both temperatures, cabin and outside (#210). If the vehicle does not report outside temperature, the slot looks as before.
- The voice assistant can navigate with Yandex Maps: say “in Maps” in a route, search, or show-point command (#200; from a user patch—check Maps operation). Without those words, everything goes to Navigator as before.

### Fixed
- The “Recalculate all trips” button was removed: recalculation starts automatically when the tariff changes.

## [3.16.0] - 2026-09-16

### New
- Voice, agent, and automations: “halfway” and “ventilate” now work for any individual window and for front/rear pairs.
- Blind-spot cameras: the on-screen camera window can be vertical; use the “Vertical camera window” toggle in camera settings (#207).
- Automations: a “Toggle” action for the trunk, front trunk, sunroof, locks, and instrument-panel output: one widget button opens and closes. The state comes from the vehicle.
- Widget: extended buttons can show an icon instead of a number. Select the icon in a rule with the “Button press” trigger.
- The app reacts to turn signals, gear, blind-spot sensors, doors, windows, and trunks when the vehicle reports them.
- Routes in split screen: the assistant collapses split view, builds the route, and restores the panels.
- Say “jedziemy do pracy” and the assistant presses “Jedziemy” for you. With “wyznacz trasę do domu”, it only builds the route and you press the button yourself.
- ABRP receives the set climate temperature, battery voltage and current, and, when charging, the actual charging power with a minus sign.
- Technical: the battery card now has a “Full cycles” row that states which sum it uses for its calculation.
- Trips: the “Temperature” chip opens the “Consumption and temperature” screen—one point per trip, medians for cold, mild, and warm weather, and a single conclusion sentence.
- Trips now save outside temperature at the start and finish, and route points save altitude above sea level.
- Automation: long command and parameter lists are collapsed by section. Only the section containing the current selection is open; tap another heading to expand it.
- Automation: the “Toggle” action now supports hazard lights, climate, and driver and passenger seat heating and ventilation. A switched-off seat turns on at the last level you selected.

### Fixed
- Charging on a running vehicle is recorded again: the plug is now detected by two additional sensors rather than only the previous one, which is silent when the vehicle is running.
- Windows: if an “open/close” command is accepted but the glass does not move, the app repeats it automatically as a percentage command (DiLink 3.0, #197).
- Blind-spot cameras: the camera window leaves the screen as soon as the native 360 view appears.
- ABRP no longer receives coordinates from the app: the car on the map will stop jumping between our point and the head unit’s GPS. The “Send coordinates to ABRP” setting was removed; the webhook remains unchanged.

## [3.15.5] - 2026-09-14

### New
- Technical: you can arrange cards in your preferred order. Hold a card and drag it onto another card’s position; the order is saved.
- Tariffs: AMD currency was added (֏, Armenian dram) (#205).
- Automation: “Fan 1…7” actions and airflow direction (face / feet / windshield) are available in rules and to the agent (#201).

### Fixed
- Technical: the “Motors and inverters” card now shows temperatures, RPM, and pedals at the top, while the front and rear motor shares moved down next to the percentages.
- The voice assistant responds faster: the model request is smaller, the constant part of the prompt is cached by the service, and the request goes to the fastest model provider.
- New users get the faster and smarter Gemini 3.8 Flash model by default (nothing changes for users who already selected a model).
- Log recording now includes a list of automation rules—their conditions and action types—to investigate “rule does not run” reports (#177).
- Log recording now includes a voice-assistant section: recent requests, commands it ran, and its replies, plus the model and connection. This helps investigate “the assistant said done, but nothing happened” reports.
- The assistant no longer says “done” if the window did not move: after a window command, the app checks whether it moved and clearly reports which window failed (#97). All windows are checked at once, so “close windows” takes almost no longer.
- The assistant clearly replies if instrument-panel projection did not turn on and no longer stays silent for up to fifteen seconds waiting for an instrument-panel response. It also explains why split panels could not be swapped.
- The assistant sees more vehicle data: front and rear motor share, BMS limits, tire temperatures, current airflow direction, and blind-spot camera state.
- The assistant can add “Go to home screen” and “YouTube” actions to rules.
- The assistant does not infer automations: if the trigger, threshold, or action is unclear, it asks one clarifying question and, after creating the rule, states it in full.
- The assistant remembers today’s latest conversations: you can return to the vehicle after a couple of hours and ask “what was the distance home?”. The day’s memory clears automatically the next day.
- In Settings, you can forget one fact about the driver rather than only the entire list at once.
- The assistant response is no longer limited to three lines: its panel grows for a long response, up to half the screen. If voice is disabled, the panel stays longer so you can read the reply.
- If a model reaches its length limit, the reply ends with an ellipsis and the logs show the reason it stopped.
- When the assistant is interrupted by name, the unfinished reply disappears from the screen rather than remaining as a fragment.
- Voice-settings hints explain what to do; README: agent setup by screen steps.

## [3.15.4] - 2026-09-14

### New
- Technical: the “Motors and inverters” card has a “power” row with front and rear motor shares in percent (dual-motor vehicles).
- Blind-spot cameras hide while the native 360 view is on screen and return after it closes if the turn signal is still on.

### Fixed
- Technical: the “BMS limits” hint explains why acceleration power is higher than “available” power.

## [3.15.3] - 2026-09-12

### New
- Settings: select Yandex Navigator or 2GIS for routes and map searches (#190).

### Fixed
- Instrument-panel output and window operation on DiLink 3.0/4.0 (Android 10): the app did not find running apps and could not move Navigator to the instrument panel.
- Technical: on vehicles without a rear motor, rear-motor and inverter temperature showed -40; a dash is now shown.
- Trips: if firmware recorded impossible consumption for a trip (tens of thousands of kWh in one trip), the app no longer uses that value in statistics and calculates consumption from charge change. Saved trips recalculate once after the update; weekly, monthly, and yearly statistics and insights no longer “explode”.
- The floating widget hides while a blind-spot camera is shown on the home screen and returns when the camera closes.
- Home: “ADB is not responding” was replaced with “Vehicle data unavailable”; it refers to reading vehicle data, not the ADB connection.
- Song Plus and other DiLink 3.0/4.0 vehicles: parameter addresses now come from the firmware catalog rather than Leopard 3 constants, so charge, mileage, and other vehicle data appear on Home and in Technical; if the catalog cannot be read at startup, the app retries automatically.
- Song Plus and other vehicles whose mileage address comes from the firmware catalog: mileage was shown ten times lower (8647 instead of 86472 km); scaling is now selected by address.
- DiLink 3.0 (Yuan Plus, Song Plus, and similar): if the app service started later than usual, its response was discarded and vehicle commands (seats, shade, windows, climate) stopped working until restart; late responses are now accepted.
- DiLink 3.0 (Yuan Plus, Song Plus, and similar): after the app restarted, vehicle commands (seats, shade, windows, climate) stopped working until the tablet restarted; the app now reattaches to the already running command service without restarting it.
- Yuan Plus and other DiLink 3.0 vehicles: the app no longer grants itself Settings permission at every startup when it is already granted; the extra command prevented the command service from starting (#64). Log recording now includes ADB connection lines, which were previously lost because of an incorrect tag name.

## [3.15.2] - 2026-09-11

### New
- Tariffs: KGS currency (som) was added at a request from Kyrgyzstan (#191).

### Fixed
- ADB recovery: the app obtains the required permission on any live ADB connection, not only at startup; the system response is written to the log if permission is not granted.
- Instrument panel: on DiLink 4.0 (Song Plus 2021 and similar), the app now finds the instrument-panel display that firmware hides from apps and sends Navigator to it directly. If firmware does not let the window be pinned on the first attempt, the app tries again after moving it.

## [3.15.1] - 2026-09-10

### New
- Blind spots: the left camera window can now be positioned separately. By default it mirrors the right window; the “Left camera position” button under the “Both cameras on home screen” toggle sets its position. Both windows share the same width.

### Fixed
- Instrument panel: Navigator is sent to the instrument panel and returns to the home screen without restart when firmware permits it. Previously, on some vehicles (Sea Lion 07), Navigator restarted and lost the route when sent. If a soft return is not confirmed, the app returns Navigator by the previous method with a restart.
- Voice commands: turning on heating for one seat by the driver’s or passenger’s name no longer turns on both seats at once.
- Widget range no longer drops to zero because of one faulty consumption measurement.
- Windows: “open” and “close” commands are now sent to the vehicle as separate commands rather than as 100% and 0% positions. This should help on vehicles where windows did not respond to app commands (DiLink 3.0). Ventilate, “halfway”, and position sliders work as before.

## [3.15.0] - 2026-09-09

### New
- ADB recovery after restart. On firmware builds 2606 and later, ADB is disabled at every tablet restart and all vehicle control stops working. The “Restore ADB after restart” toggle in “Vehicle system” (disabled by default) lets the app restore ADB after boot, usually 10–30 seconds after Wi-Fi connects. Wi-Fi is required (a home network or phone hotspot); confirm the system dialog once on a new network. Next to the toggle are a “?” icon with instructions and a status line. If ADB already survives restarts for you, the toggle is not needed. Thanks to @RBGboost for the idea and vehicle testing.
- Instrument panel: for some users, the map did not use the full display when sent to the instrument panel. The “Instrument-panel full screen” toggle was added to “Display”. It is disabled by default; if the map already fills the instrument panel, do not enable it.
- Support the project: “card via YooMoney” was added next to USDT.

## [3.14.3] - 2026-09-07

### New
- Blind spots: a “Both cameras on home screen” toggle for vehicles where the left camera on the instrument panel gets in the way (#183).

### Fixed
- Log recording: the `decode rejected` line is included in the file (#180).
- Blind-spot cameras: when the “Show on instrument panel immediately” toggle is disabled, the camera no longer switches the instrument panel to projection mode; ADAS and the native widget remain in place (DiLink 5.0, verified by a tester).

## [3.14.2] - 2026-09-06

### New
- Settings → “App data”: a “Quiet notification in shade” toggle. The BYDMate notification moves to the collapsed list without a status-bar icon. It cannot be removed entirely because Android requires a notification for background operation (#86).
- Technical: a cell-spread bar, overheating zones for motor and inverter temperatures, and a top view of tires that highlights pressure deviation.

### Fixed
- DiLink 3.0 and 4.0 (Android 10): the app crashed when the vehicle turned off and on and the system disconnected GPS. After this crash, steering-wheel buttons (voice assistant, button-triggered hazard lights, projection) stopped working until the tablet restarted. The crash is fixed. If the system still does not connect the steering-wheel-button service after the vehicle starts, the app restarts itself once 10 seconds after startup and the service returns without a tablet restart. At that moment, the widget disappears for one second and log recording pauses briefly; this is normal. Behavior on DiLink 5.0 did not change.
- Technical: the “maximum discharge power” row was renamed “available battery power” (it is a different BMS parameter), and the limit bars were removed.
- Instrument panel on DiLink 3/4: if the instrument-panel display is not found, the app writes extended diagnostics once per startup to the log (firmware properties, display list, projection services) to investigate the cause using a regular log recording.

## [3.14.1] - 2026-09-05

### New
- Automation: the “Pause” action now has 30-second and 1-minute options, so you do not need several consecutive 10-second pauses.
- Log recording continues after the vehicle turns off and on: if recording was enabled, the app continues writing to the same file after restart until two hours have elapsed from the first start. This captures startup problems themselves (startup automations and steering-wheel buttons).

### Fixed
- Technical screen: the “power” and “battery power” rows had identical hints. “Power” now explains motor power, and “battery power” includes an explanation of the second number (%/h).
- Agent chat (debug): the keyboard covered the input field; the screen now resizes and the input row remains visible.
- HUD: after activating Alice, “11 m” flashed on the windshield without a maneuver. An empty player notification no longer enables navigation prompts (#170).

## [3.14.0] - 2026-09-05

### New
- Technical screen: tapping the battery card on Home opens a full screen of live vehicle parameters grouped into cards: battery now (HV voltage and current, battery power in W and %/h, insulation), BMS charge and discharge limits, cell spread, motors and inverters (temperatures, RPM, pedals), climate (compressor, air conditioning, temperatures), tires (pressure and temperature), and battery history (SoH, lifetime mileage and energy, average SOC). Each unclear parameter has a “?” icon with a plain-language hint (#153).
- The battery card on Home shows five cells: SoH, battery temperature, 12 V onboard network, cell spread in mV, and insulation resistance in MΩ. The “Battery health” dialog was replaced by the Technical screen.
- On vehicles where a parameter is unavailable, its row shows “—”, and empty cards are hidden.

## [3.13.2] - 2026-08-28

### New
- Widget: a “Hide in apps” setting with a list of installed apps where the floating panel hides (for example, the browser during video). The “Hide for YouTube” toggle works as before (#171).
- Integrations, “Custom” connection: an “Additional request parameters (JSON)” field whose content is added to every request to the server, for example {"thinking": false}, to disable reasoning on DeepSeek-like models (#167).

### Fixed
- Voice agent: if the primary connection is not selected or filled in, but only “Custom”, for example, is configured, the agent uses the first configured connection instead of showing the “not configured” error (#172).

## [3.13.1] - 2026-08-26

### Fixed
- DiLink 3.0 (Yuan Plus, Song PRO, and other Android 10 vehicles): vehicle commands work again. Firmware prevents the service process from registering in the system, so it now passes the channel directly to the app; this also removed a restart loop that made the widget take 20–30 seconds to start (#64, #148).
- The diagnostic dump no longer retains an old service-process startup error after the process starts successfully.
- Blind-spot cameras: if the image freezes (the camera stream stops while the window holds the last frame), the app closes and reopens the camera within a couple of seconds. Previously, only turning the feature off and on in Settings helped.
- Log recording now includes blind-spot camera-controller lines, and the diagnostic dump has a camera-open and -close log.
- The diagnostic dump now contains a live snapshot of vehicle data (gear, speed, power state, SOC) to investigate gear-based automation triggers on DiLink 3.0 (#64).
- Update: the start of a download showed “0%%” instead of “0%” (#164).
- Automations: in the “Pause between runs” field, Backspace now also deletes the last digit, so the field can be cleared completely (#163).
- Automations: for parameter conditions (speed, SOC, etc.), the delete button and ordering arrows no longer extend beyond the card edge (#165).

## [3.13] - 2026-08-24

### New
- New app icon: a three-dimensional battery with a yellow lightning bolt.
- Telemetry webhook: the same live data sent to ABRP (SOC, power, temperatures, odometer, tire pressure, charging state) can be sent as a JSON POST request to your server. Settings → Webhook: URL, optional secret (Authorization: Bearer header), and a separate coordinates toggle. The cadence is unchanged: once per second while driving, every 8 seconds while charging, and every 30 seconds while parked; it works independently of ABRP (#159, #16).
- README (ru/en/zh) was updated for the current versions: split screen on UI7, steering-wheel button, agent memory, webhook, and new Settings sections; new screenshots, including projection, split, voice-agent, and blind-spot settings with explanations, plus widget infographics.

### Fixed
- Speed-safety messages (windows, sunroof, door unlocking, front trunk, prohibited command) and voice-agent refusals—“Speed unknown” / “Parking required”—are now translated into the app language; they were previously shown only in Russian (#162).

## [3.12.1] - 2026-08-24

### Fixed
- A turn-signal blind-spot camera is visible again above the map projected to the instrument panel: the projection window could cover the camera window (they live on the same display and creation order determines their order). The camera now detects projection recreation and raises itself above it again.
- When a blind-spot camera is turned off on the instrument panel, the driver no longer sees a black screen for about one second: the native instrument-panel frame returns before our layer is disabled, making the blanking moment unnoticeable.

## [3.12] - 2026-08-19

### New
- Turn-signal blind-spot cameras: the minimum activation speed can now be 0 km/h (previously at least 5), so the camera also shows while stationary.
- Split screen on firmware with the new UI7 interface (arrives with an update; V1.6 on Leopard 3): 1/3 and 2/3 panels, the divider, and the swap gesture are now native; BYDMate only starts the selected app pair and returns an app to its panel. The split mechanism is unchanged on earlier firmware.
- Steering-wheel volume wheel: a new setting where a press pauses and resumes playback instead of switching the audio source (as on UI7 firmware). Disabled by default.
- Settings: a new “Vehicle system” subsection in “Service and data” (the wheel toggle moved there). On UI7 firmware, it also has a “Vehicle interface language” button: it opens a hidden native BYD dialog with the full list of languages (Russian, Ukrainian, Polish, and others), with a warning about auto-parking. Firmware changes the language; the app writes nothing.
- Automation: a “Steering-wheel button” trigger. Press any steering-wheel button in learning mode and assign any action to it, including launching the app. Delete or disable the rule and the button returns to its factory behavior.
- Voice agent: driver memory. Tell the agent your name or what you usually prefer (cabin temperature, music, routes), and it remembers this between conversations; “forget …” removes a fact. The agent settings have a fact list and “Forget all” button. Facts are stored only on the head unit and are included only in requests to the model you selected.
- Voice agent: new commands for hotspot and hazard lights; closing and toggling split screen are now also available in automations created by voice; “open media center”, “open phone”, and “open Yandex Maps” launch the native BYD player, native calls screen, and Yandex Maps.

### Fixed
- The voice agent responds faster: models with reasoning mode (Gemini 3 through OpenRouter, GLM through z.ai) receive requests with reasoning disabled; during network failure, the agent no longer stays silent for up to two minutes—retries start only in the first 10 seconds and the fallback model in the first 20; the unchanged part of the request no longer varies between phrases, allowing providers to use their cache.
- Voice agent: speech synthesis warms up when the service starts, short frequent replies are spoken from cache, and the first reply sounds without a pause for model loading.
- Voice agent: it no longer states that the vehicle is purely electric (on hybrids the app sees only the battery), says “waiting for confirmation on screen” until you confirm a dangerous command, and knows that the “Steering-wheel button” trigger is configured only in the app.
- Voice agent: vehicle state now includes data age; the agent warns when readings are stale.
- Instrument panel on UI7 firmware: Navigator and the blind-spot camera are again shown on the instrument-panel display in any instrument-panel mode (Simple, ADAS, Maps), not only ADAS. The app switches the panel to map mode while showing them and restores the previous view after disabling; earlier firmware is unchanged. Side cards (tire pressure on the left, compass/clock/music on the right) hide while showing, as with the native “hide instrument-panel menu” setting, and return afterward; firmware still shows them for 15 seconds when steering-wheel buttons are pressed.
- The “Hide built-in voice assistant” toggle description now says that the Shortcuts app (quick commands) also disappears with the assistant and how to restore both: turn off the toggle and restart the head unit.

## [3.11.9] - 2026-08-17

### Fixed
- Split screen: fixed the first launch attempt after ignition turns on (#139). On a cold start, the app needs time to create a window, but the verification check declared failure too early; panels now wait up to three seconds for the window, so a second attempt is no longer needed.
- Trips: on vehicles with a long-frozen native trip database (file unchanged for more than a month), built-in trip recording starts after the first couple of trips rather than after several days (#148).

## [3.11.8] - 2026-08-15

### Fixed
- HUD: the speed-limit icon was restored on the windshield (#150). In 3.11.6, the sign was sent as a number through the native frame field, but the windshield draws nothing from that number, so the icon disappeared; the drawn sign returned as in 3.11.5.

## [3.11.7] - 2026-08-10

### Fixed
- Split screen: fixed a rare scenario where split screen was permanently declared “unavailable on this vehicle’s firmware” on a fully working vehicle (#147). The system window-mode setting is now rewritten each time split screen starts, and the incorrect “unsupported” mark on known-compatible firmware resets automatically.
- Instrument panel: if Navigator is closed by the system a few seconds after being moved to the panel, the app now detects this and restarts it once directly on the panel; Navigator legitimately opened on the home screen is not touched (#134).
- Split screen: the first launch attempt is less often falsely declared failed—the verification read of panel state repeats after a delayed response, and the log records the exact failure reason (#139).

### New
- Diagnostics: the settings dump now includes a split-screen state section (system flag, boot count, firmware verdict), speeding up investigation of field reports.

## [3.11.6] - 2026-08-10

### Fixed
- HUD: animated-arrow codes on the windshield now use factory values. Previously “left” was sent with the “slight left” code and end of route with the “left” code; at roundabouts and at the destination, the animated arrow now correctly disappears rather than showing a false “left turn” (#94).
- HUD: the speed-limit sign is sent as a number through the native frame field rather than embedding an image, which is more reliable on different firmware; it uses the same toggle as before.
- HUD: a prompt with a maneuver distance below 11 meters no longer disappears from the windshield.
- HUD: connection to the system projection service was strengthened: a scenario where binding could crash the firmware system process was removed, and a fallback service-discovery path was added for other firmware.

### New
- Diagnostics: when a maneuver changes, the HUD log records the Navigator window structure (once every 30 seconds) for future lane-guidance support.

## [3.11.5] - 2026-08-10

### New
- “Native split” mode: split-screen settings can select BYDMate windows (1/3+2/3) or the firmware’s native split. Native split also works where our option is unavailable (DiLink 5.1): on firmware supporting thirds, it opens 1/3+2/3 panels; on others, it splits in half with the system’s second-app picker (#139).

### Fixed
- HUD no longer turns off 90 seconds after Navigator is moved to the instrument panel: when the Navigator window is hidden by the system (fallback instrument-panel output), prompts are now read directly from Navigator events (DiLink 5.1, Sea Lion 07; #134).

## [3.11.3] - 2026-08-10

### Fixed
- HUD projection no longer turns off when Navigator is minimized: an unreadable window is no longer treated as route completion, so windshield prompts remain until navigation genuinely ends (#144).
- Moving Navigator to the instrument panel on firmware without the required window-mode support (DiLink 5.1) no longer cancels active navigation: the app first checks the mode safely and does not needlessly recreate the Navigator window (#139).
- Split screen: if restarting the vehicle does not help, the app remembers this and clearly reports “unavailable on this vehicle’s firmware” rather than endlessly asking you to restart; useless repeated attempts that restarted Navigator and music stop (#139).

## [3.11.2] - 2026-08-08

### Fixed
- HUD: at roundabouts, the animated arrow no longer shows a left turn—the correct “enter roundabout” code is now sent when approaching, and the exit number is retained (#94).
- Instrument-panel output in Extended mode: fixed Navigator “crashing” when output again—the app now waits for the system-recreated window to become visible instead of rolling back too early (the rollback also turned off HUD because fallback output is invisible for reading prompts; Sea Lion 07, #134).

## [3.11.1] - 2026-08-05

### Fixed
- The “Log recording” button no longer resets when its window closes: if recording is running, reopening the app shows “Stop recording” (recording already continued before, but appeared stopped).
- Split screen on firmware other than the acceptance-test Leopard 3: panels are created in a mode that does not break on touch, and system buttons do not appear over windows (#130).
- Seat heating and ventilation: the app no longer trusts a firmware “success” response; it checks the actual state from the driver-seat status and switches to a fallback control channel on a mismatch (#74, #98, #109).
- Image scaling on the instrument panel in Extended mode no longer covers 2GIS and other apps: scaling uses virtual-display size without changing density at runtime (thanks Chpohan, #121).
- The assistant startup error now states the specific cause, including disabled ADB debugging, rather than general “unavailable” (#133).

### New
- The diagnostic dump now includes logs for investigating field issues: split-screen transitions, instrument-panel events, HUD maneuver history, and a seat-command log with verification result (#94, #134, #135).

## [3.11] - 2026-08-05

### New
- Manual range calculation: battery settings can select the “Manual” method and set a consumption table by battery temperature (5 points, +20…−20 °C, optional range at 100% charge). Automatic calculation from trip history remains the default (thanks Dmitrijsmok, #136).
- Polish interface language.
- Belarusian interface language.
- Cost-calculation currency: Polish złoty (zł).

### Fixed
- Chinese and Portuguese translations now include missing strings (Display, blind-spot cameras, and range calculation); these places were previously shown in Russian.
- Default automation templates for the Polish and Portuguese interfaces are created in English rather than Russian (Belarusian keeps Russian names).

## [3.10] - 2026-08-03

### New
- Blind-spot cameras: when a turn signal is on, the side-camera image automatically appears on the instrument panel and in an on-screen window. A new “Display” Settings section configures its destination, position, and size, and you can drag the window into place. If a vehicle is detected to the rear side, an orange frame highlights the window. The feature is disabled by default and can be enabled in Settings.
- The GitHub project description now has a “What to do when errors occur” guide: restart the head unit, capture logs, and save the FID catalog.

### Fixed
- The maneuver arrow on the head-up display (HUD) no longer sticks after a completed turn.
- 2GIS no longer closes when sent to the instrument panel (thanks danijar2000, #131).

## [3.9.3] - 2026-07-30

### Fixed
- Windows on DiLink 3.0 (Song Plus and other vehicles with old firmware): window commands now use the control channel available on this firmware—open, close, halfway, and ventilate. The command path on DiLink 5.0 is unchanged (#79).
- The right rear-window reading on DiLink 3.0 now comes from the correct firmware parameter; automation triggers for this window previously saw junk (#79).
- The “Sunroof” automation trigger now works: the live parameter for sunroof opening percentage is connected (the trigger was previously listed but never ran).
- Driving modes in automation rules were fixed: ECO, SPORT, NORMAL, and Off-road now match actual vehicle codes (NORMAL and SNOW were previously incorrect, so rules for them did not work); switching modes no longer causes false activations; the voice agent names the current mode for every mode. Saved rules using NORMAL update automatically on first run.

### New
- The diagnostic dump now has sections for recording-daemon state (for vehicles where commands do not work, #64) and seat parameters (for vehicles where seat commands are accepted without effect, #74, #98, #109).

## [3.9.2] - 2026-07-30

### Fixed
- Navigation projection (HUD and instrument panel) no longer turns off when Navigator is minimized: notification-reading permission is now granted through the official system method (the system silently reset the old method on some firmware), and during route navigation the app checks and restores it itself (#105).
- The HUD animated arrow shows the correct direction: the icon-code table was rewritten from system-receiver codes; right turns previously drew as “slight left”, straight and roundabout movement as “left”, and destination as “roundabout exit”. For numbered roundabout exits, the exit number is sent (#94, #116).
- Permission-recovery history was added to the diagnostic dump (for log investigation).

## [3.9.1] - 2026-07-30

### Fixed
- Split screen: both panels respond to touch again (previously only one worked; the other was covered by an invisible system shield).

### Changed
- The system window bar with close and expand buttons is visible above panel windows again. This is the cost of fixing touch: the buttons work natively, and closing a panel offers a replacement picker.

## [3.9] - 2026-07-29

### New
- 1/3+2/3 split screen: two freeform windows on DiLink, controlled by the pill at the bottom (swap sides, apps, replacement picker, exit to full screen). Start it from the widget, automation, or voice command. The toggle is in Settings → “Split screen” (disabled by default).
- FID catalog: the “Save FID catalog” button in Settings → Data exports a catalog of firmware parameter names and numbers to Download to support other models (without VIN or personal data).
- A “?” hint at “Split screen” explains how to enable and start it.

## [3.8.1] - 2026-07-23

### Fixed
- Cell-imbalance indication takes charge into account: warnings appear only in the diagnostic SOC 20–80% zone (yellow from 50 mV, red from 90 mV, according to the BYD test method). At full charge, large cell spread is normal LFP physics, so “Battery health”, the Home battery card, and insights no longer alarm you in red (#113).

## [3.8] - 2026-07-23

### New
- TRIP 1 and TRIP 2 counters on Home, as in the vehicle: distance, consumption in kWh (including parking), travel time, and cost since reset; long-press to reset and tap for details. The “Parking” card was removed; its data is now in the counters.
- Note: after updating during an active trip, Trip counters start live counting from the next trip (a one-time first-session effect).
- The voice agent says how much battery remains at the end of a route (calculated from the app’s average consumption).
- HUD: a fallback output channel (standard Amap broadcast, as in OpenBYD) activates automatically only on vehicles with the Amap service; the dump has extended HUD diagnostics (frame counters, response codes, parallel HUD apps).

### Fixed
- Voice commands to “turn off/disable seat heating (ventilation)” run instantly again without calling the AI agent; “seat heating” now turns both front seats on and off.
- The voice agent no longer calls the vehicle a hybrid: it is an electric vehicle.
- HUD: roundabouts show the correct exit (1st...10th) rather than “straight”, and the arrow in the roundabout no longer turns the wrong way (#94).

## [3.7] - 2026-07-21

### New
- HUD and instrument-panel projection continue route guidance when Yandex Navigator is minimized: data is read from its notification (a port of proven solutions by @RBGboost, author of the donor yandexhud app).
- Camera warnings on HUD: a camera icon and its distance replace the maneuver arrow while Navigator shows a camera.
- Yandex Maps is now also a navigation source for HUD, instrument panel, and the voice agent (alongside Navigator).
- HUD ticker: when more than 3 km remains, remaining time and arrival time appear next to the street.
- The voice agent states remaining route distance and time even when Navigator is minimized.
- Instrument-panel projection mode: “Factory” (default; vehicle system settings do not change) and “Extended” (Navigator on the instrument panel, visible to the agent and HUD; enabled through a dialog that explains it and the path back to factory). For users already using projection, the previous mode is retained automatically.
- ABRP: optional sending of vehicle coordinates and heading in telemetry (a Settings toggle, disabled by default), for installations where the car arrow in ABRP lags the real position.
- API keys in Settings are now hidden with asterisks; the eye button shows the value.

### Fixed
- “Factory” mode returns the system window-mode setting to its factory value: after switching and restarting DiLink, third-party projection apps work again as they did before BYDMate was installed.

## [3.6.3] - 2026-07-20

### New
- Diagnostic dump: HUD state section (enabled, SOME/IP support, connection status).
- Diagnostic dump: steering-wheel-button state section (accessibility service, voice-button and projection settings).
- The pause between automation runs can now be as short as 1 second (previously at least 30).

### Fixed
- Fixed a black rectangle on the instrument panel after a projection attempt on vehicles without a cluster display (Song, DiLink 3/4); it previously remained until restart.

## [3.6.2] - 2026-07-19

### New
- “Wi-Fi hotspot” automation action: automatically turns the hotspot on or off for any trigger, for example when the vehicle starts (#100).

### Fixed
- A damaged voice model no longer causes an endless app restart loop (Sea Lion 07): if the recognition or speech-synthesis model is corrupt (for example, because power was lost during download), the app detects it, removes it, and offers to download it again.
- Fixed a rare crash of the whole app when pop-up windows appear and disappear immediately (voice-agent indicator and automation notifications).
- Voice commands for ventilation and heating of “all seats” no longer turn on cabin ventilation: phrases with the word “seats” are now correctly sent to the AI agent, which controls both seats.
- “Open/close all windows” commands are sent with a small interval between windows: on some vehicles (Song L), the first command in a dense batch was lost and the driver window did not respond.
- The diagnostic log now includes a list of system displays to help investigate why instrument-panel projection does not work on the Song family and DiLink 3/4.

## [3.6.1] - 2026-07-16

IMPORTANT: after updating, download the speech-recognition model again: Settings → Voice agent → “Download (226 MB)”. This version no longer uses the old model, and the voice agent does not recognize speech until you download it again.

### New
- The speech-recognition voice model was updated to GigaAM v3: it is noticeably more accurate on spontaneous speech and speech with music playing. After updating, go to Settings → Voice agent and download the recognition model again.
- The “Battery health” window (tap the battery on Home) now shows average charge level since the last charge and across all time. The “?” icon next to the heading explains how to interpret it and why a lower average charge is useful for an LFP battery.
- The GitHub description now publishes a complete list of quick voice commands that work without the agent and internet.

### Fixed
- An accurate voice-assistant error: if a non-Russian voice language is selected, the app now explains that recognition understands only Russian speech and points to the setting instead of falsely saying “model not downloaded”.
- Detection of an inactive trip database (vehicles where the energydata file remains from old firmware and does not update) works again: the trip counter on top of the frozen file did not increase, so the app did not switch to its own trip recording.

## [3.6] - 2026-07-16

### New
- Yandex Navigator prompts on the head-up display (HUD): maneuver, distance, street, arrival time, and speed-limit sign through the vehicle’s native bus. Toggles are in Settings → “Display”; HUD equipment is required, and the feature disables itself on vehicles without the SOME/IP bus. This part of the app was made possible by @RBGboost: the protocol reverse engineering and original implementation (YandexHUD) are his code and contribution. You can thank him on Telegram: @RBGboost.
- Instrument-panel projection: Navigator now starts on the system instrument-panel display, so the voice agent and HUD see the route during projection. The app selects the projection method itself; there is no separate setting, and Navigator restarts for one second when projection is disabled. One DiLink restart is required after updating (long-press the volume wheel; simply turning off the vehicle is not enough); until then, the old mode is used automatically. Because output no longer uses a virtual display, the instrument-panel window position and size may differ from before—adjust them again with the Settings sliders if needed.
- The “Instrument-panel auto-on” setting was renamed “Show on instrument panel immediately” and now has a clear description (automatically switches the instrument panel to projection mode).
- After updating, the first projection start shows an on-screen hint that a one-time DiLink restart is needed (previously visible only in Settings).
- Log recording now captures diagnostics for the system assistant, instrument-panel projection, and route reading; to do this, the app automatically obtains the READ_LOGS system permission (fully active from the next app start).

### Fixed
- The voice agent now sees Navigator guidance data even when it is minimized or projected to the instrument panel (previously only when Navigator was open on the central screen).
- Voice agent: volume set by a command during a session no longer returns to its previous value after the agent closes; “increase/decrease volume” during a session uses the actual music volume rather than the muted volume.

## [3.5.3] - 2026-07-14

### New
- Voice agent: “jedziemy do domu” and “do pracy” build a route to a BYDMate Place or an address saved in Navigator itself.
- Voice agent: “pokaż na mapie” shows a point without building a route.
- Voice agent: “ile jechać”, “jaki następny skręt”, and “kiedy dojedziemy” answer from Navigator guidance data (maneuver, street, remaining distance, arrival time) together with charge and range; speed limit and exit number are included when Navigator is on screen.
- Settings: a persistent button to open the system autostart setting (previously only from the after-update dialog).
- Settings: hide the floating widget while YouTube is on screen (on/off, disabled by default).
- Settings were reorganized: a new “Service and data” section, Places can be edited directly in Settings without a separate screen, all toggles and buttons use one style, and every setting has a hint.

### Fixed
- The voice agent no longer confuses route building with map search (“jedziemy do domu” opened search rather than a route).
- Voice agent: the distance stated immediately after a route is built is now spoken as approximate (it is a straight-line estimate that differed from Navigator’s exact figure).

## [3.5.2] - 2026-07-12

### New
- The app is nearly three times smaller: 29 MiB instead of 81 (code compression enabled; the obsolete Vosk voice engine removed—recognition has long used the new engine).
- “Instrument-panel projection” automation action: with the “Service start” trigger, Navigator is sent to the instrument panel automatically when the vehicle starts (#57, #84).
- Voice agent: a direct security-mode command and creation of automations with “Instrument-panel projection”, “Speak text”, and “Agent request” actions.
- Confirmation of dangerous voice-agent actions before execution, with a check of the current vehicle state when confirmed.
- Crash reports are saved to disk and included in “Log recording” even after a restart.
- A warning before backup export: the archive contains API keys and GPS history in plain text.

### Fixed
- Recording a long trip no longer stops after about 30 minutes of continuous driving.
- The “Charging” trigger works again: state is determined from live charging-plug and BMS data.
- Removed false charging-trigger activation during a cold start of the head unit.
- The command-recording service process restarts automatically if it dies during a trip.
- More reliable pickup of new trips from the onboard database (WAL journal considered).
- The seat-heating/ventilation channel is rechecked after an OTA firmware update for the vehicle.
- Voice settings are now included in backup.
- Restore is protected against damaged backup archives.
- Daily voice-agent statistics no longer include midnight of the next day.
- GPS coordinates no longer enter the system log.
- Portuguese translations were expanded (43 strings).

### Changed
- Vehicle data polling: batched reading of parameters in one call instead of hundreds of individual calls (faster and lighter for the system).
- The project license changed to PolyForm Noncommercial 1.0.0 (free non-commercial use).

## [3.5.1] - 2026-07-10

### New
- Cabin sensors in automation: front-passenger seat belt, occupancy of all five seats, light level, smart-key battery, and rain (from automatic wipers)—new rule triggers.
- The voice agent sees cabin sensors in vehicle state and warns about an unfastened seat belt while driving.
- UZS currency (Uzbekistani som) in tariff settings.
- Safety: door unlocking is blocked above 30 km/h.
- Safety: the “Run now” button now follows all safety limits (windows, sunroof, front trunk, unlocking).
- The voice agent sees more data: battery-cell voltage (min/max and spread), ignition state, battery temperature min/max, daytime running lights, lifetime mileage, and energy.
- The agent creates automations with a voice phrase, widget button, app launch, and days of week in a schedule.
- 14 new commands in automation actions: individual rear windows, ventilation of each window, climate off, climate auto/manual mode, sunroof stop/tilt/comfort.
- The diagnostic dump (“Log recording”) now includes audio state, native BYD voice-assistant packages, and the trip-source detector.
- New automation actions: “Speak text” (the agent voice speaks the specified text) and “Agent request” (the agent performs a text request and speaks the reply). Any trigger, including a schedule, can now wake the voice agent—for example, a morning weather summary every weekday at 8:00.
- Manual trip deletion: long-press a trip in the list and confirm; a deleted trip does not return during synchronization (#81).
- “Run with sound” rule setting: one sound signal when triggered, for any actions.
- “Notification (sound)” and “Notification (no sound)” actions are combined into one “Notification”; the toast remains for 15 seconds or closes on tap.

### Fixed
- The voice agent no longer says that the charging plug is connected when it is not.
- Data refresh frequency while driving returned to normal (polling incorrectly used the “charging” cadence).
- Trips: on vehicles without a live BYD trip database, where an abandoned file remains from old firmware (Yuan Plus / DiLink 3), the app now detects the “dead” database and enables its own trip recording (#63). Reset detection: Settings → “Reset trip-source detection”.
- Navigator output to the instrument panel: added a fallback name for the instrument-panel system service on DiLink 3 (tip from @klimuts).
- Music volume no longer remains at minimum if the app restarts during a voice session.
- The instrument panel no longer remains black after the vehicle restarts with active Navigator projection: at startup, the app returns the panel to its factory view.
- The voice agent can now create automations for an exact time and schedules with days of week (previously it offered only time of day).
- The voice agent no longer replies “did not work” when instrument-panel projection has time to turn on: status is awaited for up to 15 seconds.

## [3.5] - 2026-07-08

### Added
- Offline voice AI agent. Speak Russian: recognition runs directly in the vehicle without internet, and audio is not sent anywhere. The agent understands natural speech and performs requests.
- Voice control: vehicle state (charge, range, outside temperature, tire pressure); windows, climate, seat heating and ventilation, locks, sunroof, shade, trunk, lights, refrigerator; routes and saved places; music and apps; trips, charging, statistics; starting and creating automations; weather and web search.
- Simple commands (windows, temperature, volume, seat heating) run instantly offline without a network.
- Six offline speech voices (Dmitry, Alyona, Ruslan, Irina, plus higher-quality Mark and Sofia), with speed and expressiveness settings and a listen button. Premium online voices are optionally available. Voice replies can be disabled, in which case the agent replies in text.
- Push-to-talk button: on Leopard 3, the native steering-wheel button works; on other vehicles, you can assign any button. After a reply, you can continue speaking without pressing it again; the session ends after 30 seconds of silence.
- A draggable “Listening” pill with recent utterances.
- Interrupt a reply by saying the agent’s name (disabled by default).
- Agent personality and voice setting (male or female).
- Select an AI provider (OpenRouter, free z.ai, or your own server), with a fallback connection if the primary one fails. Keys are entered in the app and stored locally.
- Send Yandex Navigator to the instrument panel by voice.
- New automation trigger: activates on a spoken phrase.
- Insights: choose week or month; calculation is always local on the device.
- “Voice agent” Settings section and an “AI connections” block with presets and clear network-error messages.

### Fixed
- Automations for the driver-door lock now trigger (state codes match the vehicle’s actual values).
- “Ventilate all windows” now opens all four windows for ventilation rather than only the driver window.
- Locking and unlocking doors no longer shows a false error after a successful command.
- Speed limits clarified: speed no longer blocks the cabin shade; windows are allowed up to 120 km/h; the sunroof remains blocked above 80 km/h.

## [3.2.2] - 2026-07-01

### Fixed
- Turning off seat heating and ventilation from the app now works.
- Charge (SOC) is now shown in trip statistics.
- The toggle that disables the native voice assistant no longer gets stuck and applies reliably. The change takes effect after the head unit restarts.

## [3.2.1] - 2026-06-30

### Added
- Disable the native BYD voice assistant. Settings → “App” now has a toggle to hide the built-in voice assistant so it does not interrupt. Enable it again in the same place.

### Fixed
- Seat heating and ventilation work again on Song Plus, DiLink 3, and other models where they stopped after version 3.2.0. The app selects and remembers a working seat-control method. If seats do not respond after changing vehicles, Settings has a “Reset seat channel” button.

## [3.2.0] - 2026-06-30

### Added
- Step control for driver and passenger seat heating and ventilation.
- Refrigerator control: cooling, heating, off, and temperature presets.
- Front-trunk control while parked.
- Buttons on the floating widget: expand with one tap and start automation scenarios (enabled in Settings). Double-tap the widget to open the app.
- New automation trigger: a widget-button press.
- A charge column (start and end of trip) in the daily trips table.
- Portuguese interface language.

### Fixed
- Seat commands are no longer blocked above 80 km/h.
- Trip charge markers are retained when ignition turns off.
- The Home summary is shown in the selected language.
- Trip duration in the table no longer wraps onto two lines.
- Weekday and month in the trip list change immediately when the language changes.
- English and Chinese translations now match Russian.

## [3.1.10] - 2026-06-29

### Fixed
- Climate: fixed the “outside circulation” command (fresh-air intake), which now runs without an error.
- Automation actions work immediately after updating the app without restarting the head unit.

## [3.1.6] - 2026-06-14

### Fixed
- Small spontaneous charge increases (regeneration downhill, BMS calibration) are no longer recorded as charging. A 1% battery increase is always treated as noise; charging from 3% is always recorded.
- On DiLink 4, trip history again shows the trip route on a map.
- Dates in the charging list and service messages are shown in the app language rather than the device system language.

## [3.1.5] - 2026-06-11

### Added
- Instrument-panel output: sliders for window offset and content scale. On vehicles with a mini instrument-panel window, projection can fit the visible area; recommended values are in the README.
- Explanatory labels below the instrument-panel window sliders.
- The minimum projection-window size was reduced to 20%.

### Fixed
- A charging session is no longer lost if it followed a trip with unchanged battery percentage.

## [3.1.3] - 2026-06-11

### Added
- A charging-detection log in the diagnostic report (Settings). If a charging session is not recorded, the report shows why.

### Fixed
- Slow overnight charging sessions are no longer lost. The recording threshold was lowered from 3% to 1%, so even short 1–2% top-ups are recorded.
- Charging is completed if the head unit wakes with yesterday’s data: the app notices increased charge and restores the session.
- Charging recording no longer depends on the speed of trip import when the app starts.
- Charging-detector state is retained more reliably when the head unit shuts down unexpectedly.

## [3.1.2] - 2026-06-10

### Added
- Charge calculation from battery percentage accounts for battery health (SOH), making kWh estimates for charging more accurate.

### Fixed
- Charging sessions are no longer lost if the head unit sleeps during charging (the overnight scenario). A missed session is completed after it wakes.
- Consumption on short trips no longer shows dashes on vehicles without the energydata database (Song, Atto, and others); consumption is calculated from the BMS counter.
- Automations: after conditions change, a rule no longer triggers falsely on save, and a condition that occurs during a pause or frequency limit does not trigger itself later.

## [3.1.1] - 2026-06-05

### Added
- Battery card: a “Cells” row with minimum and maximum cell voltage and their spread. The minimum cell is highlighted by color so you can spot a weak cell early.
- Automations: new “Cell voltage (min)” and “Cell voltage (max)” conditions. You can set a notification when cell voltage falls below a specified value.

### Fixed
- Consumption on Home no longer turns gray; the figure is always colored by consumption level.

## [3.1.0] - 2026-06-04

### Added
- Chinese interface language (中文). Select it in Settings → App → Language. Contribution by @zhongjun96.
- Amap maps (高德地图) as the map source in trip details. Select it in Settings → App → Map source. OpenStreetMap is the default.

### Fixed
- Clarified the instrument-panel-output setting name: you can send any app, not only Navigator.
- In the Chinese interface, the range-per-1%-charge widget no longer overflowed its tile.

## [3.0.3] - 2026-06-04

### Added
- Export and restore configuration: all data and settings in one file (Settings → Data). Warning: the backup file contains keys and tokens (OpenRouter, ABRP, smart home) in plain text; store it safely.
- Multimedia-volume control in automation.
- Move conditions and actions in automation with up and down arrows.
- GPS trip routes and parking consumption were added to data export (CSV).

### Fixed
- Trip consumption per 100 km is shown again.

## [3.0.2] - 2026-06-03

### Added
- You can select the steering-wheel button that sends Navigator to the instrument panel: Settings → Navigator to instrument panel → “Change”, then press the required steering-wheel button. The default is the right star. System buttons, 360 view, and the instrument-panel carousel cannot be assigned.

## [3.0.1] - 2026-06-03

### Fixed
- The right-star steering-wheel button works reliably again after the vehicle turns off and on.
- Fixed adding a Place: tap the map to place a marker and save the Place.

## [3.0.0] - 2026-06-03

### Added
- The app works directly with the vehicle system, without the D+ (迪加) app. All data is read and controls work without an intermediary. You can remove D+ from the vehicle.
- Yandex.Navigator on the instrument panel with the right steering-wheel star. A short press sends Navigator to the instrument panel and back. You can choose any app for projection and set the window size (Settings → Instrument panel).
- Direct light and comfort control: cabin lighting, ambient lighting, daytime running lights, mirror and rear-window heating, windows, climate, and trunk.
- Scheduled automation: trigger at an exact time and interval, with selected days of week.
- A “Run now” button in the automation editor.
- Widget: kilometers per 1% charge and cabin and battery temperatures.

### Fixed
- Charging that occurred while the vehicle was asleep is recorded correctly.
- Closing the update window cancels the download.

## [2.8.1] - 2026-05-14

### Added
- Log recording in Settings adds vehicle, version, language, and setting-state information to the start of the file. The log also captures these subsystems: autoservice, ADB, ABRP, Charging, BatteryHealth.

### Fixed
- Battery capacity with a comma (for example 71.8) no longer resets to the default 72.9. The correct value is sent to ABRP, Charging calculations, and SoH. Tariffs and consumption thresholds were fixed similarly.
- Cabin temperature no longer shows -2000 on vehicles where the value is unavailable (for example, Song Plus). Outside and battery temperatures have the same protection.

## [2.8.0] - 2026-05-14

### Added
- English localization for the entire interface. Select it in Settings → App → Language. It changes instantly without restart.
- AI insights are generated in the selected language.
- English README (README.en.md).

### Fixed
- Dark text on bright buttons for better readability.

## [2.7.0] - 2026-05-13

### Added
- Settings in a DiLink-style two-panel layout: six sections on the left and content on the right. Less scrolling and a clearer structure.
- In the widget, you can select your own app for a left tap, not only Yandex.Navigator.
- The hidden “Smart home” section opens after seven taps on the version number.

### Fixed
- Migration of the Navigator setting between updates is protected from a race between multiple BYDMate processes.
- Widget preview no longer resets when moving between Settings sections.

## [2.6.1] - 2026-05-11

### Added
- Data accuracy in A Better Route Planner was raised to full: ABRP sees instantaneous battery power in real time.
- Adaptive sending frequency: once per second while driving, every 8 seconds while charging, and every 30 seconds while parked.

### Fixed
- Correct handling of ABRP “wait” responses (429) and temporary unavailability (5xx).

## [2.6.0] - 2026-05-10

### Added
- Sends live vehicle data to A Better Route Planner (ABRP) through the Iternio Telemetry API. Enable it in Settings → ABRP. A Generic Live Data Token from the ABRP garage is required. GPS coordinates are not sent—ABRP reads them itself. On Leopard 3 with “System data” enabled, charging-station type (AC/DC), kWh for the current session, and actual battery SoH are also sent. Thanks to @trollnuulnuul7 (PR #8) for the original integration.

### Fixed
- Charging is now recorded with the actual kWh even when the BMS counter resets between pauses. Previously, a long 48% → 99% session could be recorded as 21 kWh instead of about 37 kWh because only the last continuous phase was captured. The app now compares the counter with calculation by SoC delta and uses the correct value if the difference is greater than 30%.

## [2.5.15] - 2026-05-08

### Fixed
- Charging type (AC/DC) is determined more accurately.
- In the widget, the SOC percentage color now changes as on Home: above 50 green, 20–50 yellow, below 20 red.

## [2.5.14] - 2026-05-05

### Added
- New “DRL on” and “DRL off” automation actions in the “Lights” category. A rule can now turn daytime running lights on or off, for example when the vehicle starts. Commands were tested on Leopard 3.

## [2.5.13] - 2026-05-05

### Added
- Optional widget mode: a short tap on the left third launches Yandex Navigator (if installed); the right two-thirds still opens BYDMate. Disabled by default; enable it in Settings → Floating widget → “Left third — Yandex Navigator”. Long press (hide widget) is unchanged. The tap area uses the current widget width, so it works at any scale from 70–200%.

### Fixed
- The “Internet available” trigger no longer activates falsely. Previously, a rule could fire immediately after app installation or after the rule itself was disabled and enabled, even if internet on DiLink was actually off. The rule now waits for the moment internet genuinely appears after it is enabled and does not react to a state that was already present.

## [2.5.12] - 2026-05-04

### Added
- New “Internet available” automation trigger: the rule runs when DiLink connects to a network and Android confirms real internet access.

### Fixed
- When the vehicle starts after a full charge, the charging record is no longer lost. Previously, in a rare window right after BYDMate started, it might not wait for data from the system battery sensor and miss the charging event.

## [2.5.11] - 2026-05-01

### Fixed
- False charging records: if you turn on the vehicle while the plug is connected and charging is in progress, an extra record no longer appears. A completed charging session is written as one row when the plug is actually removed.
- Several consecutive starts with the plug inserted no longer create records: a record appears only when it is actually disconnected.

## [2.5.9] - 2026-04-30

### Added
- The widget hides automatically when the native camera is open on screen: reverse gear, automatic pop-up of the front camera at low forward speed, the “360°” button, and the parking app. It returns as soon as the camera leaves the screen.
- Watchdog for D+: if D+ stops responding for more than 9 seconds, BYDMate starts its service itself without launching the UI. It works even immediately after the vehicle starts, when DiLink is still loading apps and the native camera has appeared first.

### Fixed
- Consumption coloring (green/yellow/red) now applies immediately when thresholds change in Settings, without restarting the app.
- Charts on the “Charging” tab (SoH, cell Δ, battery temperature) now show numbers: the current value in the right-hand title and min/max labels at the edges. If the value is constant (for example, SoH 100%), the line is drawn in the center rather than pressed to the bottom.

## [2.5.5] - 2026-04-29

### Added
- A “Widget size” slider in Settings (70–200%) with live preview: while you move the slider, the widget pops up over the Settings screen and immediately shows the result. The same preview works for transparency.

### Fixed
- The widget hides automatically when reverse gear (R) is engaged and returns when leaving R; it does not cover the rear-view camera or parking panel. Closes [#5](https://github.com/AndyShaman/BYDMate/issues/5).

## [2.5.4] - 2026-04-29

### Added
- A quick “Open DiLink autostart settings” button directly in the reminder. Previously, you had to find the required screen manually.

## [2.5.3] - 2026-04-28

### Changed
- Estimated range is no longer underestimated after parking with climate on. The forecast is now calculated as a mix of the last three trips (weights 50/30/20, short trips up to 3 km excluded) plus consumption over the last 10 km of the current trip. The current trip’s share grows from zero over the first 3 km to half by 25 km.
- The large consumption figure and trend arrow continue to use a 25-km window, as in v2.5.2.

### Fixed
- Current-trip distance in the widget and Home no longer rounds to whole numbers after 10 km: it was “16 km”, now it is “16.4 km”.

## [2.5.2] - 2026-04-28

### Changed
- The large consumption figure in the widget and Home now accurately reflects the current trip: “kWh spent during trip / kilometers traveled”, updates every 3 seconds, and matches the history record on completion. During the first 2 km of a new trip, it smoothly transitions from the previous trip’s average to the current one.

## [2.5.1] - 2026-04-27

### Added
- Protection against deleting a Place used in automations. The “Delete” button is inactive for such places and has the label “used in N rules” next to it. Closes [#3](https://github.com/AndyShaman/BYDMate/issues/3).

### Fixed
- On the Charging tab in the “Dynamics” section, placeholder labels were rewritten: they clearly state that charts appear after 3 completed charging sessions, and show a counter.
- On first opening the Charging tab, the “Enable System data” onboarding no longer flashes when the toggle is already enabled.

## [2.5.0] - 2026-04-27

### Added
- Home screen in the floating-widget style: a SOC ring in the center, four values at the corners (duration, odometer, cabin temperature, trip distance, range, consumption + trend arrow).
- “Charging” tab. The vehicle records every charging session itself: SoC before and after, kWh, average power, duration, and cost. Statistics by period and for all time. “All/AC/DC” filters. Charging is counted even if BYDMate was not running when connected.
- Add charging manually with the “+ charging” button; edit and delete with long press.
- Actual SoH (battery health) on Leopard 3—a value from the vehicle’s onboard system rather than an estimate from SoC delta.
- Automatic AC/DC detection: first by connector type, otherwise by average power (15 kW threshold).

### Changed
- Consumption trend in the Dashboard card and widget is calculated with a rolling window over the last 25 km.

### Notes
- SoH and automatic charging require enabling “System data (experimental)” in Settings and one-time confirmation of ADB debugging on DiLink (with the “Always allow” checkbox).

## [2.4.12] - 2026-04-25

### Fixed
- The consumption trend arrow no longer disappears mid-trip. It appears after the first 300 m and remains stable until the end.

## [2.4.11] - 2026-04-25

### Added
- “BYDMate start” automation trigger—runs every time the app starts. Solves the issue with the Power=DRIVE trigger on EVs, which did not have time to register.
- “Pause” action with a selectable duration (0.5/1/2/3/5/10 seconds) for sequential execution.

### Fixed
- The trend arrow is not shown during the first 300 m of a new trip (previously, it showed the previous trip’s trend and was misleading). The figure remains visible in gray.

## [2.4.9] - 2026-04-25

### Fixed
- The consumption trend arrow in the widget works again (since v2.4.5, the figure to the right of range had been stuck in gray).
- The straight arrow (stable consumption) is now visible in white.
- Trip time resets after the vehicle is off for 10+ seconds.

## [2.4.7] - 2026-04-24

### Added
- Trip distance in the center of the widget’s top row.
- Compact time format in hours: `1h 25m` instead of `1 h 25 min`.

### Fixed
- Widget consumption is now real, rather than a gray placeholder.

## [2.4.6] - 2026-04-24

### Fixed
- Ghost trip timer fixed (the widget could show “11 h 43 min” immediately after startup). The timer now always starts from zero at ignition-on.

## [2.4.5] - 2026-04-24

### Changed
- Range is calculated from actual driving style over the last 25 km. It no longer gets stuck at the start of a new trip.
- The widget and Dashboard now show the same remaining-range value.

## [2.4.4] - 2026-04-23

### Changed
- Widget consumption uses a rolling 5 km window (previously it averaged from the start of the trip and became blurred).
- Trip duration uses the ignition cycle (from ignition on to off; traffic lights and parking with climate on do not reset it).
- The session survives a system kill: if the app is killed on the road, counting continues from the actual ignition-on time.

### Fixed
- Remaining-range discrepancy between the widget and Dashboard on Song fixed (TrackingService kept stale cache). Cache now refreshes every 30 seconds.
- Automatic update check at startup: the “Download and install” button no longer closes the dialog without downloading.

## [2.4.3] - 2026-04-23

### Added
- Support for Song and other models without BYD energydata. Settings now has a “Trip data source” toggle: BYD energydata (Leopard 3, more accurate) or DiPlus TripInfo (Song and others). In DiPlus mode, consumption is calculated from SoC delta (roughly 1 kWh/100 km less accurate).
- A data-source selection step in the Welcome Wizard.

## [2.4.2] - 2026-04-23

### Changed
- Baseline for the trend arrow is the EMA of the last 10 trips of at least 1 km. Previously, a weekly EMA was used; it could remain “up” for a week after colder weather, and vice versa.
- Accurate empty widget start: the first 1 km shows a dash (a cold start produces 60–100 kWh/100 km noise), after 1 km it shows the trip average, and after 2 km it includes the trend arrow.

## [2.4.1] - 2026-04-22

### Added
- Widget v3 (260×108 dp): remaining-range kilometers in 28sp white, centered SOC% in the status color, consumption + trend arrow, trip time, cabin temperature, battery temperature, 12 V.
- Long tap (1.5 seconds) hides the widget until BYDMate is opened.

### Removed
- The “hide in apps” blocklist was removed: PACKAGE_USAGE_STATS cannot be granted on DiLink without ADB. Long tap replaces this function.

### Fixed
- Automatic update check: it now runs immediately when the app opens, with a 10-minute throttle (previously, a 30-second delay + one-day cache did not work after a recent update).

## [2.4.0] - 2026-04-22

### Added
- Floating widget v2 (190×64 dp), 7 fields: SOC%, range, consumption + arrow, time, cabin temperature, battery temperature, 12 V.
- Widget transparency 30–100% (slider in Settings).
- Hide the widget in apps (default: YouTube, DiLink Settings).
- “Run once per trip” checkbox in the automation editor.

## [2.3.8] - 2026-04-22

### Changed
- The update dialog shows the target version (“Downloading v2.3.8”).
- On first launch of a new version, a dialog reminds you to disable background apps for BYDMate.

## [2.3.7] - 2026-04-22

### Added
- Automatic update check 30 seconds after opening the app. Cache duration: 24 hours. Settings toggle enabled by default.

### Changed
- Trip-duration format: “50 min” / “1 h 20 min” instead of “50m” / “1h 20m”.

## [2.3.6] - 2026-04-22

### Changed
- Unified switch style throughout the app (green track on, gray track off).

## [2.3.5] - 2026-04-22

### Changed
- Battery card: `13.9V` -> `13.9 V`, label `12V` -> “onboard system”.
- “Time” column in Trips: en dash with spaces, `13:06 - 13:22` instead of `13:06-13:22`.

## [2.3.4] - 2026-04-22

### Fixed
- Removed the currency code from the individual trip-cost column (it wrapped to the second line).
- Service notification uses a vertical bar instead of a comma between fields.

## [2.3.3] - 2026-04-22

### Added
- Floating SOC widget v1: draggable widget over other apps, colored status border (OK/WARN/CRIT), hide by dragging it to the “bin” at the bottom.
- Overlay confirmation for automations with sound and a 15-second timeout.
- Automation actions: open/close the driver window and open/close the passenger window.

### Changed
- Settings restructured: left column with tariffs, consumption thresholds, and data source.

## [2.3.1] - 2026-04-18

### Added
- Automatic Bluetooth dialing for the “Call” action: the “Dial automatically” checkbox sends the `com.byd.btcall.action.DIAL_HANGUP` broadcast (keycode 313, the steering-wheel answer button). No new permissions required.

### Removed
- Empty contact picker removed (on DiLink, `ContactsContract.Data` shows only Telegram accounts without phone numbers). The READ_CONTACTS permission was removed from the manifest.

## [2.3.0] - 2026-04-17

### Added
- Places (points of interest): osmdroid map picker, Haversine geofence. `place_enter` / `place_exit` triggers.
- Triggers: DoorFR/RL/RR, Hood, LockFL, ACCirc, Voltage12V, time_of_day (dawn/day/sunset/night via commons-suncalc).
- Actions: notification_silent, notification_sound (with overlay), app_launch, call (ACTION_DIAL without automatic dialing), navigate (Yandex Navigator), url, yandex_music (My Wave).

### Changed
- Default period on Dashboard: WEEK (previously TODAY).
- 7 most recent trips (previously 6).

## [2.2.0] - 2026-04-10

### Added
- “Automation” tab with WHEN → THEN rules (25 triggers, 37 commands).
- Edge trigger, cooldown, optional confirmation before execution.
- Safeguard: windows do not open at speeds > 80 km/h.
- Trigger log and 6 ready-made templates.
- “Save tariffs” and “Recalculate all trips” buttons in Settings.

## [2.1.7] - 2026-04-08

### Added
- Charts on the Trips screen (right third of the screen). 3 metrics: consumption, energy, cost. Adaptive grouping (by trips/days/months). Tap a bar to show the exact value.

## [2.1.6] - 2026-04-02

### Changed
- Facts in AI insights are calculated deterministically from real trips (consumption with trend, number of trips, percentage of short trips, idle drain). The LLM generates only insights—correlations, anomalies, and advice.

### Fixed
- Duplicate content in facts/insights fixed.
- The “Refresh” button in the AI Insight dialog is always available.

## [2.1.5] - 2026-04-02

### Added
- Monthly summary (30 days) in the AI prompt for trend analysis.
- Expanded AI dialog: key metrics first, then detailed analysis.

## [2.1.4] - 2026-04-02

### Fixed
- Parking consumption: removed live integration by motor power (it produced unrealistic figures). It is now calculated only from BMS data (energydata zero-km records).

### Added
- “Save” button in AI settings: save the key and get an insight immediately without restarting.

## [2.1.3] - 2026-04-02

### Added
- AI Insights on the Dashboard through the OpenRouter API. Updates once a day. Full model catalog with search and free-first sorting. Supports Gemini, Llama, Claude, and GPT.
- Analyzes consumption, trends (7 days vs. the previous 7), idle drain, temperature, cell voltage, 12 V, and cost per km.

### Removed
- SoH card removed (cannot be calculated correctly without OBD).

## [2.1.2] - 2026-04-01

### Fixed
- One-time recalculation of consumption for all trips from BMS data (energydata `electricity`). Previously, exact BMS consumption was overwritten by a rough estimate from DiPlus SoC delta (1% = 0.729 kWh).
- `enrichWithDiPlus()` no longer updates kwhConsumed/kwhPer100km. DiPlus provides only socStart, socEnd, avgSpeed.

## [2.1.1] - 2026-04-01

### Changed
- Map track: dark outline for visibility on all roads, brighter colors (red <20, yellow 20–60, green >60), 8px line + 14px outline.

## [2.1.0] - 2026-04-01

### Changed
- Autostart rewritten: BootReceiver -> WorkManager -> ServiceStartWorker -> startForegroundService (as in BydConnect). WorkManager guarantees execution even after process death. USER_PRESENT as a backup trigger.
- Automatic restart on `onDestroy` / `onTaskRemoved`. `excludeFromRecents` on MainActivity.
- Trips: fixed columns in collapsed headers; consumption color in month/day headers.

## [2.0.9] - 2026-04-01

### Changed
- Summary data in collapsed month/day headers no longer jumps (fixed columns), and the consumption color is visible.

## [2.0.8] - 2026-04-01

### Fixed
- Column order in Trips matches Dashboard. All headings use lowercase. Parking records no longer wrap to 2 lines.

## [2.0.7] - 2026-04-01

### Added
- SoH card on the Dashboard with calculated capacity (from trips with SoC delta >= 10%).
- Autostart diagnostics in Settings.

### Changed
- Russian interface tabs: Home, Trips, Settings.
- Monospace font for all numbers; right-aligned numbers in tables.

### Removed
- “Charges” tab.

## [2.0.4] - 2026-03-30

### Changed
- BootReceiver now fully matches DiPlus: `exported=true`, DEFAULT + HOME categories, QUICKBOOT_POWERON + BOOT_COMPLETED, priority 9900, direct `startForegroundService()`.
- TripDetailDialog: landscape with map on the left and statistics on the right.

### Removed
- “Map” tab (caused crashes on DiLink).

## [2.0.3] - 2026-03-30

### Fixed
- GPS recording: removed FOREIGN KEY constraint (migration v6 -> v7), parameters as in TripInfo (2000 ms / 8 m, Looper.getMainLooper(), getLastKnownLocation).
- Full GPS logging at every step.
- Idle-drain cleanup now runs once (previously it ran every time Dashboard opened).
- The update dialog scrolls. Dashboard shows 5 trips and cost precision to the kopek.
- Button to copy the DiLink+ command to the clipboard.

## [2.0.1] - 2026-03-30

### Fixed
- Duplicate trips: synchronization mutex, ±5-minute time check, sync does not run until the Welcome Wizard finishes, automatic cleanup of existing duplicates on first launch.
- Map crash: osmdroid initializes once in Application; MapView has lifecycle support.
- Android 12+ autostart through invisible SilentStartActivity (later replaced by WorkManager in v2.1.0).

## [2.0.0] - 2026-03-30

### Added
- Hybrid data source: energydata (primary) + DiPlus TripInfo (SOC enrichment) + GPS-only tracking.
- Event-based sync with file-change detection.
- First-launch Welcome Wizard (battery capacity, tariffs, currency, autostart instructions).
- Period filter on Dashboard (Today / Week / Month / Year / All) with 4 stat cards.
- Trip accordion (Month -> Day -> Trip).
- Trip-detail popup: osmdroid map with speed-colored route, statistics, and speed histogram.
- Type filters in Trips (All / Trips / Stops).

### Changed
- TripTracker is a GPS collector only, without creating TripEntity.
- Room migration v5 -> v6 (`source`, `byd_id` columns).
- Automatic deduplication during v1.x -> v2.0 migration.

### Fixed
- osmdroid crash: `getFilesDir()` instead of external cache, MAPNIK tile source.

## [1.0.7] - 2026-03-29

### Fixed
- Restored trip cost in TripCard (accidentally removed in v1.0.6).

## [1.0.6] - 2026-03-29

### Changed
- Trip column order: Time -> km -> duration -> kWh -> /100.

## [1.0.5] - 2026-03-29

### Fixed
- Idle drain showed unrealistic values (14+ kWh with unchanged SoC). Cause: BatCapacity (param 29) is a cumulative counter of total consumption. It now integrates Power over time, with fallback to SoC delta. Old invalid records are cleaned up automatically.

### Changed
- 7 trips on the Dashboard instead of 8 (they fit without clipping).

## [1.0.4] - 2026-03-29

### Changed
- The detail popup opens on the left next to the cards (40% width), rather than in the center. Close it by tapping the popup or dark background.

## [1.0.3] - 2026-03-29

### Changed
- Compact cards (less padding, 16sp font). Details open in a Dialog over the screen. Accordion: opening one popup closes the others.

## [1.0.2] - 2026-03-29

### Changed
- Three matching cards on the Dashboard (Charging, Parking, Battery) with a unified style. Expand/collapse by tapping.

## [1.0.1] - 2026-03-29

### Added
- Date and time in the “Time” column in Trips (`dd.MM HH:mm`).

### Changed
- The parking card is always visible on the Dashboard (even when consumption is 0).

## [1.0.0] - 2026-03-29

First stable release. Full audit and refactoring of the codebase.

### Added
- Battery degradation (tracking SoH and capacity from charging sessions).
- Trip cost in cards and totals.
- Smooth SOC gauge animation.
- Room indexes for fast queries, batch loading of route points.

### Changed
- WakeLock with timeout (prevents battery drain).
- Asynchronous service shutdown (prevents ANR).
- Exponential backoff when DiPlus is unavailable.
- ProGuard enabled in the release build.
- BootReceiver: `exported=false`.

### Removed
- `fallbackToDestructiveMigration`.

[Unreleased]: https://github.com/AndyShaman/BYDMate/compare/v4.0.1...HEAD
[4.0.1]: https://github.com/AndyShaman/BYDMate/compare/v4.0.0...v4.0.1
[4.0.0]: https://github.com/AndyShaman/BYDMate/compare/v3.20.0...v4.0.0
[3.20.0]: https://github.com/AndyShaman/BYDMate/compare/v3.19.9...v3.20.0
[3.19.9]: https://github.com/AndyShaman/BYDMate/compare/v3.19.8...v3.19.9
[3.19.8]: https://github.com/AndyShaman/BYDMate/compare/v3.19.7...v3.19.8
[3.19.7]: https://github.com/AndyShaman/BYDMate/compare/v3.19.6...v3.19.7
[3.19.6]: https://github.com/AndyShaman/BYDMate/compare/v3.19.5...v3.19.6
[3.19.5]: https://github.com/AndyShaman/BYDMate/compare/v3.19.4...v3.19.5
[3.19.4]: https://github.com/AndyShaman/BYDMate/compare/v3.19.3...v3.19.4
[3.19.3]: https://github.com/AndyShaman/BYDMate/compare/v3.19.2...v3.19.3
[3.19.2]: https://github.com/AndyShaman/BYDMate/compare/v3.19.1...v3.19.2
[3.19.1]: https://github.com/AndyShaman/BYDMate/compare/v3.19.0...v3.19.1
[3.19.0]: https://github.com/AndyShaman/BYDMate/compare/v3.18.2...v3.19.0
[3.18.2]: https://github.com/AndyShaman/BYDMate/compare/v3.18.1...v3.18.2
[3.18.1]: https://github.com/AndyShaman/BYDMate/compare/v3.18.0...v3.18.1
[3.18.0]: https://github.com/AndyShaman/BYDMate/compare/v3.17.5...v3.18.0
[3.17.5]: https://github.com/AndyShaman/BYDMate/compare/v3.17.4...v3.17.5
[3.17.4]: https://github.com/AndyShaman/BYDMate/compare/v3.17.3...v3.17.4
[3.17.3]: https://github.com/AndyShaman/BYDMate/compare/v3.17.2...v3.17.3
[3.17.2]: https://github.com/AndyShaman/BYDMate/compare/v3.17.1...v3.17.2
[3.17.1]: https://github.com/AndyShaman/BYDMate/compare/v3.17.0...v3.17.1
[3.17.0]: https://github.com/AndyShaman/BYDMate/compare/v3.16.0...v3.17.0
[3.16.0]: https://github.com/AndyShaman/BYDMate/compare/v3.15.5...v3.16.0
[3.15.5]: https://github.com/AndyShaman/BYDMate/compare/v3.15.4...v3.15.5
[3.15.4]: https://github.com/AndyShaman/BYDMate/compare/v3.15.3...v3.15.4
[3.15.3]: https://github.com/AndyShaman/BYDMate/compare/v3.15.2...v3.15.3
[3.15.2]: https://github.com/AndyShaman/BYDMate/compare/v3.15.1...v3.15.2
[3.15.1]: https://github.com/AndyShaman/BYDMate/compare/v3.15.0...v3.15.1
[3.15.0]: https://github.com/AndyShaman/BYDMate/compare/v3.14.3...v3.15.0
[3.14.3]: https://github.com/AndyShaman/BYDMate/compare/v3.14.2...v3.14.3
[3.14.2]: https://github.com/AndyShaman/BYDMate/compare/v3.14.1...v3.14.2
[3.14.1]: https://github.com/AndyShaman/BYDMate/compare/v3.14.0...v3.14.1
[3.14.0]: https://github.com/AndyShaman/BYDMate/compare/v3.13.2...v3.14.0
[3.13.2]: https://github.com/AndyShaman/BYDMate/compare/v3.13.1...v3.13.2
[3.13.1]: https://github.com/AndyShaman/BYDMate/compare/v3.13...v3.13.1
[3.13]: https://github.com/AndyShaman/BYDMate/compare/v3.12.1...v3.13
[3.12.1]: https://github.com/AndyShaman/BYDMate/compare/v3.12...v3.12.1
[3.12]: https://github.com/AndyShaman/BYDMate/compare/v3.11.9...v3.12
[3.11.9]: https://github.com/AndyShaman/BYDMate/compare/v3.11.8...v3.11.9
[3.11.8]: https://github.com/AndyShaman/BYDMate/compare/v3.11.7...v3.11.8
[3.11.7]: https://github.com/AndyShaman/BYDMate/compare/v3.11.6...v3.11.7
[3.11.6]: https://github.com/AndyShaman/BYDMate/compare/v3.11.5...v3.11.6
[3.11.5]: https://github.com/AndyShaman/BYDMate/compare/v3.11.3...v3.11.5
[3.11.3]: https://github.com/AndyShaman/BYDMate/compare/v3.11.2...v3.11.3
[3.11.2]: https://github.com/AndyShaman/BYDMate/compare/v3.11.1...v3.11.2
[3.11.1]: https://github.com/AndyShaman/BYDMate/compare/v3.11...v3.11.1
[3.11]: https://github.com/AndyShaman/BYDMate/compare/v3.10...v3.11
[3.10]: https://github.com/AndyShaman/BYDMate/compare/v3.9.3...v3.10
[3.9.3]: https://github.com/AndyShaman/BYDMate/compare/v3.9.2...v3.9.3
[3.9.2]: https://github.com/AndyShaman/BYDMate/compare/v3.9.1...v3.9.2
[3.9.1]: https://github.com/AndyShaman/BYDMate/compare/v3.9...v3.9.1
[3.9]: https://github.com/AndyShaman/BYDMate/compare/v3.8.1...v3.9
[3.8.1]: https://github.com/AndyShaman/BYDMate/compare/v3.8...v3.8.1
[3.8]: https://github.com/AndyShaman/BYDMate/compare/v3.7...v3.8
[3.7]: https://github.com/AndyShaman/BYDMate/compare/v3.6.3...v3.7
[3.6.3]: https://github.com/AndyShaman/BYDMate/compare/v3.6.2...v3.6.3
[3.6.2]: https://github.com/AndyShaman/BYDMate/compare/v3.6.1...v3.6.2
[3.6.1]: https://github.com/AndyShaman/BYDMate/compare/v3.6...v3.6.1
[3.6]: https://github.com/AndyShaman/BYDMate/compare/v3.5.3...v3.6
[3.5.3]: https://github.com/AndyShaman/BYDMate/compare/v3.5.2...v3.5.3
[3.5.2]: https://github.com/AndyShaman/BYDMate/compare/v3.5.1...v3.5.2
[3.5.1]: https://github.com/AndyShaman/BYDMate/compare/v3.5...v3.5.1
[3.5]: https://github.com/AndyShaman/BYDMate/compare/v3.2.2...v3.5
[3.2.2]: https://github.com/AndyShaman/BYDMate/compare/v3.2.1...v3.2.2
[3.2.1]: https://github.com/AndyShaman/BYDMate/compare/v3.2.0...v3.2.1
[3.2.0]: https://github.com/AndyShaman/BYDMate/compare/v3.1.10...v3.2.0
[3.1.10]: https://github.com/AndyShaman/BYDMate/compare/v3.1.6...v3.1.10
[3.1.5]: https://github.com/AndyShaman/BYDMate/compare/v3.1.3...v3.1.5
[3.1.3]: https://github.com/AndyShaman/BYDMate/compare/v3.1.2...v3.1.3
[3.1.2]: https://github.com/AndyShaman/BYDMate/compare/v3.1.1...v3.1.2
[3.1.1]: https://github.com/AndyShaman/BYDMate/compare/v3.1.0...v3.1.1
[3.1.0]: https://github.com/AndyShaman/BYDMate/compare/v3.0.3...v3.1.0
[3.0.3]: https://github.com/AndyShaman/BYDMate/compare/v3.0.2...v3.0.3
[3.0.2]: https://github.com/AndyShaman/BYDMate/compare/v3.0.1...v3.0.2
[3.0.1]: https://github.com/AndyShaman/BYDMate/compare/v3.0.0...v3.0.1
[3.0.0]: https://github.com/AndyShaman/BYDMate/compare/v2.8.1...v3.0.0
[2.8.1]: https://github.com/AndyShaman/BYDMate/compare/v2.8.0...v2.8.1
[2.8.0]: https://github.com/AndyShaman/BYDMate/compare/v2.7.0...v2.8.0
[2.7.0]: https://github.com/AndyShaman/BYDMate/compare/v2.6.1...v2.7.0
[2.6.1]: https://github.com/AndyShaman/BYDMate/compare/v2.6.0...v2.6.1
[2.6.0]: https://github.com/AndyShaman/BYDMate/compare/v2.5.15...v2.6.0
[2.5.15]: https://github.com/AndyShaman/BYDMate/compare/v2.5.14...v2.5.15
[2.5.14]: https://github.com/AndyShaman/BYDMate/compare/v2.5.13...v2.5.14
[2.5.13]: https://github.com/AndyShaman/BYDMate/compare/v2.5.12...v2.5.13
[2.5.12]: https://github.com/AndyShaman/BYDMate/compare/v2.5.11...v2.5.12
[2.5.11]: https://github.com/AndyShaman/BYDMate/compare/v2.5.9...v2.5.11
[2.5.9]: https://github.com/AndyShaman/BYDMate/compare/v2.5.5...v2.5.9
[2.5.5]: https://github.com/AndyShaman/BYDMate/compare/v2.5.4...v2.5.5
[2.5.4]: https://github.com/AndyShaman/BYDMate/compare/v2.5.3...v2.5.4
[2.5.3]: https://github.com/AndyShaman/BYDMate/compare/v2.5.2...v2.5.3
[2.5.2]: https://github.com/AndyShaman/BYDMate/compare/v2.5.1...v2.5.2
[2.5.1]: https://github.com/AndyShaman/BYDMate/compare/v2.5.0...v2.5.1
[2.5.0]: https://github.com/AndyShaman/BYDMate/compare/v2.4.12...v2.5.0
[2.4.12]: https://github.com/AndyShaman/BYDMate/compare/v2.4.11...v2.4.12
[2.4.11]: https://github.com/AndyShaman/BYDMate/compare/v2.4.9...v2.4.11
[2.4.9]: https://github.com/AndyShaman/BYDMate/compare/v2.4.7...v2.4.9
[2.4.7]: https://github.com/AndyShaman/BYDMate/compare/v2.4.6...v2.4.7
[2.4.6]: https://github.com/AndyShaman/BYDMate/compare/v2.4.5...v2.4.6
[2.4.5]: https://github.com/AndyShaman/BYDMate/compare/v2.4.4...v2.4.5
[2.4.4]: https://github.com/AndyShaman/BYDMate/compare/v2.4.3...v2.4.4
[2.4.3]: https://github.com/AndyShaman/BYDMate/compare/v2.4.2...v2.4.3
[2.4.2]: https://github.com/AndyShaman/BYDMate/compare/v2.4.1...v2.4.2
[2.4.1]: https://github.com/AndyShaman/BYDMate/compare/v2.4.0...v2.4.1
[2.4.0]: https://github.com/AndyShaman/BYDMate/compare/v2.3.8...v2.4.0
[2.3.8]: https://github.com/AndyShaman/BYDMate/compare/v2.3.7...v2.3.8
[2.3.7]: https://github.com/AndyShaman/BYDMate/compare/v2.3.6...v2.3.7
[2.3.6]: https://github.com/AndyShaman/BYDMate/compare/v2.3.5...v2.3.6
[2.3.5]: https://github.com/AndyShaman/BYDMate/compare/v2.3.4...v2.3.5
[2.3.4]: https://github.com/AndyShaman/BYDMate/compare/v2.3.3...v2.3.4
[2.3.3]: https://github.com/AndyShaman/BYDMate/compare/v2.3.1...v2.3.3
[2.3.1]: https://github.com/AndyShaman/BYDMate/compare/v2.3.0...v2.3.1
[2.3.0]: https://github.com/AndyShaman/BYDMate/compare/v2.2.0...v2.3.0
[2.2.0]: https://github.com/AndyShaman/BYDMate/compare/v2.1.7...v2.2.0
[2.1.7]: https://github.com/AndyShaman/BYDMate/compare/v2.1.6...v2.1.7
[2.1.6]: https://github.com/AndyShaman/BYDMate/compare/v2.1.5...v2.1.6
[2.1.5]: https://github.com/AndyShaman/BYDMate/compare/v2.1.4...v2.1.5
[2.1.4]: https://github.com/AndyShaman/BYDMate/compare/v2.1.3...v2.1.4
[2.1.3]: https://github.com/AndyShaman/BYDMate/compare/v2.1.2...v2.1.3
[2.1.2]: https://github.com/AndyShaman/BYDMate/compare/v2.1.1...v2.1.2
[2.1.1]: https://github.com/AndyShaman/BYDMate/compare/v2.1.0...v2.1.1
[2.1.0]: https://github.com/AndyShaman/BYDMate/compare/v2.0.9...v2.1.0
[2.0.9]: https://github.com/AndyShaman/BYDMate/compare/v2.0.8...v2.0.9
[2.0.8]: https://github.com/AndyShaman/BYDMate/compare/v2.0.7...v2.0.8
[2.0.7]: https://github.com/AndyShaman/BYDMate/compare/v2.0.6...v2.0.7
[2.0.4]: https://github.com/AndyShaman/BYDMate/compare/v2.0.3...v2.0.4
[2.0.3]: https://github.com/AndyShaman/BYDMate/compare/v2.0.2...v2.0.3
[2.0.1]: https://github.com/AndyShaman/BYDMate/compare/v2.0.0...v2.0.1
[2.0.0]: https://github.com/AndyShaman/BYDMate/compare/v1.0.7...v2.0.0
[1.0.7]: https://github.com/AndyShaman/BYDMate/compare/v1.0.6...v1.0.7
[1.0.6]: https://github.com/AndyShaman/BYDMate/compare/v1.0.5...v1.0.6
[1.0.5]: https://github.com/AndyShaman/BYDMate/compare/v1.0.4...v1.0.5
[1.0.4]: https://github.com/AndyShaman/BYDMate/compare/v1.0.3...v1.0.4
[1.0.3]: https://github.com/AndyShaman/BYDMate/compare/v1.0.2...v1.0.3
[1.0.2]: https://github.com/AndyShaman/BYDMate/compare/v1.0.1...v1.0.2
[1.0.1]: https://github.com/AndyShaman/BYDMate/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/AndyShaman/BYDMate/releases/tag/v1.0.0
