#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Report Cyrillic that is not an approved compatibility input or locale value."""

from __future__ import annotations

import argparse
import ast
from dataclasses import dataclass
from pathlib import Path
import re
import sys

CYRILLIC = re.compile(r"[\u0400-\u052f]")
AUTHORED_SUFFIXES = {".css", ".html", ".java", ".js", ".json", ".kt", ".kts", ".md", ".properties", ".py", ".sh", ".svg", ".toml", ".txt", ".xml", ".yaml", ".yml"}
IGNORED_PARTS = {".git", ".gradle", "build", "node_modules", "out", "target"}
IGNORED_FILES = {"app/lint-baseline.xml"}
BELARUSIAN_RESOURCE_FILES = frozenset({"app/src/main/res/values-be/strings.xml", "app/src/main/res/values-be/strings_hybrid_peripherals.xml", "app/src/debug/res/values-be/nav_capture_errors.xml"})

# Exact external Yandex input values. A value is permitted only in the path that
# consumes it; this is deliberately not a source-file or test-tree waiver.
EXTERNAL_YANDEX_TOKENS = {
    "app/src/main/kotlin/com/bydmate/app/media/NaviGoButton.kt": frozenset({"Поехали"}),
    "app/src/main/kotlin/com/bydmate/app/media/NaviRichNotificationParser.kt": frozenset({"км", "м", "ч", "мин", "завершить маршрут", "отмена", "отменить", "закрыть", "без уведомлений", "выключить уведомления", "парковки", "обзор", "Навигатор запущен"}),
    "app/src/main/kotlin/com/bydmate/app/media/NaviRichPostProcessor.kt": frozenset({"км", "м", "ч", "мин", "Навигатор запущен", "навигатор"}),
    "app/src/main/kotlin/com/bydmate/app/media/NaviScreenReader.kt": frozenset({"Навигатор запущен", "250 м", "28 км", "27 мин", "ул. Качаны"}),
    "app/src/main/kotlin/com/bydmate/app/navdata/NavGuidance.kt": frozenset({"км", "м", "ч", "мин", "300 м", "1,2 км", "Поверните направо"}),
    "app/src/main/kotlin/com/bydmate/app/navdata/NavManeuverCodes.kt": frozenset({
        "(\\d+)[-‑]й\\s+съезд", "въезд на паром", "кольцевое", "круговое", "выезд с кольца", "съезд с кольца", "промежуточная точка", "съезд с парома", "выезд с парома", "прибытие", "маршрут окончен", "конечная", "достигнут", "тоннель", "туннель", "плавный поворот налево", "плавно налево", "держитесь левее", "плавный поворот направо", "плавно направо", "держитесь правее", "резкий поворот налево", "резко налево", "резкий поворот направо", "резко направо", "разворот", "развернитесь", "направо", "поверните налево", "поворот налево", "налево", "поверните направо", "Поверните направо", "поворот направо", "прямо", "продолжайте", "двигайтесь", "развернитесь направо", "разворот направо", "развернитесь налево", "левее", "правее", "въезжайте на кольцо", "войдите в кольцо", "съезжайте с кольца", "выезжайте из кольца", "вы прибыли", "маршрут завершён", "до конца маршрута", "конец маршрута", "продолжайте прямо", "продолжить", "двигайтесь прямо", "левый", "правый", "паром", "кольцо", "круг", "первый", "второй", "третий", "четвёртый", "четвертый", "пятый", "шестой", "седьмой", "восьмой", "девятый", "десятый", "($ORDINAL_WORDS)\\s+съезд(?:|а|у|е|ом)", "съезд(?:|а|у|е|ом)\\s+(?:№\\s*)?(\\d+)", "(?:в|на)?лев(?:о|ее|ый|ая|ое|ой|ую|ого|ом)", "(?:в|на|с)?прав(?:о|а|ее|ый|ая|ое|ой|ую|ого|ом)", "плавн(?:о|ый|ая|ое|ого|ом)", "резк(?:о|ий|ая|ое|ого|им)|крут(?:о|ой|ая|ое|ого)", "кольц(?:о|а|у|е|ом|ев(?:ая|ое|ой|ую|ого|ом))|кругов(?:ая|ое|ой|ую|ого|ом)", "развор(?:от|ота|оте|отом|ачивайтесь|ачивайся|ачиваться)", "камера контроля скорости", "почти на месте", "кольцевое движение", "съезд", "платный", "пошлина", "влево", "вправо", "лево", "право", "левая", "правая", "плавно", "резко", "круто", "кольца", "кольцевая", "круговая", "разворачивайтесь", "съезда",
    }),
}
YANDEX_INPUT_TOKENS = frozenset().union(*EXTERNAL_YANDEX_TOKENS.values())
CHECKER_FIXTURE_FILE = "tools/test_check_polish_only.py"
GENERATED_DETEKT_SIGNATURE_FILE = "config/detekt/baseline.xml"
MANEUVER_CORPUS_FILE = "app/src/test/resources/navdata/maneuver-corpus.txt"
# Frozen regression vocabulary. This literal was reviewed against the 2026-10-22
# characterization corpus; it is not read from the corpus or golden TSV at runtime.
FROZEN_MANEUVER_CORPUS_ROWS = frozenset({
    '1-Й',
    '1-Й СЪЕЗД',
    '1-й',
    '1-й съезд',
    '10-Й',
    '10-Й СЪЕЗД',
    '10-й',
    '10-й съезд',
    '11-Й СЪЕЗД',
    '11-й съезд',
    '2-Й',
    '2-Й ПОВОРОТ',
    '2-Й СЪЕЗД',
    '2-й',
    '2-й поворот',
    '2-й съезд',
    '3-Й',
    '3-Й СЪЕЗД',
    '3-й',
    '3-й съезд',
    '4-Й',
    '4-Й СЪЕЗД',
    '4-й',
    '4-й съезд',
    '5-Й',
    '5-Й СЪЕЗД',
    '5-й',
    '5-й съезд',
    '6-Й',
    '6-Й СЪЕЗД',
    '6-й',
    '6-й съезд',
    '7-Й',
    '7-Й СЪЕЗД',
    '7-й',
    '7-й съезд',
    '8-Й',
    '8-Й СЪЕЗД',
    '8-й',
    '8-й съезд',
    '9-Й',
    '9-Й СЪЕЗД',
    '9-й',
    '9-й съезд',
    'ВОЙДИТЕ В КОЛЬЦО',
    'ВОСЬМОЙ',
    'ВТОРОЙ',
    'ВТОРОЙ СЪЕЗД',
    'ВЪЕЗД НА ПАРОМ',
    'ВЪЕЗЖАЙТЕ НА КОЛЬЦО',
    'ВЫ ПРИБЫЛИ',
    'ВЫЕЗД С КОЛЬЦА',
    'ВЫЕЗД С КОЛЬЦА 1-Й СЪЕЗД',
    'ВЫЕЗД С КОЛЬЦА 10-Й СЪЕЗД',
    'ВЫЕЗД С КОЛЬЦА 2-Й СЪЕЗД',
    'ВЫЕЗД С КОЛЬЦА 3-Й СЪЕЗД',
    'ВЫЕЗД С КОЛЬЦА 4-Й СЪЕЗД',
    'ВЫЕЗД С КОЛЬЦА 5-Й СЪЕЗД',
    'ВЫЕЗД С КОЛЬЦА 6-Й СЪЕЗД',
    'ВЫЕЗД С КОЛЬЦА 7-Й СЪЕЗД',
    'ВЫЕЗД С КОЛЬЦА 8-Й СЪЕЗД',
    'ВЫЕЗД С КОЛЬЦА 9-Й СЪЕЗД',
    'ВЫЕЗД С ПАРОМА',
    'ВЫЕЗЖАЙТЕ ИЗ КОЛЬЦА',
    'Войдите в кольцо',
    'Восьмой',
    'Второй',
    'Второй съезд',
    'Въезд на паром',
    'Въезжайте на кольцо',
    'Вы прибыли',
    'Выезд с кольца',
    'Выезд с кольца 1-й съезд',
    'Выезд с кольца 10-й съезд',
    'Выезд с кольца 2-й съезд',
    'Выезд с кольца 3-й съезд',
    'Выезд с кольца 4-й съезд',
    'Выезд с кольца 5-й съезд',
    'Выезд с кольца 6-й съезд',
    'Выезд с кольца 7-й съезд',
    'Выезд с кольца 8-й съезд',
    'Выезд с кольца 9-й съезд',
    'Выезд с парома',
    'Выезжайте из кольца',
    'ДВИГАЙТЕСЬ',
    'ДВИГАЙТЕСЬ ПРЯМО',
    'ДЕВЯТЫЙ',
    'ДЕРЖИТЕСЬ ЛЕВЕЕ',
    'ДЕРЖИТЕСЬ ПРАВЕЕ',
    'ДЕРЖИТЕСЬ СЕРЕДИНЫ',
    'ДЕСЯТЫЙ',
    'ДО КОНЦА МАРШРУТА',
    'ДОСТИГНУТ',
    'Двигайтесь',
    'Двигайтесь прямо',
    'Девятый',
    'Держитесь левее',
    'Держитесь правее',
    'Держитесь середины',
    'Десятый',
    'До конца маршрута',
    'Достигнут',
    'ЕЗЖАЙТЕ ПРЯМО',
    'Езжайте прямо',
    'КОЛЬЦ',
    'КОЛЬЦЕВАЯ РАЗВЯЗКА',
    'КОЛЬЦЕВОЕ',
    'КОЛЬЦЕВОЕ ДВИЖЕНИЕ',
    'КОЛЬЦЕВОЕ ДВИЖЕНИЕ 1-Й СЪЕЗД',
    'КОЛЬЦЕВОЕ ДВИЖЕНИЕ 10-Й СЪЕЗД',
    'КОЛЬЦЕВОЕ ДВИЖЕНИЕ 2-Й СЪЕЗД',
    'КОЛЬЦЕВОЕ ДВИЖЕНИЕ 3-Й СЪЕЗД',
    'КОЛЬЦЕВОЕ ДВИЖЕНИЕ 4-Й СЪЕЗД',
    'КОЛЬЦЕВОЕ ДВИЖЕНИЕ 5-Й СЪЕЗД',
    'КОЛЬЦЕВОЕ ДВИЖЕНИЕ 6-Й СЪЕЗД',
    'КОЛЬЦЕВОЕ ДВИЖЕНИЕ 7-Й СЪЕЗД',
    'КОЛЬЦЕВОЕ ДВИЖЕНИЕ 8-Й СЪЕЗД',
    'КОЛЬЦЕВОЕ ДВИЖЕНИЕ 9-Й СЪЕЗД',
    'КОЛЬЦЕВОЕ ДВИЖЕНИЕ, ВТОРОЙ СЪЕЗД',
    'КОЛЬЦО',
    'КОНЕЦ МАРШРУТА',
    'КОНЕЧНАЯ',
    'КРУГ',
    'КРУГОВ',
    'КРУГОВОЕ',
    'КРУГОВОЕ ДВИЖЕНИЕ',
    'КРУТО',
    'КРУТО ВЛЕВО',
    'КРУТО ВПРАВО',
    'КРУТО НАЛЕВО',
    'КРУТО НАПРАВО',
    'КРУТО ПОВЕРНИТЕ НАЛЕВО',
    'КРУТО ПОВЕРНИТЕ НАПРАВО',
    'Кольц',
    'Кольцевая развязка',
    'Кольцевое',
    'Кольцевое движение',
    'Кольцевое движение 1-й съезд',
    'Кольцевое движение 10-й съезд',
    'Кольцевое движение 2-й съезд',
    'Кольцевое движение 3-й съезд',
    'Кольцевое движение 4-й съезд',
    'Кольцевое движение 5-й съезд',
    'Кольцевое движение 6-й съезд',
    'Кольцевое движение 7-й съезд',
    'Кольцевое движение 8-й съезд',
    'Кольцевое движение 9-й съезд',
    'Кольцевое движение, второй съезд',
    'Кольцо',
    'Конец маршрута',
    'Конечная',
    'Круг',
    'Кругов',
    'Круговое',
    'Круговое движение',
    'Круто',
    'Круто влево',
    'Круто вправо',
    'Круто налево',
    'Круто направо',
    'Круто поверните налево',
    'Круто поверните направо',
    'ЛЕВ',
    'ЛЕВЕЕ',
    'ЛЕВЫЙ',
    'ЛЕВЯ',
    'Лев',
    'Левее',
    'Левый',
    'Левя',
    'МАРШРУТ ЗАВЕРШЁН',
    'МАРШРУТ ОКОНЧЕН',
    'Маршрут завершён',
    'Маршрут окончен',
    'НАЛЕВО',
    'НАПРАВА',
    'НАПРАВО',
    'Налево',
    'Направа',
    'Направо',
    'ОҢҒА БҰРЫЛЫҢЫЗ',
    'Оңға бұрылыңыз',
    'ПАВЯРНІЦЕ НАПРАВА',
    'ПАРОМ',
    'ПАРОМ ЧЕРЕЗ ЧАС',
    'ПЕРВЫЙ',
    'ПЕРЕСАДКА НА ПАРОМ',
    'ПЛАВН',
    'ПЛАВНО ВЛЕВО',
    'ПЛАВНО ВПРАВО',
    'ПЛАВНО НАЛЕВО',
    'ПЛАВНО НАПРАВО',
    'ПЛАВНЫЙ ПОВОРОТ НАЛЕВО',
    'ПЛАВНЫЙ ПОВОРОТ НАПРАВО',
    'ПЛАТНЫЙ УЧАСТОК',
    'ПЛАТНЫЙ\u005cu00A0УЧАСТОК',
    'ПОВЕРНИТЕ ЛЕВЕЕ',
    'ПОВЕРНИТЕ НАЛЕВО',
    'ПОВЕРНИТЕ НАЛЕВО 2-Й ПОВОРОТ',
    'ПОВЕРНИТЕ НАПРАВО',
    'ПОВЕРНИТЕ НАПРАВО 2-Й ПОВОРОТ',
    'ПОВЕРНИТЕ НАПРАВО НА УЛИЦУ ЛЕНИНА',
    'ПОВЕРНИТЕ ПРАВЕЕ',
    'ПОВОРОТ НАЛЕВО',
    'ПОВОРОТ НАПРАВО',
    'ПРАВ',
    'ПРАВЕЕ',
    'ПРАВО РУЛЯ',
    'ПРАВЫЙ',
    'ПРИБЫТИЕ',
    'ПРОДОЛЖАЙТЕ',
    'ПРОДОЛЖАЙТЕ ДВИЖЕНИЕ ПРЯМО',
    'ПРОДОЛЖАЙТЕ ПРЯМО',
    'ПРОДОЛЖИТЬ',
    'ПРОМЕЖУТОЧНАЯ ТОЧКА',
    'ПРОМЕЖУТОЧНАЯ\u005cu00A0ТОЧКА',
    'ПРЯМО',
    'ПЯТЫЙ',
    'Павярніце направа',
    'Паром',
    'Паром через час',
    'Первый',
    'Пересадка на паром',
    'Плавн',
    'Плавно влево',
    'Плавно вправо',
    'Плавно налево',
    'Плавно направо',
    'Плавный поворот налево',
    'Плавный поворот направо',
    'Платный участок',
    'Платный\u005cu00A0участок',
    'Поверните левее',
    'Поверните налево',
    'Поверните налево 2-й поворот',
    'Поверните направо',
    'Поверните направо 2-й поворот',
    'Поверните направо на улицу Ленина',
    'Поверните направо на улицу ленина',
    'Поверните правее',
    'Поворот налево',
    'Поворот направо',
    'Прав',
    'Правее',
    'Право руля',
    'Правый',
    'Прибытие',
    'Продолжайте',
    'Продолжайте движение прямо',
    'Продолжайте прямо',
    'Продолжить',
    'Промежуточная точка',
    'Промежуточная\u005cu00A0точка',
    'Прямо',
    'Пятый',
    'РАЗВЕРНИТЕСЬ',
    'РАЗВЕРНИТЕСЬ НАЛЕВО',
    'РАЗВЕРНИТЕСЬ НАПРАВО',
    'РАЗВОР',
    'РАЗВОРАЧИВАЙТЕСЬ',
    'РАЗВОРОТ',
    'РАЗВОРОТ НАЛЕВО',
    'РАЗВОРОТ НАПРАВО',
    'РЕЗК',
    'РЕЗКИЙ ПОВОРОТ НАЛЕВО',
    'РЕЗКИЙ ПОВОРОТ НАПРАВО',
    'РЕЗКИЙ\u005cu00A0ПОВОРОТ\u005cu00A0НАЛЕВО',
    'РЕЗКО ВЛЕВО',
    'РЕЗКО ВПРАВО',
    'РЕЗКО НАЛЕВО',
    'РЕЗКО НАПРАВО',
    'Развернитесь',
    'Развернитесь налево',
    'Развернитесь направо',
    'Развор',
    'Разворачивайтесь',
    'Разворот',
    'Разворот налево',
    'Разворот направо',
    'Резк',
    'Резкий поворот налево',
    'Резкий поворот направо',
    'Резкий\u005cu00A0поворот\u005cu00A0налево',
    'Резко влево',
    'Резко вправо',
    'Резко налево',
    'Резко направо',
    'СВЕРНИТЕ НАЛЕВО',
    'СВЕРНИТЕ НАПРАВО',
    'СЕДЬМОЙ',
    'СЪЕЗД 1',
    'СЪЕЗД 10',
    'СЪЕЗД 2',
    'СЪЕЗД 3',
    'СЪЕЗД 4',
    'СЪЕЗД 5',
    'СЪЕЗД 6',
    'СЪЕЗД 7',
    'СЪЕЗД 8',
    'СЪЕЗД 9',
    'СЪЕЗД НА ПАРОМ',
    'СЪЕЗД С КОЛЬЦА',
    'СЪЕЗД С ПАРОМА',
    'СЪЕЗД ЧЕРЕЗ 300 М',
    'СЪЕЗД\u005cu00A0С\u005cu00A0КОЛЬЦА',
    'СЪЕЗЖАЙТЕ С КОЛЬЦА',
    'Сверните налево',
    'Сверните направо',
    'Седьмой',
    'Съезд 1',
    'Съезд 10',
    'Съезд 2',
    'Съезд 3',
    'Съезд 4',
    'Съезд 5',
    'Съезд 6',
    'Съезд 7',
    'Съезд 8',
    'Съезд 9',
    'Съезд на паром',
    'Съезд с кольца',
    'Съезд с парома',
    'Съезд через 300 м',
    'Съезд\u005cu00A0с\u005cu00A0кольца',
    'Съезжайте с кольца',
    'ТОННЕЛЬ',
    'ТРЕТИЙ',
    'ТРЕТИЙ СЪЕЗД С КОЛЬЦА',
    'ТУННЕЛЬ',
    'Тоннель',
    'Третий',
    'Третий съезд с кольца',
    'Туннель',
    'УЛИЦА ЛЕНИНА',
    'Улица ленина',
    'ЧЕТВЁРТЫЙ',
    'ЧЕТВЕРТЫЙ',
    'Четвертый',
    'Четвёртый',
    'ШЕСТОЙ',
    'Шестой',
    'войдите в кольцо',
    'восьмой',
    'второй',
    'второй съезд',
    'въезд на паром',
    'въезжайте на кольцо',
    'вы прибыли',
    'выезд с кольца',
    'выезд с кольца 1-й съезд',
    'выезд с кольца 10-й съезд',
    'выезд с кольца 2-й съезд',
    'выезд с кольца 3-й съезд',
    'выезд с кольца 4-й съезд',
    'выезд с кольца 5-й съезд',
    'выезд с кольца 6-й съезд',
    'выезд с кольца 7-й съезд',
    'выезд с кольца 8-й съезд',
    'выезд с кольца 9-й съезд',
    'выезд с парома',
    'выезжайте из кольца',
    'двигайтесь',
    'двигайтесь прямо',
    'девятый',
    'держитесь левее',
    'держитесь правее',
    'десятый',
    'до конца маршрута',
    'достигнут',
    'езжайте прямо',
    'кольц',
    'кольцевое',
    'кольцевое движение',
    'кольцевое движение 1-й съезд',
    'кольцевое движение 10-й съезд',
    'кольцевое движение 2-й съезд',
    'кольцевое движение 3-й съезд',
    'кольцевое движение 4-й съезд',
    'кольцевое движение 5-й съезд',
    'кольцевое движение 6-й съезд',
    'кольцевое движение 7-й съезд',
    'кольцевое движение 8-й съезд',
    'кольцевое движение 9-й съезд',
    'кольцо',
    'конец маршрута',
    'конечная',
    'круг',
    'кругов',
    'круговое',
    'круговое движение',
    'круто',
    'круто налево',
    'круто направо',
    'круто поверните налево',
    'круто поверните направо',
    'лев',
    'левее',
    'левый',
    'левя',
    'маршрут завершён',
    'маршрут окончен',
    'налево',
    'направо',
    'паром',
    'паром через час',
    'первый',
    'пересадка на паром',
    'плавн',
    'плавно налево',
    'плавно направо',
    'плавный поворот налево',
    'плавный поворот направо',
    'платный участок',
    'платный\u005cu00A0участок',
    'поверните левее',
    'поверните налево',
    'поверните налево 2-й поворот',
    'поверните направо',
    'поверните направо 2-й поворот',
    'поверните правее',
    'поворот налево',
    'поворот направо',
    'прав',
    'правее',
    'правый',
    'прибытие',
    'продолжайте',
    'продолжайте прямо',
    'продолжить',
    'промежуточная точка',
    'прямо',
    'пятый',
    'развернитесь',
    'развернитесь налево',
    'развернитесь направо',
    'развор',
    'разворот',
    'разворот налево',
    'разворот направо',
    'резк',
    'резкий поворот налево',
    'резкий поворот направо',
    'резко налево',
    'резко направо',
    'сверните налево',
    'сверните направо',
    'седьмой',
    'съезд 1',
    'съезд 10',
    'съезд 2',
    'съезд 3',
    'съезд 4',
    'съезд 5',
    'съезд 6',
    'съезд 7',
    'съезд 8',
    'съезд 9',
    'съезд на паром',
    'съезд с кольца',
    'съезд с парома',
    'съезд через 300 м',
    'съезжайте с кольца',
    'тоннель',
    'третий',
    'туннель',
    'улица Ленина',
    'четвертый',
    'четвёртый',
    'шестой',
})
NATIVE_SONGL_CAPTURE_TOKENS = frozenset({
    'проветрить',
    'Проветрить',
    'не сдвинулось',
    'окно водителя не сдвинулось с места, команда не сработала',
    'открыть',
    'Открыть',
    'половина',
    'Половина',
    'закрыть',
    'Закрыть',
})

REPORT_BACKED_ORACLE_TOKENS = {
    'app/src/test/kotlin/com/bydmate/app/agent/AgentToolsChargersTest.kt': frozenset({'Минск', 'Минск, Пр-т Независимости, 116', 'Петра Мстиславца', 'Петра Мстиславца 22а, Минск', 'Пр-т Независимости, 116', 'ТД Азербайджан', '№46 - 2×22 кВт (Type 2-Type 2) - Мстиславца 6 Минск', '№48 - 40 кВт - Мстиславца 6'}),
    'app/src/test/kotlin/com/bydmate/app/agent/AgentToolsHomeWorkTest.kt': frozenset({'Дом'}),
    'app/src/test/kotlin/com/bydmate/app/agent/AgentToolsPlacesTest.kt': frozenset({'Орша'}),
    'app/src/test/kotlin/com/bydmate/app/agent/AgentToolsRangeToDestTest.kt': frozenset({'Орша'}),
    'app/src/test/kotlin/com/bydmate/app/agent/AgentToolsRouteEnergyTest.kt': frozenset({'-5 км', '1 234 км', '1,5 км', '5 км', '800 м', 'Осталось 124 км'}),
    'app/src/test/kotlin/com/bydmate/app/agent/AgentToolsRouteInfoTest.kt': frozenset({'250 м', '27 мин', '28 км', '300 м', '350 м', '5 км', '500 м', 'направо', 'пр. Мира', 'прямо', 'ул. Качаны', 'улица Ленина'}),
    'app/src/test/kotlin/com/bydmate/app/agent/ChargerSearchClientTest.kt': frozenset({'Малахит', 'Минск', 'Орша', 'Орша, улица Мира, 4', 'Россети', 'ул. Навуковая'}),
    'app/src/test/kotlin/com/bydmate/app/hud/HudAmapBroadcasterTest.kt': frozenset({'Ленина'}),
    'app/src/test/kotlin/com/bydmate/app/hud/HudAmapClusterLoopTest.kt': frozenset({'Ленина'}),
    'app/src/test/kotlin/com/bydmate/app/hud/HudControllerWayTest.kt': frozenset({'Проспект Независимости'}),
    'app/src/test/kotlin/com/bydmate/app/hud/HudManeuverJournalTest.kt': frozenset({'Поверните налево', 'Поверните направо'}),
    'app/src/test/kotlin/com/bydmate/app/hud/HudProtobufBuilderTest.kt': frozenset({'Ленина', 'ул'}),
    'app/src/test/kotlin/com/bydmate/app/hud/HudPushLoopTest.kt': frozenset({'Ленина', 'Минское шоссе', 'Победителей', 'пр', 'ул'}),
    'app/src/test/kotlin/com/bydmate/app/hud/HudRoadScriptTest.kt': frozenset({'Ленина', 'М', 'ул'}),
    'app/src/test/kotlin/com/bydmate/app/hud/HudWayChannelsTest.kt': frozenset({'Проспект Независимости', 'Щукина', 'щ'}),
    'app/src/test/kotlin/com/bydmate/app/media/ClusterMusicCardTest.kt': frozenset({'Кино', 'Цой', 'Я'}),
    'app/src/test/kotlin/com/bydmate/app/media/NaviNotificationParserTest.kt': frozenset({'км', 'м', 'улица Ленина'}),
    'app/src/test/kotlin/com/bydmate/app/media/NaviRichNotificationParserTest.kt': frozenset({'Завершить маршрут', 'Ленинский проспект', 'Навигатор запущен', 'Поверните направо', 'Прибытие в', 'Тверская', 'Через', 'другая', 'км', 'м', 'м поверните направо', 'мин', 'улица Ленина', 'ч'}),
    'app/src/test/kotlin/com/bydmate/app/media/NaviRichPostProcessorTest.kt': frozenset({'Для работы приложения', 'Ленинский проспект', 'Навигатор запущен', 'Навигатор работает', 'Поверните налево', 'Поверните направо', 'Поверните направо на улицу Ленина', 'Тверская', 'Установите сервисы', 'Яндекс Навигатор', 'м', 'мин', 'поверните направо', 'требуется', 'ч'}),
    'app/src/test/kotlin/com/bydmate/app/media/NaviRouteHolderPackagesTest.kt': frozenset({'км', 'направо'}),
    'app/src/test/kotlin/com/bydmate/app/media/NaviRouteHolderTest.kt': frozenset({'км', 'м', 'улица Ленина'}),
    'app/src/test/kotlin/com/bydmate/app/media/NaviScreenReaderTest.kt': frozenset({'Качаны', 'км', 'м', 'мин', 'ул'}),
    'app/src/test/kotlin/com/bydmate/app/navdata/NavA11yExtractorTest.kt': frozenset({'Поверните направо', 'Тверская улица', 'налево', 'налево на Ленина улицу'}),
    'app/src/test/kotlin/com/bydmate/app/navdata/NavA11yFeedNoGuidanceTraceTest.kt': frozenset({'Ленина', 'Маршрут', 'Поверните направо', 'м'}),
    'app/src/test/kotlin/com/bydmate/app/navdata/NavA11yFeedSourceFallbackTest.kt': frozenset({'Ленина', 'м'}),
    'app/src/test/kotlin/com/bydmate/app/navdata/NavA11yFeedTimerTest.kt': frozenset({'Ленина', 'м'}),
    'app/src/test/kotlin/com/bydmate/app/navdata/NavA11yFeedTreeDumpTest.kt': frozenset({'Ленина', 'Плавно налево', 'Поверните налево', 'Поверните направо', 'м'}),
    'app/src/test/kotlin/com/bydmate/app/navdata/NavA11yFeedUnknownManeuverTest.kt': frozenset({'Ленина', 'Поверните направо', 'м', 'налево'}),
    'app/src/test/kotlin/com/bydmate/app/navdata/NavGuidanceHubStaleArrowTest.kt': frozenset({'поверните направо'}),
    'app/src/test/kotlin/com/bydmate/app/navdata/NavGuidanceHubTest.kt': frozenset({'А', 'Б', 'В', 'Езжайте к Тверской', 'Ленина', 'Поверните направо', 'Поверните направо на Тверскую', 'Съезжайте на Тверскую', 'Тверск', 'Тверскую', 'й съезд', 'й съезд на Ленина', 'один два три четыре пять шесть семь восемь девять десять', 'поверните направо', 'съезжайте на', 'ул', 'улица Ленина'}),
    'app/src/test/kotlin/com/bydmate/app/navdata/NavGuidanceIssue199Test.kt': frozenset({'Ленина', 'Поверните налево', 'Поверните направо', 'км', 'м', 'мин'}),
    'app/src/test/kotlin/com/bydmate/app/navdata/NavGuidanceParserTest.kt': frozenset({'Выезд с кольца', 'Качаны', 'Ленина', 'Поверните направо', 'Тверская', 'км', 'м', 'мин', 'поверните направо', 'скоро', 'ул', 'ч'}),
    'app/src/test/kotlin/com/bydmate/app/navdata/NavManeuverCodesTest.kt': frozenset({'Въезд на паром', 'Въезжайте на кольцо', 'Выезд с кольца', 'Держитесь левее', 'Камера контроля скорости', 'Кольцевое движение', 'Плавно направо', 'Поверните направо', 'Поворот налево', 'Прибытие', 'Продолжайте движение прямо', 'Промежуточная', 'Промежуточная точка', 'Развернитесь', 'Развернитесь направо', 'Резкий', 'Резкий поворот налево', 'Резко направо', 'Съезд', 'Тоннель', 'й съезд', 'кольца', 'кольцо', 'налево', 'паром', 'паром через час', 'платный', 'платный участок', 'поворот', 'почти на месте', 'с', 'съезд', 'съезд на паром', 'точка', 'улица Ленина', 'участок'}),
    'app/src/test/kotlin/com/bydmate/app/navdata/NavManeuverCompetitorsTest.kt': frozenset({'Второй съезд', 'Держитесь середины', 'Кольцевая развязка', 'Кольцевое движение', 'Круг', 'Круто влево', 'Круто поверните налево', 'Крутоярская улица', 'Пересадка на паром', 'Плавникова улица', 'Плавно влево', 'Плавно вправо', 'Проспект Правды', 'Разворачивайтесь', 'Резко вправо', 'Резкое исправление маршрута', 'Съезд', 'Съездовская линия', 'Третий съезд', 'Третий съезд с кольца', 'Улица Кольцова', 'Улица Кругова', 'Улица Левашова', 'Через', 'второй съезд', 'й поворот', 'км второй съезд', 'км держитесь середины', 'км пятый съезд', 'км съезд', 'через'}),
    'app/src/test/kotlin/com/bydmate/app/navdata/UnknownManeuverGateTest.kt': frozenset({'Поверните направо'}),
    'app/src/test/kotlin/com/bydmate/app/ui/settings/RecordingDumpFormatTest.kt': frozenset({'Ленина', 'Ленина ул'}),
    'app/src/test/kotlin/com/bydmate/app/voice/SelfEchoFilterTest.kt': frozenset({'Ёжик', 'ежик'}),
    'app/src/test/kotlin/com/bydmate/app/helper/DisplayDumpFixtures.kt': frozenset({'Встроенный экран'}),
    'app/src/test/kotlin/com/bydmate/app/helper/offreport/OffReportTest.kt': frozenset({'Заряд 64%', 'Открыть на карте', 'машина выключена в 18:54'}),
    'app/src/test/kotlin/com/bydmate/app/ui/settings/SplitHowToHintTest.kt': frozenset({'Голасам:'}),
    'app/src/test/kotlin/com/bydmate/app/util/AppStringsTest.kt': frozenset({'Захавана', 'МБ'}),
}

# Every value below is persisted data, external data, or a supported non-Russian
# locale. Authored labels and prose remain reportable.
EXACT_TOKEN_ALLOWANCES = {
    "app/src/main/kotlin/com/bydmate/app/agent/AgentTools.kt": frozenset({"дом", "домой", "до дома", "мой дом", "работа", "работу", "на работу", "моя работа", "км", "м"}),
    "app/src/main/kotlin/com/bydmate/app/data/automation/PowerStateRuleMigration.kt": frozenset({"Шторка при движении"}),
    "app/src/main/kotlin/com/bydmate/app/data/repository/SettingsRepository.kt": frozenset({"сом"}),
    "app/src/main/kotlin/com/bydmate/app/ui/automation/AutomationViewModel.kt": frozenset({"Справаздача ў Telegram", "Дзе машына", "Перадача = P", "Стан пры запуску", 'Зачыніць вокны на шашы', 'Хуткасць > 100 км/г', 'Зачыніць усе вокны', 'Зімовы старт', 'Тэмп. звонку < 0°C', 'Запуск BYDMate', 'Падагрэў кіроўцы 2', 'Падагрэў люстэркаў укл.', 'ECO пры нізкім зарадзе', 'Рэжым ECO', 'Летняе ахалоджванне', 'Тэмп. салона > 30°C', 'Вентыляцыя кіроўцы 1', 'Аўта AC', 'Шторка падчас руху', 'Перадача = D', 'Адкрыць шторку', 'Клімат падчас зарадкі', 'Зарадка = пачата', 'Тэмп. звонку < 5°C', 'Падагрэў кіроўцы 1'}),
    "app/src/main/kotlin/com/bydmate/app/ui/dashboard/DashboardScreen.kt": frozenset({"кВтч", "км", "мВ"}),
    "app/src/main/kotlin/com/bydmate/app/util/AppLanguage.kt": frozenset({"Беларуская"}),
    "app/src/main/res/values/strings.xml": frozenset({"Русский язык", "ЮMoney"}),
    "app/src/main/res/values-en/strings.xml": frozenset({"Русский язык", "ЮMoney"}),
    "app/src/main/res/values-pl/strings.xml": frozenset({"Русский язык", "ЮMoney"}),
    "app/src/main/res/values-pt/strings.xml": frozenset({"Русский язык", "ЮMoney"}),
    "app/src/main/res/values-zh/strings.xml": frozenset({"Русский язык", "ЮMoney"}),
    "docs/plans/2026-10-21-multi-navigator-hud-feasibility.md": frozenset({"Навигатор запущен", "км", "м", "ч", "мин"}),
    "docs/voice/polish-acceptance.md": frozenset({"Так.", "Закрой все окна.", "Открой окно водителя.", "Закры у все викна."}),
}

# Test fixtures remain checked. These are the exact retained test-oracle values
# whose surrounding assertions prove external input, historical serialized bytes,
# Unicode guard coverage, or supported Belarusian output.
TEST_ORACLE_TOKEN_ALLOWANCES = {
    "app/src/test/kotlin/com/bydmate/app/data/automation/RuleShareTest.kt": frozenset({"Дом: звонок", "Скорость > 7 км/ч", "Закрыть все окна"}),
    "app/src/test/kotlin/com/bydmate/app/agent/AgentTraceTest.kt": frozenset({"Северная", "ТЦ Dana Mall", "Минск, ул. Мстиславца, 11", "Мстиславца"}),
    "app/src/test/kotlin/com/bydmate/app/agent/AgentToolsRouteEnergyTest.kt": frozenset({"5 км", "1 234 км", "800 м", "1,5 км", "-5 км", "Осталось 124 км"}),
    "app/src/test/kotlin/com/bydmate/app/agent/AgentToolsRouteInfoTest.kt": frozenset({"250 м", "28 км", "27 мин", "ул. Качаны", "5 км", "Через 300 м направо", "направо", "улица Ленина", "42 км", "350 м", "прямо", "500 м", "пр. Мира"}),
    "app/src/test/kotlin/com/bydmate/app/agent/AgentToolsNavigateVerifyTest.kt": frozenset({"домой"}),
    "app/src/test/kotlin/com/bydmate/app/agent/AgentToolsNavigateRangeTest.kt": frozenset({"точка", "кофейня рядом", "домой", "на работу", "Дом"}),
    "app/src/test/kotlin/com/bydmate/app/agent/AgentToolsSplitScreenTest.kt": frozenset({"Навигатор", "Камера", "Браузер", "Калькулятор"}),
    "app/src/test/kotlin/com/bydmate/app/agent/AgentToolsLaunchTest.kt": frozenset({"Навигатор", "Настройки", "Яндекс Музыка", "Телефон", "Карты", "Карточки", "Яндекс Карты"}),
    "app/src/test/kotlin/com/bydmate/app/agent/AgentToolsWhereAmITest.kt": frozenset({"Минск", "проспект Независимости", "Ленинский район", "Беларусь", "М-1", "Минская область"}),
    "app/src/test/kotlin/com/bydmate/app/agent/SettlementSearchClientTest.kt": frozenset({"Валожын", "Воложин", "Семково", "Без координат", "Минск", "проспект Независимости", "Ленинский район", "Беларусь"}),
    "app/src/test/kotlin/com/bydmate/app/agent/AgentToolsChargersTest.kt": frozenset({"Пр-т Независимости, 116 (TZone)", "Минск, Пр-т Независимости, 116", "Петра Мстиславца 22а, Минск", "ТД Азербайджан (forEVo,7)", "№48 - 40 кВт - Мстиславца 6", "№46 - 2×22 кВт (Type 2-Type 2) - Мстиславца 6 Минск", "Минск, ул. Пулихова, 5"}),
    "app/src/test/kotlin/com/bydmate/app/agent/BetaMapClientTest.kt": frozenset({"Пр-т Независимости, 116 (TZone)", "Минск, Пр-т Независимости, 116", "ул. Навуковая, 2 (\"ООО Волдан\")", "Без координат", "Есть"}),
    "app/src/test/kotlin/com/bydmate/app/agent/MalankaGatewayClientTest.kt": frozenset({"Ул. Академика Федорова, 5 (TZone / Vityaz)", "Минск, Ул. Академика Фёдорова, 5", "Минск, ул. Пулихова, 5", "Без координат", "БЦ \"Capital Palace\" - Минск", "г. Минск, ул. Интернациональная, 38", "АЗС", "г. Минск"}),
    "app/src/test/kotlin/com/bydmate/app/agent/ChargerSearchClientTest.kt": frozenset({"ЭЗС Орша", "Россети", "Зарядная станция", "Малахит", "Орша", "улица Мира", "Минск, ул. Пулихова, 5", "ул. Навуковая"}),
    "app/src/test/kotlin/com/bydmate/app/data/automation/RouteNavigatorUrisTest.kt": frozenset({"кафе", "Кафе", "кафе у дома"}),
    "app/src/test/kotlin/com/bydmate/app/data/automation/PowerStateRuleMigrationTest.kt": frozenset({"Шторка при движении"}),
    "app/src/test/kotlin/com/bydmate/app/data/automation/ActionDispatcherToggleTest.kt": frozenset({"Переключить: Багажник", "Переключить: Люк"}),
    "app/src/test/kotlin/com/bydmate/app/data/telegram/TelegramReporterTest.kt": frozenset({"Заряд 64%"}),
    "app/src/test/kotlin/com/bydmate/app/ui/automation/RuleTextTest.kt": frozenset({"Экран: выключыць", "Экран: уключыць"}),
    "app/src/test/kotlin/com/bydmate/app/ui/automation/StarterTemplatesTest.kt": frozenset({"Дзе машына", "Стан пры запуску", "Перадача = P", "Справаздача ў Telegram"}),
    "app/src/test/kotlin/com/bydmate/app/voice/VoiceAutomationActionsAgentQueryTest.kt": frozenset({"Ѐ", "ӿ"}),
    "app/src/test/kotlin/com/bydmate/app/voice/VoiceAutomationActionsSpeakTest.kt": frozenset({"Ѐ", "ӿ"}),
    "app/src/test/kotlin/com/bydmate/app/media/MediaSessionListenerServiceTest.kt": frozenset({
        "500 м", "450 м", "Поверните направо", "Тверская", "Навигатор",
        "Установите сервисы Google Play", 'Для работы приложения "Яндекс Навигатор" требуется',
    }),
    "app/src/test/kotlin/com/bydmate/app/hud/HudTextSanitizerTest.kt": frozenset({
        "Проспект Независимости", "Щукина", "Жукова", "Ярослава Гашека", "Объездная",
        "Ленинградский пр.", "Ёлкина Хвойная Центральная Чехова Экспо", "вуліца Кірава",
        "Вуліца Паўлюка", "Пад’езд", "ЖУКОВА", "Щ.", "ул. ЩОРСА", " Ленина ",
    }),
}

TEST_ORACLE_LINE_ALLOWANCES = {
    "app/src/test/kotlin/com/bydmate/app/agent/AgentToolsHomeWorkTest.kt": frozenset({
        '            PlaceEntity(name = "Дом", lat = 52.2297, lon = 21.0122, radiusM = 100),',
        '        assertEquals("Дом", out.getString("place"))',
    }),
    "app/src/main/kotlin/com/bydmate/app/agent/AgentTools.kt": frozenset({
        '            "praca", "do pracy", "moja praca" -> HomeWork("Praca", "work", legacyPlaceName = "Работа")',
    }),
}

# Launcher labels are external data. These are exact character-folding pairs used
# for matching, never an allowance for a Cyrillic launcher label or other prose.
LAUNCHER_LABEL_CYRILLIC_FOLD_PAIRS = frozenset({
    "'а' to \"a\"", "'б' to \"b\"", "'в' to \"v\"", "'г' to \"g\"", "'д' to \"d\"",
    "'е' to \"e\"", "'ё' to \"e\"", "'ж' to \"zh\"", "'з' to \"z\"", "'и' to \"i\"",
    "'й' to \"i\"", "'к' to \"k\"", "'л' to \"l\"", "'м' to \"m\"", "'н' to \"n\"",
    "'о' to \"o\"", "'п' to \"p\"", "'р' to \"r\"", "'с' to \"s\"", "'т' to \"t\"",
    "'у' to \"u\"", "'ф' to \"f\"", "'х' to \"h\"", "'ц' to \"ts\"", "'ч' to \"ch\"",
    "'ш' to \"sh\"", "'щ' to \"shch\"", "'ъ' to \"\"", "'ы' to \"y\"", "'ь' to \"\"",
    "'э' to \"e\"", "'ю' to \"yu\"", "'я' to \"ya\"",
})

# The checker's test source is allowed to contain only its specific Unicode
# scanner oracles. It is not a general test-file exception.
CHECKER_TEST_ORACLE_TOKENS = frozenset({"Настройки", "Поверните направо", "Добра", "Дом", "Навигатор запущен"})
SAVED_PLACE_KEY = 'legacyPlaceName = "Дом"'
MIGRATION_EXCEPTION_DOCUMENT = "docs/voice/polish-only-migration.md"
CONFIGURATION_TOKENS = frozenset(
    token
    for tokens in EXACT_TOKEN_ALLOWANCES.values()
    for token in tokens
) | frozenset(
    token
    for tokens in TEST_ORACLE_TOKEN_ALLOWANCES.values()
    for token in tokens
) | frozenset(
    token.replace('"', r'\"')
    for tokens in TEST_ORACLE_TOKEN_ALLOWANCES.values()
    for token in tokens
) | YANDEX_INPUT_TOKENS | CHECKER_TEST_ORACLE_TOKENS | frozenset({SAVED_PLACE_KEY, "Дом", "Работа", "ё", "е", "й", "а", "у", "о"})
CONFIGURATION_TOKENS |= LAUNCHER_LABEL_CYRILLIC_FOLD_PAIRS
CONFIGURATION_TOKENS |= frozenset(
    token.replace('"', r'\"') for token in LAUNCHER_LABEL_CYRILLIC_FOLD_PAIRS
)
CONFIGURATION_TOKENS |= FROZEN_MANEUVER_CORPUS_ROWS | NATIVE_SONGL_CAPTURE_TOKENS
CONFIGURATION_TOKENS |= frozenset(
    token
    for tokens in REPORT_BACKED_ORACLE_TOKENS.values()
    for token in tokens
)

TRANSLITERATION_TABLE_PATHS = frozenset({
    "app/src/main/kotlin/com/bydmate/app/data/automation/RuleShare.kt",
    "app/src/main/kotlin/com/bydmate/app/hud/HudTextSanitizer.kt",
})
TRANSLITERATION_TABLE_LINE = re.compile(r'''\s*(?:'[\u0400-\u052fł]' to "[a-z]*",\s*)+''')
SAVED_PLACE_ALIAS_LINE = re.compile(
    r'''\s*"(?:дом|домой|до дома|мой дом|работа|работу|на работу|моя работа)"(?:,\s*"(?:дом|домой|до дома|мой дом|работа|работу|на работу|моя работа|home|work)")*\s*->\s*HomeWork\("(?:Дом|Работа)",\s*"(?:home|work)"\)'''
)
SELF_ECHO_LEGACY_FOLD = re.compile(r'''\s*(?:\* .*`ё`→`е` fold.*|\.replace\("ё", "е"\))\s*''')
DASHBOARD_UNITS_LINE = '    val numberPattern = Regex("""(\\d+[.,]\\d+|\\d+)(%| кВтч?/ч| кВтч?/100км| кВтч?| км/ч| км| мВ|°C)?""")'

# These are single captured external responses. The patterns remove only the
# response fields identified by their consumers, then scan the remaining bytes.
BETA_MAP_CAPTURE_FILE = "app/src/test/resources/beta/map-rsc-national-library.txt"
BETA_MAP_CAPTURED_VALUE = re.compile(r'''"(?:name|address)":"(?:[^"\\]|\\.)*"''')
NOMINATIM_CAPTURE_FILE = "app/src/test/resources/nominatim/reverse-minsk.json"
NOMINATIM_CAPTURED_FIELD = re.compile(
    r'''\s*"(?:name|display_name|road|city_district|city|country)":\s*"(?:[^"\\]|\\.)*",?\s*'''
)


# Frozen complete contexts for previously reviewed embedded regexes, external
# captures, and saved-byte test oracles. These are path-specific literal values.
EXACT_LITERAL_CONTEXTS = {
    'app/src/main/kotlin/com/bydmate/app/agent/AgentTools.kt': frozenset({
        '^\\s*(\\d{1,3}(?:[ \xa0]\\d{3})+|\\d+)(?:[.,](\\d+))?\\s*(\u043a\u043c|\u043c)\\.?\\s*$',
    }),
    'app/src/main/kotlin/com/bydmate/app/media/NaviRichNotificationParser.kt': frozenset({
        '(?:(\\d+)\\s*\u0447)?\\s*(?:(\\d+)\\s*\u043c\u0438\u043d)?',
        '(\\d+(?:[.,]\\d+)?)\\s*(\u043a\u043c|km)(?!\\S)',
        '(\\d+)\\s*(\u043c|m)(?!\\S)',
    }),
    'app/src/main/kotlin/com/bydmate/app/media/NaviRichPostProcessor.kt': frozenset({
        '(\\d+(?:[.,]\\d+)?)\\s*(\u043a\u043c|km)(?!\\S)',
        '(\\d+)\\s*(\u043c|m)(?!\\S)',
        '(\\d+)\\s*\u043c\u0438\u043d',
        '(\\d+)\\s*\u0447',
    }),
    'app/src/main/kotlin/com/bydmate/app/navdata/NavGuidance.kt': frozenset({
        '(\\d+(?:[.,]\\d+)?)\\s*(\u043a\u043c|km)(?!\\S)',
        '(\\d+)\\s*(\u043c|m)(?!\\S)',
        '(\\d+)\\s*\u043c\u0438\u043d',
        '(\\d+)\\s*\u0447\\s*(\\d+)\\s*\u043c\u0438\u043d',
    }),
    'app/src/test/kotlin/com/bydmate/app/agent/AgentToolsChargersTest.kt': frozenset({
        '[{"id":"1","name":"\u041c\u0438\u043d\u0441\u043a, \u0443\u043b. \u041f\u0443\u043b\u0438\u0445\u043e\u0432\u0430, 5","address":"\u041c\u0438\u043d\u0441\u043a","latitude":53.89911,"longitude":27.575947,"status":"AVAILABLE"}]',
    }),
    'app/src/test/kotlin/com/bydmate/app/agent/AgentToolsLaunchTest.kt': frozenset({
        '\u041d\u0430\u0432\u0438\u0433\u0430\u0442\u043e\u0440 jest ju\u017c na zestawie wska\u017anik\xf3w',
    }),
    'app/src/test/kotlin/com/bydmate/app/agent/AgentToolsNavigateRangeTest.kt': frozenset({
        '{"destination":"\u0442\u043e\u0447\u043a\u0430","lat":57.0,"lon":36.0}',
        '{"query":"\u043a\u043e\u0444\u0435\u0439\u043d\u044f \u0440\u044f\u0434\u043e\u043c"}',
    }),
    'app/src/test/kotlin/com/bydmate/app/agent/AgentToolsNavigateVerifyTest.kt': frozenset({
        '{"destination":"\u0434\u043e\u043c\u043e\u0439"}',
    }),
    'app/src/test/kotlin/com/bydmate/app/agent/AgentToolsPlacesTest.kt': frozenset({
        '{"destination":"\u041e\u0440\u0448\u0430"}',
    }),
    'app/src/test/kotlin/com/bydmate/app/agent/AgentToolsRangeToDestTest.kt': frozenset({
        '{"destination":"\u041e\u0440\u0448\u0430"}',
    }),
    'app/src/test/kotlin/com/bydmate/app/agent/AgentTraceTest.kt': frozenset({
        'W pobli\u017cu jest \u0421\u0435\u0432\u0435\u0440\u043d\u0430\u044f',
        'W pobli\u017cu jest \u0422\u0426 Dana Mall',
        '{"chargers":[{"name":"\u0422\u0426 Dana Mall","address":"\u041c\u0438\u043d\u0441\u043a, \u0443\u043b. \u041c\u0441\u0442\u0438\u0441\u043b\u0430\u0432\u0446\u0430, 11","distance_km":0.5}]}',
        '{"settlements":[{"name":"\u0421\u0435\u0432\u0435\u0440\u043d\u0430\u044f","distance_km":1.1}]}',
    }),
    'app/src/test/kotlin/com/bydmate/app/agent/BetaMapClientTest.kt': frozenset({
        '0:{"stations":[{"name":"\u0411\u0435\u0437 \u043a\u043e\u043e\u0440\u0434\u0438\u043d\u0430\u0442"},',
        '{"name":"\u0415\u0441\u0442\u044c","latitude":53.9,"longitude":27.5,"status":"available","connectors":[]}]}',
    }),
    'app/src/test/kotlin/com/bydmate/app/agent/ChargerSearchClientTest.kt': frozenset({
        '"addr:city":"\u041e\u0440\u0448\u0430","addr:street":"\u0443\u043b\u0438\u0446\u0430 \u041c\u0438\u0440\u0430","addr:housenumber":"4"}}]}',
        '[{"id":"NDMw","name":"\u041c\u0438\u043d\u0441\u043a, \u0443\u043b. \u041f\u0443\u043b\u0438\u0445\u043e\u0432\u0430, 5","address":"\u041c\u0438\u043d\u0441\u043a","latitude":53.89911,"longitude":27.575947,"status":"FULLY_USED"}]',
        '{"elements":[{"type":"node","lat":54.5,"lon":30.4,"tags":{"name":"Stacja","operator":"\u041c\u0430\u043b\u0430\u0445\u0438\u0442",',
        '{"elements":[{"type":"node","lat":54.5,"lon":30.4,"tags":{"operator":"\u0420\u043e\u0441\u0441\u0435\u0442\u0438"}}]}',
    }),
    'app/src/test/kotlin/com/bydmate/app/data/automation/ActionDispatcherToggleTest.kt': frozenset({
        '[{"command":"","displayName":"\u041f\u0435\u0440\u0435\u043a\u043b\u044e\u0447\u0438\u0442\u044c: \u0411\u0430\u0433\u0430\u0436\u043d\u0438\u043a","kind":"toggle","payload":"trunk"}]',
        '[{"command":"","displayName":"\u041f\u0435\u0440\u0435\u043a\u043b\u044e\u0447\u0438\u0442\u044c: \u041b\u044e\u043a","kind":"toggle","payload":"sunroof"}]',
    }),
    'app/src/test/kotlin/com/bydmate/app/data/automation/PowerStateRuleMigrationTest.kt': frozenset({
        ' \u0428\u0442\u043e\u0440\u043a\u0430 \u043f\u0440\u0438 \u0434\u0432\u0438\u0436\u0435\u043d\u0438\u0438 ',
    }),
    'app/src/test/kotlin/com/bydmate/app/data/telegram/TelegramReporterTest.kt': frozenset({
        '<b>BYDMate: A</b>\\n\u0417\u0430\u0440\u044f\u0434 64%',
        '[{"id":"old1","chat":$chat,"created":$now,"text":"<b>BYDMate: A</b>\\n\u0417\u0430\u0440\u044f\u0434 64%","late":true}]',
    }),
    'app/src/test/kotlin/com/bydmate/app/helper/DisplayDumpFixtures.kt': frozenset({
        '\u0412\u0441\u0442\u0440\u043e\u0435\u043d\u043d\u044b\u0439 \u044d\u043a\u0440\u0430\u043d, displayId 0',
    }),
    'app/src/test/kotlin/com/bydmate/app/helper/offreport/OffReportTest.kt': frozenset({
        '<b>BYDMate: \u043c\u0430\u0448\u0438\u043d\u0430 \u0432\u044b\u043a\u043b\u044e\u0447\u0435\u043d\u0430 \u0432 18:54</b>\\n\u0417\u0430\u0440\u044f\u0434 64%\\n<a href=\\"https://x/?a=1&amp;b=2\\">\u041e\u0442\u043a\u0440\u044b\u0442\u044c \u043d\u0430 \u043a\u0430\u0440\u0442\u0435</a>',
    }),
    'app/src/test/kotlin/com/bydmate/app/hud/HudManeuverJournalTest.kt': frozenset({
        'desc:\u041f\u043e\u0432\u0435\u0440\u043d\u0438\u0442\u0435 \u043d\u0430\u043b\u0435\u0432\u043e',
        'desc:\u041f\u043e\u0432\u0435\u0440\u043d\u0438\u0442\u0435 \u043d\u0430\u043f\u0440\u0430\u0432\u043e',
        'gaode=13 dist=400m f28=0 amap=11 suppress=false src=a11y raw=\\"desc:\u041f\u043e\u0432\u0435\u0440\u043d\u0438\u0442\u0435 \u043d\u0430\u043b\u0435\u0432\u043e\\"',
        'gaode=2 dist=250m f28=2 amap=off suppress=false src=a11y raw=\\"desc:\u041f\u043e\u0432\u0435\u0440\u043d\u0438\u0442\u0435 \u043d\u0430\u043f\u0440\u0430\u0432\u043e\\"',
    }),
    'app/src/test/kotlin/com/bydmate/app/hud/HudProtobufBuilderTest.kt': frozenset({
        '\u0443\u043b. \u041b\u0435\u043d\u0438\u043d\u0430',
    }),
    'app/src/test/kotlin/com/bydmate/app/hud/HudPushLoopTest.kt': frozenset({
        '\u041c\u0438\u043d\u0441\u043a\u043e\u0435 \u0448\u043e\u0441\u0441\u0435 | 01:05 min | ${loop.etaString(3900)}',
        '\u043f\u0440. \u041f\u043e\u0431\u0435\u0434\u0438\u0442\u0435\u043b\u0435\u0439',
        '\u0443\u043b. \u041b\u0435\u043d\u0438\u043d\u0430',
    }),
    'app/src/test/kotlin/com/bydmate/app/hud/HudRoadScriptTest.kt': frozenset({
        '\u041c-1',
        '\u0443\u043b. \u041b\u0435\u043d\u0438\u043d\u0430 | 00:12 min',
        '\u0443\u043b. \u041b\u0435\u043d\u0438\u043d\u0430, 12',
    }),
    'app/src/test/kotlin/com/bydmate/app/media/ClusterMusicCardTest.kt': frozenset({
        ' \u041a\u0438\u043d\u043e ',
        'A\u042f',
        '\u0426\u043e\u0439',
    }),
    'app/src/test/kotlin/com/bydmate/app/media/NaviNotificationParserTest.kt': frozenset({
        '350 \u043c',
        '42 \u043a\u043c',
    }),
    'app/src/test/kotlin/com/bydmate/app/media/NaviRichNotificationParserTest.kt': frozenset({
        '1 \u0447 40 \u043c\u0438\u043d',
        '1,5 \u043a\u043c',
        '12 \u043a\u043c',
        '230 \u043c',
        '230 \u043c \xb7 \u0422\u0432\u0435\u0440\u0441\u043a\u0430\u044f',
        '27 \u043c\u0438\u043d',
        '30 \u043c\u0438\u043d',
        '45 \u043c\u0438\u043d',
        '5 \u043a\u043c',
        '500 \u043c',
        '500 \u043c \xb7 \u0422\u0432\u0435\u0440\u0441\u043a\u0430\u044f',
        '\u041f\u0440\u0438\u0431\u044b\u0442\u0438\u0435 \u0432 08:30',
        '\u041f\u0440\u0438\u0431\u044b\u0442\u0438\u0435 \u0432 12:45',
        '\u0427\u0435\u0440\u0435\u0437 300 \u043c \u043f\u043e\u0432\u0435\u0440\u043d\u0438\u0442\u0435 \u043d\u0430\u043f\u0440\u0430\u0432\u043e',
    }),
    'app/src/test/kotlin/com/bydmate/app/media/NaviRichPostProcessorTest.kt': frozenset({
        '0 \u043c',
        '1 \u0447 5 \u043c\u0438\u043d',
        '230 \u043c',
        '27 \u043c\u0438\u043d',
        '300 \u043c',
        '45 \u043c\u0438\u043d',
        '500 \u043c',
        'text:key=\u043f\u043e\u0432\u0435\u0440\u043d\u0438\u0442\u0435 \u043d\u0430\u043f\u0440\u0430\u0432\u043e',
        '\u0414\u043b\u044f \u0440\u0430\u0431\u043e\u0442\u044b \u043f\u0440\u0438\u043b\u043e\u0436\u0435\u043d\u0438\u044f \\"\u042f\u043d\u0434\u0435\u043a\u0441 \u041d\u0430\u0432\u0438\u0433\u0430\u0442\u043e\u0440\\" \u0442\u0440\u0435\u0431\u0443\u0435\u0442\u0441\u044f',
        '\u041d\u0430\u0432\u0438\u0433\u0430\u0442\u043e\u0440 \u0437\u0430\u043f\u0443\u0449\u0435\u043d',
        '\u041f\u043e\u0432\u0435\u0440\u043d\u0438\u0442\u0435 \u043d\u0430\u043f\u0440\u0430\u0432\u043e',
        '\u0422\u0432\u0435\u0440\u0441\u043a\u0430\u044f',
        '\u0423\u0441\u0442\u0430\u043d\u043e\u0432\u0438\u0442\u0435 \u0441\u0435\u0440\u0432\u0438\u0441\u044b Google Play',
    }),
    'app/src/test/kotlin/com/bydmate/app/media/NaviRouteHolderPackagesTest.kt': frozenset({
        '5 \u043a\u043c',
        '\u043d\u0430\u043f\u0440\u0430\u0432\u043e',
    }),
    'app/src/test/kotlin/com/bydmate/app/media/NaviRouteHolderTest.kt': frozenset({
        '350 \u043c',
        '42 \u043a\u043c',
        '5 \u043a\u043c',
        '\u0443\u043b\u0438\u0446\u0430 \u041b\u0435\u043d\u0438\u043d\u0430',
    }),
    'app/src/test/kotlin/com/bydmate/app/media/NaviScreenReaderTest.kt': frozenset({
        ' \u043c',
        '250 \u043c',
        '27 \u043c\u0438\u043d',
        '28 \u043a\u043c',
        '\u0443\u043b. \u041a\u0430\u0447\u0430\u043d\u044b',
    }),
    'app/src/test/kotlin/com/bydmate/app/navdata/NavA11yExtractorTest.kt': frozenset({
        '\\"\u041f\u043e\u0432\u0435\u0440\u043d\u0438\u0442\u0435 \u043d\u0430\u043f\u0440\u0430\u0432\u043e\\" len=17',
        '\\"\u043d\u0430\u043b\u0435\u0432\u043e\\" len=6',
        '{id=image_maneuverballoon_maneuver cls=ImageView desc=* len=14 state=\\"\u043d\u0430\u043b\u0435\u0432\u043e\\" len=6 ',
        '\u041f\u043e\u0432\u0435\u0440\u043d\u0438\u0442\u0435 \u043d\u0430\u043f\u0440\u0430\u0432\u043e',
        '\u043d\u0430\u043b\u0435\u0432\u043e',
    }),
    'app/src/test/kotlin/com/bydmate/app/navdata/NavA11yFeedUnknownManeuverTest.kt': frozenset({
        ' [1]{id=? cls=ImageView desc=\\"\u043d\u0430\u043b\u0435\u0432\u043e\\" len=6 order=0 sel=true chk=false extras=[]}',
    }),
    'app/src/test/kotlin/com/bydmate/app/navdata/NavGuidanceHubStaleArrowTest.kt': frozenset({
        'desc:key=\u043f\u043e\u0432\u0435\u0440\u043d\u0438\u0442\u0435 \u043d\u0430\u043f\u0440\u0430\u0432\u043e',
    }),
    'app/src/test/kotlin/com/bydmate/app/navdata/NavGuidanceHubTest.kt': frozenset({
        '2-\u0439 \u0441\u044a\u0435\u0437\u0434 \u043d\u0430 \u041b\u0435\u043d\u0438\u043d\u0430',
        'desc:key=2-\u0439 \u0441\u044a\u0435\u0437\u0434',
        'desc:key=\u043f\u043e\u0432\u0435\u0440\u043d\u0438\u0442\u0435 \u043d\u0430\u043f\u0440\u0430\u0432\u043e',
        'desc:\u041f\u043e\u0432\u0435\u0440\u043d\u0438\u0442\u0435 \u043d\u0430\u043f\u0440\u0430\u0432\u043e',
        'text:\u0441\u044a\u0435\u0437\u0436\u0430\u0439\u0442\u0435 \u043d\u0430 * len=21',
        '\u0443\u043b. \u0410',
        '\u0443\u043b. \u0410 ',
        '\u0443\u043b. \u0411',
        '\u0443\u043b. \u0412',
        '\u0443\u043b. \u041b\u0435\u043d\u0438\u043d\u0430',
    }),
    'app/src/test/kotlin/com/bydmate/app/navdata/NavGuidanceIssue199Test.kt': frozenset({
        '12 \u043c\u0438\u043d',
        '300 \u043c',
    }),
    'app/src/test/kotlin/com/bydmate/app/navdata/NavGuidanceParserTest.kt': frozenset({
        '1 \u0447 5 \u043c\u0438\u043d',
        '1,2 \u043a\u043c',
        '2 \u0422\u0432\u0435\u0440\u0441\u043a\u0430\u044f',
        '27 \u043c\u0438\u043d',
        '28 \u043a\u043c',
        '300 \u043c',
        '800 \u043c',
        'desc:key=\u043f\u043e\u0432\u0435\u0440\u043d\u0438\u0442\u0435 \u043d\u0430\u043f\u0440\u0430\u0432\u043e',
        '\u041f\u043e\u0432\u0435\u0440\u043d\u0438\u0442\u0435 \u043d\u0430\u043f\u0440\u0430\u0432\u043e',
        '\u0443\u043b. \u041a\u0430\u0447\u0430\u043d\u044b',
        '\u0443\u043b. \u041b\u0435\u043d\u0438\u043d\u0430',
    }),
    'app/src/test/kotlin/com/bydmate/app/navdata/NavManeuverCodesTest.kt': frozenset({
        '1-\u0439 \u0441\u044a\u0435\u0437\u0434',
        '10-\u0439 \u0441\u044a\u0435\u0437\u0434',
        '11-\u0439 \u0441\u044a\u0435\u0437\u0434',
        '2-\u0439 \u0441\u044a\u0435\u0437\u0434',
        '\u041f\u0440\u043e\u043c\u0435\u0436\u0443\u0442\u043e\u0447\u043d\u0430\u044f\\u00A0\u0442\u043e\u0447\u043a\u0430',
        '\u0420\u0435\u0437\u043a\u0438\u0439\\u00A0\u043f\u043e\u0432\u043e\u0440\u043e\u0442\\u00A0\u043d\u0430\u043b\u0435\u0432\u043e',
        '\u0421\u044a\u0435\u0437\u0434\\u00A0\u0441\\u00A0\u043a\u043e\u043b\u044c\u0446\u0430',
        '\u043f\u043b\u0430\u0442\u043d\u044b\u0439\\u00A0\u0443\u0447\u0430\u0441\u0442\u043e\u043a',
    }),
    'app/src/test/kotlin/com/bydmate/app/navdata/NavManeuverCompetitorsTest.kt': frozenset({
        '2-\u0439 \u043f\u043e\u0432\u043e\u0440\u043e\u0442',
        '\u041a\u043e\u043b\u044c\u0446\u0435\u0432\u043e\u0435 \u0434\u0432\u0438\u0436\u0435\u043d\u0438\u0435, \u0432\u0442\u043e\u0440\u043e\u0439 \u0441\u044a\u0435\u0437\u0434',
        '\u0421\u044a\u0435\u0437\u0434 2',
        '\u0427\u0435\u0440\u0435\u0437 3 \u043a\u043c \u0441\u044a\u0435\u0437\u0434',
        '\u0427\u0435\u0440\u0435\u0437 5 \u043a\u043c \u0432\u0442\u043e\u0440\u043e\u0439 \u0441\u044a\u0435\u0437\u0434',
        '\u0427\u0435\u0440\u0435\u0437 5 \u043a\u043c \u0434\u0435\u0440\u0436\u0438\u0442\u0435\u0441\u044c \u0441\u0435\u0440\u0435\u0434\u0438\u043d\u044b',
        '\u0447\u0435\u0440\u0435\u0437 2 \u043a\u043c \u043f\u044f\u0442\u044b\u0439 \u0441\u044a\u0435\u0437\u0434',
    }),
    'app/src/test/kotlin/com/bydmate/app/util/AppStringsTest.kt': frozenset({
        'bydmate_backup.zip, 1.2 \u041c\u0411',
    }),
    'docs/plans/2026-10-21-multi-navigator-hud-feasibility.md': frozenset({
        '\u043a\u043c|km',
        '\u043c|m',
    }),
    'tools/check-polish-only.py': frozenset({
        '            "praca", "do pracy", "moja praca" -> HomeWork("Praca", "work", legacyPlaceName = "\u0420\u0430\u0431\u043e\u0442\u0430")',
        '            PlaceEntity(name = "\u0414\u043e\u043c", lat = 52.2297, lon = 21.0122, radiusM = 100),',
        '        assertEquals("\u0414\u043e\u043c", out.getString("place"))',
        '    val numberPattern = Regex("""(\\\\d+[.,]\\\\d+|\\\\d+)(%| \u043a\u0412\u0442\u0447?/\u0447| \u043a\u0412\u0442\u0447?/100\u043a\u043c| \u043a\u0412\u0442\u0447?| \u043a\u043c/\u0447| \u043a\u043c| \u043c\u0412|\xb0C)?""")',
        '\\s*"(?:\u0434\u043e\u043c|\u0434\u043e\u043c\u043e\u0439|\u0434\u043e \u0434\u043e\u043c\u0430|\u043c\u043e\u0439 \u0434\u043e\u043c|\u0440\u0430\u0431\u043e\u0442\u0430|\u0440\u0430\u0431\u043e\u0442\u0443|\u043d\u0430 \u0440\u0430\u0431\u043e\u0442\u0443|\u043c\u043e\u044f \u0440\u0430\u0431\u043e\u0442\u0430)"(?:,\\s*"(?:\u0434\u043e\u043c|\u0434\u043e\u043c\u043e\u0439|\u0434\u043e \u0434\u043e\u043c\u0430|\u043c\u043e\u0439 \u0434\u043e\u043c|\u0440\u0430\u0431\u043e\u0442\u0430|\u0440\u0430\u0431\u043e\u0442\u0443|\u043d\u0430 \u0440\u0430\u0431\u043e\u0442\u0443|\u043c\u043e\u044f \u0440\u0430\u0431\u043e\u0442\u0430|home|work)")*\\s*->\\s*HomeWork\\("(?:\u0414\u043e\u043c|\u0420\u0430\u0431\u043e\u0442\u0430)",\\s*"(?:home|work)"\\)',
        '\\s*(?:\\* .*`\u0451`\u2192`\u0435` fold.*|\\.replace\\("\u0451", "\u0435"\\))\\s*',
    }),
    'tools/test_check_polish_only.py': frozenset({
        '<ID>Rule$throw Exception("\u041d\u0430\u0441\u0442\u0440\u043e\u0439\u043a\u0438")</ID>\\n<note>\u041d\u0430\u0441\u0442\u0440\u043e\u0439\u043a\u0438</note>\\n',
        '<string name="ok">\u0414\u043e\u0431\u0440\u0430</string>\\nval bad = "\u041d\u0430\u0441\u0442\u0440\u043e\u0439\u043a\u0438"\\n',
        'Exact input: "\u041d\u0430\u0432\u0438\u0433\u0430\u0442\u043e\u0440 \u0437\u0430\u043f\u0443\u0449\u0435\u043d"\\nAuthored text: "\u041d\u0430\u0441\u0442\u0440\u043e\u0439\u043a\u0438"\\n',
        'HomeWork("\u0414\u043e\u043c", "home")\\n',
        'val legacyPlaceName = "\u0414\u043e\u043c"\\n',
        'val title = "\u041d\u0430\u0441\u0442\u0440\u043e\u0439\u043a\u0438"\\n',
        'val title = "\u041f\u043e\u0432\u0435\u0440\u043d\u0438\u0442\u0435 \u043d\u0430\u043f\u0440\u0430\u0432\u043e \u041d\u0430\u0441\u0442\u0440\u043e\u0439\u043a\u0438"\\n',
        'val title = "\u041f\u043e\u0432\u0435\u0440\u043d\u0438\u0442\u0435 \u043d\u0430\u043f\u0440\u0430\u0432\u043e"\\n',
        'val yandexRight = "\u041f\u043e\u0432\u0435\u0440\u043d\u0438\u0442\u0435 \u043d\u0430\u043f\u0440\u0430\u0432\u043e"\\n',
    }),
}
EXACT_RAW_ORACLE_LINES = {
    'app/src/main/res/values-en/strings.xml': frozenset({
        '    <string name="donate_auto_body">The project is non-commercial and completely free, built purely out of enthusiasm. If BYDMate has been useful to you and you would like to say thanks, you can support development with a USDT transfer on the EVM networks below or with a card via \u042eMoney.</string>',
        '    <string name="donate_card_body">Payment by card or a \u042eMoney wallet. Open the link on your phone and enter any amount.</string>',
        '    <string name="donate_card_label">Card \xb7 \u042eMoney</string>',
        '    <string name="donate_settings_body">BYDMate is non-commercial and free. If you would like to support development, send USDT to the address below or pay by card via \u042eMoney.</string>',
    }),
    'app/src/main/res/values-pl/strings.xml': frozenset({
        '    <string name="donate_auto_body">Projekt jest niekomercyjny i ca\u0142kowicie darmowy, rozwija si\u0119 na czystym entuzjazmie. Je\u015bli BYDMate okaza\u0142 si\u0119 przydatny i chcesz powiedzie\u0107 dzi\u0119kuj\u0119, wesprzyj rozw\xf3j: przelewem USDT w sieciach EVM lub kart\u0105 przez \u042eMoney.</string>',
        '    <string name="donate_card_body">P\u0142atno\u015b\u0107 kart\u0105 lub portfelem \u042eMoney. Otw\xf3rz link na telefonie i podaj dowoln\u0105 kwot\u0119.</string>',
        '    <string name="donate_card_label">Kart\u0105 \xb7 \u042eMoney</string>',
        '    <string name="donate_settings_body">BYDMate jest niekomercyjny i darmowy. Je\u015bli chcesz wesprze\u0107 rozw\xf3j, wy\u015blij USDT na adres poni\u017cej lub zap\u0142a\u0107 kart\u0105 przez \u042eMoney.</string>',
        '    <string name="settings_car_language_desc">Tylko dla oprogramowania UI7. Przycisk otwiera ukryte fabryczne okno BYD z pe\u0142n\u0105 list\u0105 j\u0119zyk\xf3w. Aby w\u0142\u0105czy\u0107 rosyjski: wybierz \u201e\u0420\u0443\u0441\u0441\u043a\u0438\u0439 \u044f\u0437\u044b\u043a\u201d, potem OK. Aby wr\xf3ci\u0107: ten sam przycisk, \u201eEnglish\u201d, OK. Uwaga: przy rosyjskim u cz\u0119\u015bci w\u0142a\u015bcicieli przestaje dzia\u0142a\u0107 automatyczne parkowanie. Je\u015bli korzystasz z automatycznego parkowania, zostaw English.</string>',
    }),
    'app/src/main/res/values-pt/strings.xml': frozenset({
        '    <string name="donate_auto_body">O projeto \xe9 sem fins lucrativos e totalmente gratuito, desenvolvido por puro entusiasmo. Se o BYDMate foi \xfatil para voc\xea e quiser agradecer, apoie o desenvolvimento: com uma transfer\xeancia de USDT nas redes EVM ou com cart\xe3o via \u042eMoney.</string>',
        '    <string name="donate_card_body">Pagamento com cart\xe3o ou carteira \u042eMoney. Abra o link no celular e informe qualquer valor.</string>',
        '    <string name="donate_card_label">Cart\xe3o \xb7 \u042eMoney</string>',
        '    <string name="donate_settings_body">O BYDMate \xe9 sem fins lucrativos e gratuito. Se quiser apoiar o desenvolvimento, envie USDT para o endere\xe7o abaixo ou pague com cart\xe3o via \u042eMoney.</string>',
    }),
    'app/src/main/res/values-zh/strings.xml': frozenset({
        '    <string name="donate_auto_body">\u8be5\u9879\u76ee\u975e\u5546\u4e1a\u5316\u4e14\u5b8c\u5168\u514d\u8d39\uff0c\u5b8c\u5168\u51fa\u4e8e\u4e2a\u4eba\u70ed\u60c5\u5f00\u53d1\u7ef4\u62a4\u3002\u5982\u679c BYDMate \u5bf9\u60a8\u6709\u5e2e\u52a9\uff0c\u60f3\u8868\u793a\u611f\u8c22\uff0c\u53ef\u4ee5\u901a\u8fc7 EVM \u7f51\u7edc\u8f6c\u8d26 USDT\uff0c\u6216\u901a\u8fc7 \u042eMoney \u7528\u94f6\u884c\u5361\u652f\u6301\u5f00\u53d1\u3002</string>',
        '    <string name="donate_card_body">\u652f\u6301\u94f6\u884c\u5361\u6216 \u042eMoney \u94b1\u5305\u4ed8\u6b3e\u3002\u8bf7\u5728\u624b\u673a\u4e0a\u6253\u5f00\u94fe\u63a5\u5e76\u8f93\u5165\u4efb\u610f\u91d1\u989d\u3002</string>',
        '    <string name="donate_card_label">\u94f6\u884c\u5361 \xb7 \u042eMoney</string>',
        '    <string name="donate_settings_body">BYDMate \u975e\u5546\u4e1a\u5316\u4e14\u514d\u8d39\u3002\u5982\u679c\u60f3\u652f\u6301\u5f00\u53d1\uff0c\u8bf7\u5c06 USDT \u8f6c\u5230\u4e0b\u65b9\u5730\u5740\uff0c\u6216\u901a\u8fc7 \u042eMoney \u7528\u94f6\u884c\u5361\u652f\u4ed8\u3002</string>',
        '    <string name="settings_car_language_desc">\u4ec5\u9002\u7528\u4e8e UI7 \u56fa\u4ef6\u3002\u6309\u94ae\u4f1a\u6253\u5f00\u6bd4\u4e9a\u8fea\u9690\u85cf\u7684\u539f\u5382\u8bed\u8a00\u5bf9\u8bdd\u6846\uff0c\u5176\u4e2d\u6709\u5b8c\u6574\u8bed\u8a00\u5217\u8868\u3002\u5207\u6362\u5230\u4fc4\u8bed\uff1a\u9009\u62e9\u201c\u0420\u0443\u0441\u0441\u043a\u0438\u0439 \u044f\u0437\u044b\u043a\u201d\uff0c\u7136\u540e\u70b9 OK\u3002\u5207\u56de\u6765\uff1a\u540c\u4e00\u4e2a\u6309\u94ae\uff0c\u9009\u62e9\u201cEnglish\u201d\uff0c\u70b9 OK\u3002\u6ce8\u610f\uff1a\u90e8\u5206\u8f66\u4e3b\u5728\u4fc4\u8bed\u4e0b\u81ea\u52a8\u6cca\u8f66\u4f1a\u5931\u6548\u3002\u5982\u679c\u60a8\u4f7f\u7528\u81ea\u52a8\u6cca\u8f66\uff0c\u8bf7\u4fdd\u7559 English\u3002</string>',
    }),
    'app/src/main/res/values/strings.xml': frozenset({
        '    <string name="donate_auto_body">Projekt jest niekomercyjny i ca\u0142kowicie darmowy, rozwija si\u0119 na czystym entuzjazmie. Je\u015bli BYDMate okaza\u0142 si\u0119 przydatny i chcesz powiedzie\u0107 dzi\u0119kuj\u0119, wesprzyj rozw\xf3j: przelewem USDT w sieciach EVM lub kart\u0105 przez \u042eMoney.</string>',
        '    <string name="donate_card_body">P\u0142atno\u015b\u0107 kart\u0105 lub portfelem \u042eMoney. Otw\xf3rz link na telefonie i podaj dowoln\u0105 kwot\u0119.</string>',
        '    <string name="donate_card_label">Kart\u0105 \xb7 \u042eMoney</string>',
        '    <string name="donate_settings_body">BYDMate jest niekomercyjny i darmowy. Je\u015bli chcesz wesprze\u0107 rozw\xf3j, wy\u015blij USDT na adres poni\u017cej lub zap\u0142a\u0107 kart\u0105 przez \u042eMoney.</string>',
        '    <string name="settings_car_language_desc">Tylko dla oprogramowania UI7. Przycisk otwiera ukryte fabryczne okno BYD z pe\u0142n\u0105 list\u0105 j\u0119zyk\xf3w. Aby w\u0142\u0105czy\u0107 rosyjski: wybierz \u201e\u0420\u0443\u0441\u0441\u043a\u0438\u0439 \u044f\u0437\u044b\u043a\u201d, potem OK. Aby wr\xf3ci\u0107: ten sam przycisk, \u201eEnglish\u201d, OK. Uwaga: przy rosyjskim u cz\u0119\u015bci w\u0142a\u015bcicieli przestaje dzia\u0142a\u0107 automatyczne parkowanie. Je\u015bli korzystasz z automatycznego parkowania, zostaw English.</string>',
    }),
    'app/src/test/resources/native-stack-fixtures/window-songl-held-target-20260929.txt': frozenset({
        '09-29 17:56:26.691 I/VoiceController(23024): heard="\u043f\u0440\u043e\u0432\u0435\u0442\u0440\u0438\u0442\u044c" route=automation cmd=\u041f\u0440\u043e\u0432\u0435\u0442\u0440\u0438\u0442\u044c reason=-',
        '09-29 17:56:28.440 I/VehicleApiImpl(23024): window readback action=window_driver_pos dev=1001 fid=1276219408 value=11 before=0 after=0,0 verdict=\u043d\u0435 \u0441\u0434\u0432\u0438\u043d\u0443\u043b\u043e\u0441\u044c',
        "09-29 17:56:28.445 W/ActionDispatcher(23024): param '\u4e3b\u9a7e\u901a\u98ce' failed: window_driver_pos: \u043e\u043a\u043d\u043e \u0432\u043e\u0434\u0438\u0442\u0435\u043b\u044f \u043d\u0435 \u0441\u0434\u0432\u0438\u043d\u0443\u043b\u043e\u0441\u044c \u0441 \u043c\u0435\u0441\u0442\u0430, \u043a\u043e\u043c\u0430\u043d\u0434\u0430 \u043d\u0435 \u0441\u0440\u0430\u0431\u043e\u0442\u0430\u043b\u0430",
        '09-29 17:56:38.567 I/VoiceController(23024): heard="\u043e\u0442\u043a\u0440\u044b\u0442\u044c" route=automation cmd=\u041e\u0442\u043a\u0440\u044b\u0442\u044c reason=-',
        '09-29 17:57:13.412 I/VoiceController(23024): heard="\u043f\u043e\u043b\u043e\u0432\u0438\u043d\u0430" route=automation cmd=\u041f\u043e\u043b\u043e\u0432\u0438\u043d\u0430 reason=-',
        '09-29 17:57:19.553 I/VoiceController(23024): heard="\u043f\u0440\u043e\u0432\u0435\u0442\u0440\u0438\u0442\u044c" route=automation cmd=\u041f\u0440\u043e\u0432\u0435\u0442\u0440\u0438\u0442\u044c reason=-',
        '09-29 17:57:24.251 I/VoiceController(23024): heard="\u043f\u043e\u043b\u043e\u0432\u0438\u043d\u0430" route=automation cmd=\u041f\u043e\u043b\u043e\u0432\u0438\u043d\u0430 reason=-',
        '09-29 17:57:39.336 I/VoiceController(23024): heard="\u0437\u0430\u043a\u0440\u044b\u0442\u044c" route=automation cmd=\u0417\u0430\u043a\u0440\u044b\u0442\u044c reason=-',
        '09-29 17:57:44.160 I/VoiceController(23024): heard="\u043f\u043e\u043b\u043e\u0432\u0438\u043d\u0430" route=automation cmd=\u041f\u043e\u043b\u043e\u0432\u0438\u043d\u0430 reason=-',
        '09-29 17:57:45.944 I/VehicleApiImpl(23024): window readback action=window_driver_pos dev=1001 fid=1276219408 value=51 before=0 after=0,0 verdict=\u043d\u0435 \u0441\u0434\u0432\u0438\u043d\u0443\u043b\u043e\u0441\u044c',
        "09-29 17:57:45.947 W/ActionDispatcher(23024): param '\u4e3b\u9a7e\u534a\u5f00' failed: window_driver_pos: \u043e\u043a\u043d\u043e \u0432\u043e\u0434\u0438\u0442\u0435\u043b\u044f \u043d\u0435 \u0441\u0434\u0432\u0438\u043d\u0443\u043b\u043e\u0441\u044c \u0441 \u043c\u0435\u0441\u0442\u0430, \u043a\u043e\u043c\u0430\u043d\u0434\u0430 \u043d\u0435 \u0441\u0440\u0430\u0431\u043e\u0442\u0430\u043b\u0430",
        '09-29 17:58:08.474 I/VoiceController(23024): heard="\u043f\u0440\u043e\u0432\u0435\u0442\u0440\u0438\u0442\u044c" route=automation cmd=\u041f\u0440\u043e\u0432\u0435\u0442\u0440\u0438\u0442\u044c reason=-',
        '09-29 17:58:15.993 I/VoiceController(23024): heard="\u0437\u0430\u043a\u0440\u044b\u0442\u044c" route=automation cmd=\u0417\u0430\u043a\u0440\u044b\u0442\u044c reason=-',
        '09-29 17:58:23.718 I/VoiceController(23024): heard="\u043f\u0440\u043e\u0432\u0435\u0442\u0440\u0438\u0442\u044c" route=automation cmd=\u041f\u0440\u043e\u0432\u0435\u0442\u0440\u0438\u0442\u044c reason=-',
        '09-29 17:58:25.463 I/VehicleApiImpl(23024): window readback action=window_driver_pos dev=1001 fid=1276219408 value=11 before=0 after=0,0 verdict=\u043d\u0435 \u0441\u0434\u0432\u0438\u043d\u0443\u043b\u043e\u0441\u044c',
        "09-29 17:58:25.469 W/ActionDispatcher(23024): param '\u4e3b\u9a7e\u901a\u98ce' failed: window_driver_pos: \u043e\u043a\u043d\u043e \u0432\u043e\u0434\u0438\u0442\u0435\u043b\u044f \u043d\u0435 \u0441\u0434\u0432\u0438\u043d\u0443\u043b\u043e\u0441\u044c \u0441 \u043c\u0435\u0441\u0442\u0430, \u043a\u043e\u043c\u0430\u043d\u0434\u0430 \u043d\u0435 \u0441\u0440\u0430\u0431\u043e\u0442\u0430\u043b\u0430",
    }),
}
QUOTED_LITERAL = re.compile(
    r'''(?P<quote>"""|\\"|"|'|`)(?P<value>(?:(?!(?P=quote))\\.|(?!(?P=quote)).)*)(?P=quote)'''
)


@dataclass(frozen=True)
class Finding:
    path: str
    line: int
    text: str


def is_authored(path: Path, root: Path) -> bool:
    relative = path.relative_to(root)
    return path.is_file() and path.suffix.lower() in AUTHORED_SUFFIXES and not any(part in IGNORED_PARTS for part in relative.parts) and relative.as_posix() not in IGNORED_FILES


def allowed(relative: str, line: str) -> bool:
    if line in EXACT_RAW_ORACLE_LINES.get(relative, ()):
        return True
    if relative == MANEUVER_CORPUS_FILE:
        return line in FROZEN_MANEUVER_CORPUS_ROWS
    if relative == "tools/check-polish-only.py" and line.strip().replace("\\u005c", "\\\\") in {
        f"{token!r}," for token in FROZEN_MANEUVER_CORPUS_ROWS
    }:
        # The frozen source literal is itself checked as an exact complete row.
        return True
    if relative == GENERATED_DETEKT_SIGNATURE_FILE:
        # Detekt generates these signature IDs from the source expression.
        return bool(re.fullmatch(r"\s*<ID>.*</ID>\s*", line))
    if relative in BELARUSIAN_RESOURCE_FILES:
        return bool(re.match(r"^\s*<string\s+name=\"[^\"]+\"", line))
    if relative in TRANSLITERATION_TABLE_PATHS:
        # These are character-to-ASCII compatibility tables, not prose.
        return bool(TRANSLITERATION_TABLE_LINE.fullmatch(line))
    if relative == "app/src/main/kotlin/com/bydmate/app/agent/AgentTools.kt" and SAVED_PLACE_ALIAS_LINE.fullmatch(line):
        # Exact historical input aliases and their persisted canonical keys.
        return True
    if relative == "app/src/main/kotlin/com/bydmate/app/voice/SelfEchoFilter.kt" and SELF_ECHO_LEGACY_FOLD.fullmatch(line):
        return True
    if relative == "app/src/main/kotlin/com/bydmate/app/ui/dashboard/DashboardScreen.kt" and line == DASHBOARD_UNITS_LINE:
        return True
    if relative == BETA_MAP_CAPTURE_FILE:
        return not CYRILLIC.search(BETA_MAP_CAPTURED_VALUE.sub("", line))
    if relative == NOMINATIM_CAPTURE_FILE:
        return bool(NOMINATIM_CAPTURED_FIELD.fullmatch(line))
    if line in TEST_ORACLE_LINE_ALLOWANCES.get(relative, ()):
        return True

    tokens = set(EXACT_TOKEN_ALLOWANCES.get(relative, ()))
    tokens.update(EXTERNAL_YANDEX_TOKENS.get(relative, ()))
    tokens.update(TEST_ORACLE_TOKEN_ALLOWANCES.get(relative, ()))
    tokens.update(REPORT_BACKED_ORACLE_TOKENS.get(relative, ()))
    if relative == "app/src/test/resources/native-stack-fixtures/window-songl-held-target-20260929.txt":
        tokens.update(NATIVE_SONGL_CAPTURE_TOKENS)
    tokens.update(token.replace('"', r'\"') for token in TEST_ORACLE_TOKEN_ALLOWANCES.get(relative, ()))
    if relative == "app/src/main/kotlin/com/bydmate/app/agent/AgentTools.kt":
        tokens.update(LAUNCHER_LABEL_CYRILLIC_FOLD_PAIRS)
    if relative == CHECKER_FIXTURE_FILE:
        tokens.update(CHECKER_TEST_ORACLE_TOKENS)
    if relative in {"tools/check-polish-only.py", MIGRATION_EXCEPTION_DOCUMENT}:
        # These two files document the exact allowlist itself; no other Cyrillic
        # is permitted because the remainder is still checked below.
        tokens.update(CONFIGURATION_TOKENS)

    remainder = line
    if relative == "app/src/main/kotlin/com/bydmate/app/agent/AgentTools.kt":
        remainder = remainder.replace(SAVED_PLACE_KEY, "")
    if relative == "app/src/main/kotlin/com/bydmate/app/agent/AgentTools.kt":
        for pair in LAUNCHER_LABEL_CYRILLIC_FOLD_PAIRS:
            remainder = remainder.replace(pair, "")
    # Raw capture fixtures also use unquoted key=value rows. The whole value
    # must match; repeated or concatenated values are not compatibility input.
    if relative in REPORT_BACKED_ORACLE_TOKENS or relative.endswith("window-songl-held-target-20260929.txt"):
        field = re.fullmatch(r"[A-Za-z_]+=(.*)", remainder)
        if field and field.group(1) in tokens:
            return True
    # Match complete literals, never substrings inside a literal. The exact
    # context table preserves the reviewed regex and serialized-byte oracles.
    tokens.update(EXACT_LITERAL_CONTEXTS.get(relative, ()))
    for match in list(QUOTED_LITERAL.finditer(remainder))[::-1]:
        value = match.group("value")
        candidates = {value}
        if relative.endswith(".py"):
            try:
                candidates.add(ast.literal_eval(match.group(0)))
            except (ValueError, SyntaxError):
                pass
        if candidates & tokens:
            remainder = remainder[:match.start()] + remainder[match.end():]
    return not CYRILLIC.search(remainder)



def scan(root: Path) -> list[Finding]:
    findings: list[Finding] = []
    for path in sorted(root.rglob("*")):
        if not is_authored(path, root):
            continue
        relative = path.relative_to(root).as_posix()
        try:
            lines = path.read_text(encoding="utf-8").splitlines()
        except UnicodeDecodeError:
            continue
        for number, line in enumerate(lines, start=1):
            if CYRILLIC.search(line) and not allowed(relative, line):
                findings.append(Finding(relative, number, line))
    return findings


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="report unclassified authored Cyrillic")
    parser.add_argument("root", nargs="?", type=Path, default=Path(__file__).resolve().parents[1])
    root = parser.parse_args(argv).root.resolve()
    findings = scan(root)
    for finding in findings:
        print(f"{finding.path}:{finding.line}: unclassified Cyrillic: {finding.text}")
    if findings:
        print(f"FAIL: {len(findings)} unclassified authored Cyrillic occurrence(s)", file=sys.stderr)
        return 1
    print("PASS: no unclassified authored Cyrillic")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
