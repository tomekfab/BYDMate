# Offline voice commands

BYDMate runs these phrases directly in the car, without a language model or network connection. This page lists the supported Polish phrases and examples.

[Back to the README](../README.md#voice-ai-agent)

## Setup

The offline profile is Polish only. In **Settings → Voice agent**, download **Speech recognition (Parakeet)**. This also installs the shared VAD model that detects speech. Download the local **Gosia** voice if you want spoken replies, then use **Check Polish language readiness**. Recognition and Gosia run on the head unit; the command list below does not require an assistant connection.

## How it works

- A phrase runs offline only when it matches a supported phrase after normalization. A different wording can be sent to the assistant if it is enabled.
- Politeness and address words do not change recognition: `proszę`, `możesz`, `słuchaj`, `hej`, `dziękuję`, and similar filler words are dropped. `Otwórz okno, proszę` works like `Otwórz okno`.
- Diacritics and punctuation are normalized as defined by the command dictionary. Numbers can be spoken as words or digits: `dwadzieścia dwa` and `22` mean the same value.
- `A i B`, `A oraz B`, and `A a także B` run both commands only when each part is supported. For example: `otwórz okno kierowcy i zamknij szyberdach`.
- Other phrases go to the cloud AI assistant when it is enabled. Without the assistant, an unsupported car request is refused while listed phrases still work.
- The offline command list is Polish. The app interface language does not change it.

Use only the forms shown by the dictionary. The command grammar accepts the listed verb variants, such as `otwórz`, `otworzyć`, and `otwieraj`; for windows it also accepts `opuść` and `podnieś`.

## Windows

### Window names

| Window | Supported names |
| --- | --- |
| All | `okna`, `wszystkie okna`, `szyby`, `okienka`, `okna w samochodzie` |
| Front | `przednie okna`, `okna z przodu`, `okna kierowcy i pasażera` |
| Rear | `tylne okna`, `okna z tyłu`, `tylne okno` |
| Driver | `okno`, `moje okno`, `okno kierowcy`, `okno po stronie kierowcy`, `szybę` |
| Passenger | `okno pasażera`, `okno po stronie pasażera`, `przednie prawe okno` |
| Rear left | `lewe okno`, `tylne lewe okno`, `okno z tyłu po lewej` |
| Rear right | `prawe okno`, `tylne prawe okno`, `okno z tyłu po prawej` |

You can use `okienko` or `szyba` for `okno`. An unqualified `okno` is the driver's window; unqualified `lewe` and `prawe` are rear windows.

### Actions

| Result | How to say it |
| --- | --- |
| Open fully | `otwórz okna`, `opuść okno kierowcy`, `otwórz okna całkowicie` |
| Close | `zamknij okna`, `podnieś okno pasażera`, `zimno, zamknij okna` |
| Open halfway | `otwórz okna do połowy`, `okno kierowcy do połowy`, `otwórz połowę okna` |
| Vent slightly | `uchyl okno kierowcy`, `otwórz okna trochę`, `ustaw okna na wietrzenie`, `przewietrz kabinę` |
| Open to a share | `otwórz okno na 30 procent`, `okna na 70 procent`, `otwórz okna na jedną trzecią`, `otwórz okno na trzy czwarte` |
| Open or close all except some | `otwórz okna oprócz kierowcy`, `zamknij wszystkie okna oprócz tylnych`, `uchyl okna oprócz kierowcy` |

**How a share is interpreted.** A window has three positions: a ventilation gap, halfway, and fully open. A named share is rounded down to the nearest supported position, so the window never opens farther than requested:

- 10 to 49 percent, `na jedną trzecią`, `na ćwierć`, `trochę`, `lekko`, `na szparkę`: ventilation gap;
- 50 to 99 percent, `do połowy`, `na trzy czwarte`: halfway;
- 100 percent, `całkowicie`, `do końca`, `na maksimum`: fully open.

Below 10 percent, a window does not open: the phrase is not in the command list.

## Ventilation mode

| Result | How to say it |
| --- | --- |
| Vent all windows and tilt the sunroof | `tryb wietrzenia`, `włącz tryb wietrzenia` |
| Close all windows and the sunroof | `wyłącz tryb wietrzenia` |

## Sunroof and shade

Call the sunroof `szyberdach`, `dach`, or `okno dachowe`. The sunroof and shade accept only open and close verbs; `opuść` and `podnieś` do not apply.

| Result | How to say it |
| --- | --- |
| Open the sunroof fully | `otwórz szyberdach`, `otwórz dach całkowicie` |
| Close the sunroof | `zamknij szyberdach`, `zamknij dach` |
| Open halfway | `otwórz szyberdach do połowy`, `szyberdach na pół` |
| Tilt for ventilation | `uchyl szyberdach`, `otwórz szyberdach trochę`, `ustaw szyberdach na wietrzenie` |
| Open to a share | `otwórz szyberdach na 30 procent`, `otwórz szyberdach na 80 procent`, `otwórz szyberdach na jedną trzecią` |
| Open the shade | `otwórz roletę`, `otwórz roletę szyberdachu` |
| Close the shade | `zamknij roletę`, `zamknij roletę szyberdachu` |

The sunroof uses the same shares as windows, except that ventilation begins at 7 percent: 7 to 49 percent tilts it, 50 to 99 opens it halfway, and 100 opens it fully.

## Windows and sunroof together

| Result | How to say it |
| --- | --- |
| Open all windows and the sunroof | `otwórz okna i szyberdach`, `otwórz szyberdach oraz okna` |
| Close all windows and the sunroof | `zamknij okna i szyberdach`, `zamknij szyberdach oraz okna` |
| Close the sunroof and shade | `zamknij szyberdach i roletę` |

## Seats

### Seat names

| Seat | Supported names |
| --- | --- |
| Driver | `siedzenia`, `siedzenia kierowcy`, `fotela kierowcy`, `mojego siedzenia`, `lewego siedzenia` |
| Passenger | `siedzenia pasażera`, `fotela pasażera`, `prawego siedzenia` |
| Both front seats | `siedzeń`, `wszystkich siedzeń`, `obu siedzeń`, `przednich siedzeń` |

You can use `fotel` for `siedzenie`, `ogrzewanie` for `podgrzewanie`, and `wentylowanie` for `wentylacja`. An unqualified seat is the driver's seat.

### Level

Levels run from 1 to 3. Without a level, the command selects level 1.

- first: `na 1`, `na pierwszy poziom`, `na minimum`;
- second: `na 2`, `na drugi poziom`;
- third: `na 3`, `na trzeci poziom`, `na maksimum`, `na pełną`.

Levels 4 and 5 are not in the offline list; the assistant can handle such requests when enabled.

### Actions

| Result | How to say it |
| --- | --- |
| Enable seat heating | `włącz ogrzewanie siedzenia`, `ogrzewanie siedzenia pasażera`, `włącz podgrzewanie siedzeń` |
| Heating at a level | `włącz ogrzewanie siedzenia na 2`, `ogrzewanie siedzenia pasażera na maksimum`, `drugi poziom ogrzewania siedzenia` |
| Disable heating | `wyłącz ogrzewanie siedzenia`, `wyłącz podgrzewanie siedzeń`, `wyłącz ogrzewanie siedzenia pasażera` |
| Enable seat ventilation | `włącz wentylację siedzenia`, `wentylacja siedzenia pasażera`, `włącz wentylację wszystkich siedzeń` |
| Ventilation at a level | `włącz wentylację siedzenia na maksimum`, `wentylacja siedzenia pasażera na 2`, `włącz silną wentylację siedzenia` |
| Disable ventilation | `wyłącz wentylację siedzenia`, `wyłącz wentylowanie siedzeń` |

### Seat massage (driver only)

| Result | How to say it |
| --- | --- |
| Start the driver seat massage | `włącz masaż fotela [kierowcy]`, `włącz masaż w fotelu [kierowcy]` |
| Stop it | `wyłącz masaż fotela [kierowcy]`, `wyłącz masaż w fotelu [kierowcy]` |

The commands write one value to the driver seat massage mode: mode 2 (pulse) to start, mode 1 (off) to stop. Massage modes, intensity, the passenger seat, `siedzenia`, both seats and a bare `włącz masaż` are refused offline. The commands run locally and never go to the assistant (OpenClaw) or to the in-app agent.

**Not validated on a car.** The car has no massage status read-back, so a spoken "Gotowe" means only that the write was accepted, not that the seat is massaging. Pending car check: on a parked car, `włącz masaż fotela kierowcy` starts a massage and `wyłącz masaż fotela kierowcy` stops it. Record the car model with the result.

## Steering wheel

Works on vehicles with heated steering wheels.

| Result | How to say it |
| --- | --- |
| Enable steering-wheel heating | `włącz ogrzewanie kierownicy` |
| Disable steering-wheel heating | `wyłącz ogrzewanie kierownicy` |
| Heat steering wheel and both seats | `włącz ogrzewanie kierownicy i siedzeń` |

## Mirrors

| Result | How to say it |
| --- | --- |
| Enable mirror heating | `włącz ogrzewanie lusterek`, `włącz ogrzewanie wszystkich lusterek` |
| Disable mirror heating | `wyłącz ogrzewanie lusterek` |

## Climate

You can use `klimatyzator`, `klima`, or `klimatyzacja`.

| Result | How to say it |
| --- | --- |
| Enable automatic climate control | `włącz klimatyzację`, `klimatyzacja auto`, `włącz tryb automatyczny klimatyzacji` |
| Disable climate control | `wyłącz klimatyzację` |
| Enable cabin airflow | `włącz nawiew`, `włącz wentylację kabiny` |
| Internal recirculation | `włącz recyrkulację`, `włącz obieg wewnętrzny` |
| External air | `wyłącz recyrkulację`, `włącz obieg zewnętrzny` |
| Windscreen airflow | `włącz nawiew na przednią szybę`, `włącz odmglanie przedniej szyby` |
| Disable windscreen airflow | `wyłącz nawiew na przednią szybę` |
| Temperature from 16 to 33 | `ustaw temperaturę na 22`, `temperatura 22`, `22 stopnie`, `ustaw 24 stopnie` |
| One degree warmer | `zrób cieplej`, `cieplej`, `jest zimno`, `zmarzłem` |
| One degree cooler | `zrób chłodniej`, `chłodniej`, `jest gorąco`, `w samochodzie duszno` |

## Climate fan

The speed runs from 1 to 7. Call it `wentylator`, `prędkość wentylatora`, `siłę nawiewu`, or `nawiew`.

| Result | How to say it |
| --- | --- |
| Set speed by number | `wentylator na 3`, `prędkość wentylatora 5`, `ustaw wentylator na 4` |
| Set speed by word | `wentylator na trzeci bieg`, `wentylator na maksimum`, `wentylator na minimum` |

`Na maksimum` and `na pełną` mean speed 7; `na minimum` means speed 1. `Włącz wentylator` alone is not a listed phrase: specify the speed.

## Volume

This controls media volume, not the assistant voice.

| Result | How to say it |
| --- | --- |
| Louder by one step | `głośniej`, `zrób głośniej`, `podgłośnij`, `zwiększ głośność` |
| Quieter by one step | `ciszej`, `zrób ciszej`, `ścisz`, `zmniejsz głośność` |
| Mute | `wycisz dźwięk` |
| Unmute | `włącz dźwięk` |
| Set volume from 0 to 100 | `głośność 20`, `ustaw głośność na 50`, `głośność na 10 procent` |

## Locks

| Result | How to say it |
| --- | --- |
| Lock the car | `zamknij samochód`, `zablokuj drzwi samochodu`, `zablokuj zamki` |
| Unlock the car | `otwórz samochód`, `odblokuj drzwi`, `odemknij zamki` |

`Zamknij drzwi` is not a listed phrase. It can refer to a passenger rather than the car.

## Trunk

| Result | How to say it |
| --- | --- |
| Open the rear trunk | `otwórz bagażnik`, `otwórz tylny bagażnik` |
| Close the rear trunk | `zamknij bagażnik`, `zamknij tylny bagażnik` |

The front trunk is not in the offline list. The assistant can open it only while the car is stationary.

## Lights

| Result | How to say it |
| --- | --- |
| Enable ambient light | `włącz podświetlenie`, `włącz oświetlenie ambientowe`, `włącz ambient` |
| Disable ambient light | `wyłącz podświetlenie`, `wyłącz oświetlenie ambientowe` |
| Enable interior light | `włącz światło w kabinie`, `włącz światło w środku` |
| Disable interior light | `wyłącz światło w kabinie`, `zgaś światło w środku` |
| Enable daytime running lights | `włącz światła dzienne`, `włącz DRL` |
| Disable daytime running lights | `wyłącz światła dzienne`, `wyłącz DRL` |

## Safety

The same limits apply as to the assistant and automations: windows do not open above 120 km/h, the sunroof does not open above 80 km/h, and doors do not unlock above 30 km/h.
