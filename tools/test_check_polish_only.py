import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("check-polish-only.py")
SPEC = importlib.util.spec_from_file_location("check_polish_only", SCRIPT)
CHECKER = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = CHECKER
SPEC.loader.exec_module(CHECKER)


class CheckPolishOnlyTest(unittest.TestCase):
    def write(self, root: Path, relative: str, text: str) -> None:
        path = root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")

    def test_approved_tokens_cannot_be_repeated_or_concatenated(self) -> None:
        cases = (
            ("app/src/main/kotlin/com/bydmate/app/media/NaviGoButton.kt", "\u041f\u043e\u0435\u0445\u0430\u043b\u0438", None),
            ("app/src/main/kotlin/com/bydmate/app/navdata/NavManeuverCodes.kt", "\u043d\u0430\u043f\u0440\u0430\u0432\u043e", "\u043d\u0430\u043b\u0435\u0432\u043e"),
            ("app/src/test/kotlin/com/bydmate/app/data/automation/RuleShareTest.kt", "\u0414\u043e\u043c: \u0437\u0432\u043e\u043d\u043e\u043a", None),
            ("app/src/test/kotlin/com/bydmate/app/hud/HudPushLoopTest.kt", "\u041c\u0438\u043d\u0441\u043a\u043e\u0435 \u0448\u043e\u0441\u0441\u0435", None),
            ("app/src/test/resources/native-stack-fixtures/window-songl-held-target-20260929.txt", "\u043f\u0440\u043e\u0432\u0435\u0442\u0440\u0438\u0442\u044c", None),
        )
        for relative, token, other in cases:
            for separator in ("", " ", " / ", "Latin"):
                with self.subTest(relative=relative, separator=separator):
                    with tempfile.TemporaryDirectory() as temp:
                        root = Path(temp)
                        self.write(root, relative, f'val input = "{token}{separator}{other or token}"\n')
                        self.assertEqual([(relative, 1)], [(f.path, f.line) for f in CHECKER.scan(root)])

    def test_separate_exact_literals_on_same_line_remain_allowed(self) -> None:
        relative = "app/src/main/kotlin/com/bydmate/app/media/NaviGoButton.kt"
        token = "\u041f\u043e\u0435\u0445\u0430\u043b\u0438"
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self.write(root, relative, f'listOf("{token}", "{token}")\n')
            self.assertEqual([], CHECKER.scan(root))

    def test_raw_capture_field_rejects_repeated_approved_values(self) -> None:
        relative = "app/src/test/resources/native-stack-fixtures/window-songl-held-target-20260929.txt"
        token = "\u043f\u0440\u043e\u0432\u0435\u0442\u0440\u0438\u0442\u044c"
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self.write(root, relative, f"voice={token}\nvoice={token}{token}\nvoice={token} {token}\n")
            self.assertEqual([(relative, 2), (relative, 3)], [(f.path, f.line) for f in CHECKER.scan(root)])

    def test_frozen_literal_context_rejects_combined_approved_fragments(self) -> None:
        relative = "app/src/test/kotlin/com/bydmate/app/navdata/NavGuidanceHubTest.kt"
        first, second = "\u0443\u043b. \u0410", "\u0443\u043b. \u0411"
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self.write(root, relative, f'val road = "{first}"\nval road = "{first} {second}"\nval road = "{first}{first}"\n')
            self.assertEqual([(relative, 2), (relative, 3)], [(f.path, f.line) for f in CHECKER.scan(root)])

    def test_retained_belarusian_starter_values_are_path_specific(self) -> None:
        relative = "app/src/main/kotlin/com/bydmate/app/ui/automation/AutomationViewModel.kt"
        values = ['\u0417\u0430\u0447\u044b\u043d\u0456\u0446\u044c \u0432\u043e\u043a\u043d\u044b \u043d\u0430 \u0448\u0430\u0448\u044b', '\u0425\u0443\u0442\u043a\u0430\u0441\u0446\u044c > 100 \u043a\u043c/\u0433', '\u0417\u0430\u0447\u044b\u043d\u0456\u0446\u044c \u0443\u0441\u0435 \u0432\u043e\u043a\u043d\u044b', '\u0417\u0456\u043c\u043e\u0432\u044b \u0441\u0442\u0430\u0440\u0442', '\u0422\u044d\u043c\u043f. \u0437\u0432\u043e\u043d\u043a\u0443 < 0\xb0C', '\u0417\u0430\u043f\u0443\u0441\u043a BYDMate', '\u041f\u0430\u0434\u0430\u0433\u0440\u044d\u045e \u043a\u0456\u0440\u043e\u045e\u0446\u044b 2', '\u041f\u0430\u0434\u0430\u0433\u0440\u044d\u045e \u043b\u044e\u0441\u0442\u044d\u0440\u043a\u0430\u045e \u0443\u043a\u043b.', 'ECO \u043f\u0440\u044b \u043d\u0456\u0437\u043a\u0456\u043c \u0437\u0430\u0440\u0430\u0434\u0437\u0435', '\u0420\u044d\u0436\u044b\u043c ECO', '\u041b\u0435\u0442\u043d\u044f\u0435 \u0430\u0445\u0430\u043b\u043e\u0434\u0436\u0432\u0430\u043d\u043d\u0435', '\u0422\u044d\u043c\u043f. \u0441\u0430\u043b\u043e\u043d\u0430 > 30\xb0C', '\u0412\u0435\u043d\u0442\u044b\u043b\u044f\u0446\u044b\u044f \u043a\u0456\u0440\u043e\u045e\u0446\u044b 1', '\u0410\u045e\u0442\u0430 AC', '\u0428\u0442\u043e\u0440\u043a\u0430 \u043f\u0430\u0434\u0447\u0430\u0441 \u0440\u0443\u0445\u0443', '\u041f\u0435\u0440\u0430\u0434\u0430\u0447\u0430 = D', '\u0410\u0434\u043a\u0440\u044b\u0446\u044c \u0448\u0442\u043e\u0440\u043a\u0443', '\u041a\u043b\u0456\u043c\u0430\u0442 \u043f\u0430\u0434\u0447\u0430\u0441 \u0437\u0430\u0440\u0430\u0434\u043a\u0456', '\u0417\u0430\u0440\u0430\u0434\u043a\u0430 = \u043f\u0430\u0447\u0430\u0442\u0430', '\u0422\u044d\u043c\u043f. \u0437\u0432\u043e\u043d\u043a\u0443 < 5\xb0C', '\u041f\u0430\u0434\u0430\u0433\u0440\u044d\u045e \u043a\u0456\u0440\u043e\u045e\u0446\u044b 1']
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self.write(root, relative, "\n".join(f'val value = "{value}"' for value in values))
            self.assertEqual([], CHECKER.scan(root))
            authored = "\u041d\u0430\u0441\u0442\u0440\u043e\u0439\u043a\u0438"
            self.write(root, relative, f'val value = "{values[0]} {authored}"\n')
            self.assertEqual([(relative, 1)], [(f.path, f.line) for f in CHECKER.scan(root)])
            other = "app/src/main/kotlin/com/bydmate/app/ui/Settings.kt"
            self.write(root, relative, "")
            self.write(root, other, f'val value = "{values[0]}"\n')
            self.assertEqual([(other, 1)], [(f.path, f.line) for f in CHECKER.scan(root)])

    def test_legacy_rule_round_trip_values_are_path_specific(self) -> None:
        relative = "app/src/test/kotlin/com/bydmate/app/data/automation/RuleShareTest.kt"
        values = ("\u0414\u043e\u043c: \u0437\u0432\u043e\u043d\u043e\u043a", "\u0421\u043a\u043e\u0440\u043e\u0441\u0442\u044c > 7 \u043a\u043c/\u0447", "\u0417\u0430\u043a\u0440\u044b\u0442\u044c \u0432\u0441\u0435 \u043e\u043a\u043d\u0430")
        authored = "\u041d\u0430\u0441\u0442\u0440\u043e\u0439\u043a\u0438"
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self.write(root, relative, "\n".join(f'val value = "{value}"' for value in values))
            self.assertEqual([], CHECKER.scan(root))
            self.write(root, relative, f'val value = "{values[0]} {authored}"\n')
            self.assertEqual([(relative, 1)], [(f.path, f.line) for f in CHECKER.scan(root)])
            other = "app/src/main/kotlin/com/bydmate/app/ui/Settings.kt"
            self.write(root, relative, "")
            self.write(root, other, f'val value = "{values[0]}"\n')
            self.assertEqual([(other, 1)], [(f.path, f.line) for f in CHECKER.scan(root)])

    def test_forbidden_russian_ui_literal_is_reported_with_path_and_line(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            relative = "app/src/main/kotlin/com/bydmate/app/ui/Settings.kt"
            self.write(root, relative, 'val title = "Настройки"\n')
            self.assertEqual([(relative, 1)], [(f.path, f.line) for f in CHECKER.scan(root)])

    def test_permitted_exact_yandex_token_is_not_reported(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            relative = "app/src/main/kotlin/com/bydmate/app/navdata/NavManeuverCodes.kt"
            self.write(root, relative, 'val yandexRight = "Поверните направо"\n')
            self.assertEqual([], CHECKER.scan(root))

    def test_yandex_path_does_not_allow_an_unapproved_token(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            relative = "app/src/main/kotlin/com/bydmate/app/navdata/NavManeuverCodes.kt"
            self.write(root, relative, 'val title = "Поверните направо Настройки"\n')
            self.assertEqual([(relative, 1)], [(f.path, f.line) for f in CHECKER.scan(root)])

    def test_yandex_token_in_a_different_production_path_is_reported(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            relative = "app/src/main/kotlin/com/bydmate/app/ui/Settings.kt"
            self.write(root, relative, 'val title = "Поверните направо"\n')
            self.assertEqual([(relative, 1)], [(f.path, f.line) for f in CHECKER.scan(root)])

    def test_external_yandex_input_is_path_specific_but_adjacent_authored_text_reports(self) -> None:
        go_text = "\u041f\u043e\u0435\u0445\u0430\u043b\u0438"
        authored_text = "\u041d\u0430\u0441\u0442\u0440\u043e\u0439\u043a\u0438"
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            relative = "app/src/main/kotlin/com/bydmate/app/media/NaviGoButton.kt"
            self.write(root, relative, f'private const val GO_TEXT = "{go_text}"\nval title = "{authored_text}"\n')
            self.assertEqual([(relative, 2)], [(f.path, f.line) for f in CHECKER.scan(root)])

    def test_unicode_transform_table_is_bounded_but_adjacent_authored_text_reports(self) -> None:
        cyrillic_zhe = "\u0436"
        authored_text = "\u041d\u0430\u0441\u0442\u0440\u043e\u0439\u043a\u0438"
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            relative = "app/src/main/kotlin/com/bydmate/app/data/automation/RuleShare.kt"
            self.write(root, relative, f'        \'{cyrillic_zhe}\' to "zh",\n// {authored_text}\n')
            self.assertEqual([(relative, 2)], [(f.path, f.line) for f in CHECKER.scan(root)])

    def test_historical_saved_alias_syntax_is_bounded(self) -> None:
        home_input = "\u0434\u043e\u043c"
        home_key = "\u0414\u043e\u043c"
        authored_text = "\u041d\u0430\u0441\u0442\u0440\u043e\u0439\u043a\u0438"
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            relative = "app/src/main/kotlin/com/bydmate/app/agent/AgentTools.kt"
            self.write(root, relative, f'"{home_input}" -> HomeWork("{home_key}", "home")\nval title = "{authored_text}"\n')
            self.assertEqual([(relative, 2)], [(f.path, f.line) for f in CHECKER.scan(root)])

    def test_removed_widget_label_exception_is_reported(self) -> None:
        vendor_label = "\u042f\u043d\u0434\u0435\u043a\u0441.Nawigator"
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            relative = "app/src/main/kotlin/com/bydmate/app/ui/widget/WidgetPreferences.kt"
            self.write(root, relative, f'val label = "{vendor_label}"\n')
            self.assertEqual([(relative, 1)], [(f.path, f.line) for f in CHECKER.scan(root)])

    def test_captured_beta_map_fields_are_allowed_but_another_field_reports(self) -> None:
        station_name = "\u041c\u0438\u043d\u0441\u043a"
        operator_name = "\u0417\u0430\u0440\u044f\u0434\u043a\u0430"
        authored_text = "\u041d\u0430\u0441\u0442\u0440\u043e\u0439\u043a\u0438"
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            relative = "app/src/test/resources/beta/map-rsc-national-library.txt"
            self.write(root, relative, f'{{"name":"{station_name}","address":"{station_name}","operator":{{"name":"{operator_name}"}}}}\n')
            self.assertEqual([], CHECKER.scan(root))
            self.write(root, relative, f'{{"name":"{station_name}","note":"{authored_text}"}}\n')
            self.assertEqual([(relative, 1)], [(f.path, f.line) for f in CHECKER.scan(root)])

    def test_captured_nominatim_fields_are_allowed_but_another_field_reports(self) -> None:
        place_name = "\u043f\u0440\u043e\u0441\u043f\u0435\u043a\u0442 \u041d\u0435\u0437\u0430\u0432\u0438\u0441\u0438\u043c\u043e\u0441\u0442\u0438"
        authored_text = "\u041d\u0430\u0441\u0442\u0440\u043e\u0439\u043a\u0438"
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            relative = "app/src/test/resources/nominatim/reverse-minsk.json"
            self.write(root, relative, f'{{\n  "name": "{place_name}",\n  "note": "{authored_text}"\n}}\n')
            self.assertEqual([(relative, 3)], [(f.path, f.line) for f in CHECKER.scan(root)])

    def test_saved_place_key_is_allowed_only_in_its_compatibility_path(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            relative = "app/src/main/kotlin/com/bydmate/app/agent/AgentTools.kt"
            self.write(root, relative, 'val legacyPlaceName = "Дом"\n')
            self.assertEqual([], CHECKER.scan(root))

    def test_saved_place_display_label_is_still_reported(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            relative = "app/src/main/kotlin/com/bydmate/app/agent/AgentTools.kt"
            self.write(root, relative, 'HomeWork("Дом", "home")\n')
            self.assertEqual([(relative, 1)], [(f.path, f.line) for f in CHECKER.scan(root)])

    def test_historical_sunshade_key_is_allowed_only_in_its_compatibility_path(self) -> None:
        historical_key = "\u0428\u0442\u043e\u0440\u043a\u0430 \u043f\u0440\u0438 \u0434\u0432\u0438\u0436\u0435\u043d\u0438\u0438"
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            relative = "app/src/main/kotlin/com/bydmate/app/data/automation/PowerStateRuleMigration.kt"
            self.write(root, relative, f'val key = "{historical_key}"\n')
            self.assertEqual([], CHECKER.scan(root))

            other_relative = "app/src/main/kotlin/com/bydmate/app/data/automation/OtherMigration.kt"
            self.write(root, other_relative, f'val key = "{historical_key}"\n')
            self.assertEqual([(other_relative, 1)], [(f.path, f.line) for f in CHECKER.scan(root)])

    def test_hybrid_sunshade_key_remains_reported(self) -> None:
        hybrid_key = "\u0428\u0442\u043e\u0440\u043a\u0430 przy podczas jazdy"
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            relative = "app/src/main/kotlin/com/bydmate/app/data/automation/PowerStateRuleMigration.kt"
            self.write(root, relative, f'val key = "{hybrid_key}"\n')
            self.assertEqual([(relative, 1)], [(f.path, f.line) for f in CHECKER.scan(root)])

    def test_documented_yandex_input_is_allowed_but_authored_text_is_not(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            relative = "docs/plans/2026-10-21-multi-navigator-hud-feasibility.md"
            self.write(root, relative, 'Exact input: "Навигатор запущен"\nAuthored text: "Настройки"\n')
            self.assertEqual([(relative, 2)], [(f.path, f.line) for f in CHECKER.scan(root)])

    def test_only_detekt_signature_ids_are_allowed(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            relative = "config/detekt/baseline.xml"
            self.write(root, relative, '<ID>Rule$throw Exception("Настройки")</ID>\n<note>Настройки</note>\n')
            self.assertEqual([(relative, 2)], [(f.path, f.line) for f in CHECKER.scan(root)])

    def test_belarusian_exception_does_not_allow_non_resource_tampering(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            relative = "app/src/main/res/values-be/strings.xml"
            self.write(root, relative, '<string name="ok">Добра</string>\nval bad = "Настройки"\n')
            self.assertEqual([(relative, 2)], [(f.path, f.line) for f in CHECKER.scan(root)])

    def test_debug_belarusian_capture_errors_require_exact_path_and_xml_context(self) -> None:
        relative = "app/src/debug/res/values-be/nav_capture_errors.xml"
        resource = '<string name="nav_capture_error_write">\u041f\u0430\u043c\u044b\u043b\u043a\u0430 \u0437\u0430\u043f\u0456\u0441\u0443.</string>'
        authored = '<!-- \u041d\u0430\u0441\u0442\u0440\u043e\u0439\u043a\u0438 -->'
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self.write(root, relative, resource + "\n" + authored + "\n")
            self.assertEqual([(relative, 2)], [(f.path, f.line) for f in CHECKER.scan(root)])
            other = "app/src/debug/res/values-be/unreviewed.xml"
            self.write(root, relative, "")
            self.write(root, other, resource + "\n")
            self.assertEqual([(other, 1)], [(f.path, f.line) for f in CHECKER.scan(root)])

    def test_external_navigation_oracle_is_path_specific_but_adjacent_authored_text_reports(self) -> None:
        navigation_query = "\u043a\u0430\u0444\u0435 \u0443 \u0434\u043e\u043c\u0430"
        authored_text = "\u041d\u0430\u0441\u0442\u0440\u043e\u0439\u043a\u0438"
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            relative = "app/src/test/kotlin/com/bydmate/app/data/automation/RouteNavigatorUrisTest.kt"
            self.write(root, relative, f'RouteNavigatorUris.search("waze", "{navigation_query}")\nval title = "{authored_text}"\n')
            self.assertEqual([(relative, 2)], [(f.path, f.line) for f in CHECKER.scan(root)])

    def test_historical_serialized_oracle_is_bounded_but_adjacent_authored_text_reports(self) -> None:
        legacy_toggle = "\u041f\u0435\u0440\u0435\u043a\u043b\u044e\u0447\u0438\u0442\u044c: \u0411\u0430\u0433\u0430\u0436\u043d\u0438\u043a"
        authored_text = "\u041d\u0430\u0441\u0442\u0440\u043e\u0439\u043a\u0438"
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            relative = "app/src/test/kotlin/com/bydmate/app/data/automation/ActionDispatcherToggleTest.kt"
            self.write(root, relative, f'val stored = "{legacy_toggle}"\nval title = "{authored_text}"\n')
            self.assertEqual([(relative, 2)], [(f.path, f.line) for f in CHECKER.scan(root)])

    def test_belarusian_test_oracle_is_path_specific_but_adjacent_authored_text_reports(self) -> None:
        belarusian_label = "\u042d\u043a\u0440\u0430\u043d: \u0432\u044b\u043a\u043b\u044e\u0447\u044b\u0446\u044c"
        authored_text = "\u041d\u0430\u0441\u0442\u0440\u043e\u0439\u043a\u0438"
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            relative = "app/src/test/kotlin/com/bydmate/app/ui/automation/RuleTextTest.kt"
            self.write(root, relative, f'assertEquals("{belarusian_label}", actual)\nval title = "{authored_text}"\n')
            self.assertEqual([(relative, 2)], [(f.path, f.line) for f in CHECKER.scan(root)])

    def test_captured_navigation_test_oracle_is_path_specific_but_adjacent_authored_text_reports(self) -> None:
        notification_input = "\u0427\u0435\u0440\u0435\u0437 300 \u043c \u043d\u0430\u043f\u0440\u0430\u0432\u043e"
        authored_text = "\u041d\u0430\u0441\u0442\u0440\u043e\u0439\u043a\u0438"
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            relative = "app/src/test/kotlin/com/bydmate/app/agent/AgentToolsRouteInfoTest.kt"
            self.write(root, relative, f'val notification = "{notification_input}"\nval title = "{authored_text}"\n')
            self.assertEqual([(relative, 2)], [(f.path, f.line) for f in CHECKER.scan(root)])

    def test_media_session_yandex_notification_oracles_are_bounded(self) -> None:
        notification_inputs = (
            "500 \\u043c", "450 \\u043c", "\\u041f\\u043e\\u0432\\u0435\\u0440\\u043d\\u0438\\u0442\\u0435 \\u043d\\u0430\\u043f\\u0440\\u0430\\u0432\\u043e", "\\u0422\\u0432\\u0435\\u0440\\u0441\\u043a\\u0430\\u044f", "\\u041d\\u0430\\u0432\\u0438\\u0433\\u0430\\u0442\\u043e\\u0440",
            "\\u0423\\u0441\\u0442\\u0430\\u043d\\u043e\\u0432\\u0438\\u0442\\u0435 \\u0441\\u0435\\u0440\\u0432\\u0438\\u0441\\u044b Google Play", '\\u0414\\u043b\\u044f \\u0440\\u0430\\u0431\\u043e\\u0442\\u044b \\u043f\\u0440\\u0438\\u043b\\u043e\\u0436\\u0435\\u043d\\u0438\\u044f "\\u042f\\u043d\\u0434\\u0435\\u043a\\u0441 \\u041d\\u0430\\u0432\\u0438\\u0433\\u0430\\u0442\\u043e\\u0440" \\u0442\\u0440\\u0435\\u0431\\u0443\\u0435\\u0442\\u0441\\u044f',
        )
        authored_text = "\\u041d\\u0430\\u0441\\u0442\\u0440\\u043e\\u0439\\u043a\\u0438"
        notification_inputs = tuple(value.encode("ascii").decode("unicode_escape") for value in notification_inputs)
        authored_text = authored_text.encode("ascii").decode("unicode_escape")
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            relative = "app/src/test/kotlin/com/bydmate/app/media/MediaSessionListenerServiceTest.kt"
            self.write(root, relative, "\n".join(f'val input = "{value.replace(chr(34), chr(92) + chr(34))}"' for value in notification_inputs) + f'\nval label = "{authored_text}"\n')
            self.assertEqual([(relative, len(notification_inputs) + 1)], [(f.path, f.line) for f in CHECKER.scan(root)])

    def test_hud_transliteration_oracles_are_bounded(self) -> None:
        transliteration_inputs = (
            "\\u041f\\u0440\\u043e\\u0441\\u043f\\u0435\\u043a\\u0442 \\u041d\\u0435\\u0437\\u0430\\u0432\\u0438\\u0441\\u0438\\u043c\\u043e\\u0441\\u0442\\u0438", "\\u0429\\u0443\\u043a\\u0438\\u043d\\u0430", "\\u0416\\u0443\\u043a\\u043e\\u0432\\u0430", "\\u042f\\u0440\\u043e\\u0441\\u043b\\u0430\\u0432\\u0430 \\u0413\\u0430\\u0448\\u0435\\u043a\\u0430", "\\u041e\\u0431\\u044a\\u0435\\u0437\\u0434\\u043d\\u0430\\u044f", "\\u041b\\u0435\\u043d\\u0438\\u043d\\u0433\\u0440\\u0430\\u0434\\u0441\\u043a\\u0438\\u0439 \\u043f\\u0440.", "\\u0401\\u043b\\u043a\\u0438\\u043d\\u0430 \\u0425\\u0432\\u043e\\u0439\\u043d\\u0430\\u044f \\u0426\\u0435\\u043d\\u0442\\u0440\\u0430\\u043b\\u044c\\u043d\\u0430\\u044f \\u0427\\u0435\\u0445\\u043e\\u0432\\u0430 \\u042d\\u043a\\u0441\\u043f\\u043e",
            "\\u0432\\u0443\\u043b\\u0456\\u0446\\u0430 \\u041a\\u0456\\u0440\\u0430\\u0432\\u0430", "\\u0412\\u0443\\u043b\\u0456\\u0446\\u0430 \\u041f\\u0430\\u045e\\u043b\\u044e\\u043a\\u0430", "\\u041f\\u0430\\u0434\\u2019\\u0435\\u0437\\u0434", "\\u0416\\u0423\\u041a\\u041e\\u0412\\u0410", "\\u0429.", "\\u0443\\u043b. \\u0429\\u041e\\u0420\\u0421\\u0410", " \\u041b\\u0435\\u043d\\u0438\\u043d\\u0430 ",
        )
        authored_text = "\\u041d\\u0430\\u0441\\u0442\\u0440\\u043e\\u0439\\u043a\\u0438"
        transliteration_inputs = tuple(value.encode("ascii").decode("unicode_escape") for value in transliteration_inputs)
        authored_text = authored_text.encode("ascii").decode("unicode_escape")
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            relative = "app/src/test/kotlin/com/bydmate/app/hud/HudTextSanitizerTest.kt"
            self.write(root, relative, "\n".join(f'assertEquals("Latin", sanitize("{value}"))' for value in transliteration_inputs) + f'\nval label = "{authored_text}"\n')
            self.assertEqual([(relative, len(transliteration_inputs) + 1)], [(f.path, f.line) for f in CHECKER.scan(root)])

    def test_agent_tools_compatibility_rows_do_not_allow_a_launcher_label(self) -> None:
        legacy_work_row = '"praca", "do pracy", "moja praca" -> HomeWork("Praca", "work", legacyPlaceName = "\u0420\u0430\u0431\u043e\u0442\u0430")'
        alphabet_pair = "'\u0430' to \"a\""
        authored_text = "\u041d\u0430\u0441\u0442\u0440\u043e\u0439\u043a\u0438"
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            relative = "app/src/main/kotlin/com/bydmate/app/agent/AgentTools.kt"
            self.write(root, relative, f"            {legacy_work_row}\nprivate val folds = mapOf({alphabet_pair})\nval label = \"{authored_text}\"\n")
            self.assertEqual([(relative, 3)], [(f.path, f.line) for f in CHECKER.scan(root)])

    def test_generated_output_is_ignored(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self.write(root, "app/build/generated/Labels.kt", 'val title = "Настройки"\n')
            self.assertEqual([], CHECKER.scan(root))

    def test_generic_mocked_overpass_labels_are_not_compatibility_exceptions(self) -> None:
        generic_labels = tuple(
            value.encode("ascii").decode("unicode_escape")
            for value in (
                "\\u0421\\u0442\\u043e\\u044f\\u043d\\u043a\\u0430",
                "\\u0411\\u0435\\u0437 \\u043a\\u043e\\u043e\\u0440\\u0434\\u0438\\u043d\\u0430\\u0442",
                "\\u0411\\u0438\\u0442\\u044b\\u0439 center",
                "\\u0416\\u0438\\u0432\\u0430\\u044f",
            )
        )
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            relative = "app/src/test/kotlin/com/bydmate/app/agent/ChargerSearchClientTest.kt"
            self.write(root, relative, "\n".join(f'val fixture = "{label}"' for label in generic_labels) + "\n")
            self.assertEqual(
                [(relative, index) for index in range(1, len(generic_labels) + 1)],
                [(finding.path, finding.line) for finding in CHECKER.scan(root)],
            )

    def test_frozen_maneuver_corpus_allows_known_row_but_rejects_new_or_adjacent_text(self) -> None:
        known_row, novel_row, adjacent_authored_text = (
            value.encode("ascii").decode("unicode_escape")
            for value in (
                "\\u041f\\u043e\\u0432\\u0435\\u0440\\u043d\\u0438\\u0442\\u0435 \\u043d\\u0430\\u043f\\u0440\\u0430\\u0432\\u043e",
                "\\u041d\\u043e\\u0432\\u0430\\u044f \\u0440\\u0443\\u0441\\u0441\\u043a\\u0430\\u044f \\u0444\\u0440\\u0430\\u0437\\u0430",
                "\\u041d\\u0430\\u0441\\u0442\\u0440\\u043e\\u0439\\u043a\\u0438",
            )
        )
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            relative = "app/src/test/resources/navdata/maneuver-corpus.txt"
            self.write(root, relative, f"{known_row}\n{novel_row}\n{known_row} {adjacent_authored_text}\n")
            self.assertEqual(
                [(relative, 2), (relative, 3)],
                [(finding.path, finding.line) for finding in CHECKER.scan(root)],
            )

    def test_native_capture_is_exact_and_adjacent_authored_text_reports(self) -> None:
        capture, authored_text = (
            value.encode("ascii").decode("unicode_escape")
            for value in ("\\u043f\\u0440\\u043e\\u0432\\u0435\\u0442\\u0440\\u0438\\u0442\\u044c", "\\u041d\\u0430\\u0441\\u0442\\u0440\\u043e\\u0439\\u043a\\u0438")
        )
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            relative = "app/src/test/resources/native-stack-fixtures/window-songl-held-target-20260929.txt"
            self.write(root, relative, f"voice={capture}\nlabel={authored_text}\n")
            self.assertEqual([(relative, 2)], [(finding.path, finding.line) for finding in CHECKER.scan(root)])

    def test_report_backed_navigation_token_is_path_specific_and_adjacent_text_reports(self) -> None:
        road, authored_text = (
            value.encode("ascii").decode("unicode_escape")
            for value in ("\\u041c\\u0438\\u043d\\u0441\\u043a\\u043e\\u0435 \\u0448\\u043e\\u0441\\u0441\\u0435", "\\u041d\\u0430\\u0441\\u0442\\u0440\\u043e\\u0439\\u043a\\u0438")
        )
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            relative = "app/src/test/kotlin/com/bydmate/app/hud/HudPushLoopTest.kt"
            self.write(root, relative, f'val road = "{road}"\nval title = "{authored_text}"\n')
            self.assertEqual([(relative, 2)], [(finding.path, finding.line) for finding in CHECKER.scan(root)])

    def test_report_backed_belarusian_token_is_path_specific_and_adjacent_text_reports(self) -> None:
        place, authored_text = (
            value.encode("ascii").decode("unicode_escape")
            for value in ("\\u041e\\u0440\\u0448\\u0430", "\\u041d\\u0430\\u0441\\u0442\\u0440\\u043e\\u0439\\u043a\\u0438")
        )
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            relative = "app/src/test/kotlin/com/bydmate/app/agent/AgentToolsPlacesTest.kt"
            self.write(root, relative, f'val destination = "{place}"\nval title = "{authored_text}"\n')
            self.assertEqual([(relative, 2)], [(finding.path, finding.line) for finding in CHECKER.scan(root)])

    def test_report_backed_raw_capture_token_is_path_specific_and_adjacent_text_reports(self) -> None:
        capture, authored_text = (
            value.encode("ascii").decode("unicode_escape")
            for value in ("\\u0412\\u0441\\u0442\\u0440\\u043e\\u0435\\u043d\\u043d\\u044b\\u0439 \\u044d\\u043a\\u0440\\u0430\\u043d", "\\u041d\\u0430\\u0441\\u0442\\u0440\\u043e\\u0439\\u043a\\u0438")
        )
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            relative = "app/src/test/kotlin/com/bydmate/app/helper/DisplayDumpFixtures.kt"
            self.write(root, relative, f'capture={capture}\nlabel={authored_text}\n')
            self.assertEqual([(relative, 2)], [(finding.path, finding.line) for finding in CHECKER.scan(root)])

    def test_report_backed_history_and_belarusian_tokens_reject_adjacent_authored_text(self) -> None:
        history, belarusian, authored_text = (
            value.encode("ascii").decode("unicode_escape")
            for value in (
                "\\u043c\\u0430\\u0448\\u0438\\u043d\\u0430 \\u0432\\u044b\\u043a\\u043b\\u044e\\u0447\\u0435\\u043d\\u0430 \\u0432 18:54",
                "\\u0417\\u0430\\u0445\\u0430\\u0432\\u0430\\u043d\\u0430",
                "\\u041d\\u0430\\u0441\\u0442\\u0440\\u043e\\u0439\\u043a\\u0438",
            )
        )
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            history_path = "app/src/test/kotlin/com/bydmate/app/helper/offreport/OffReportTest.kt"
            belarusian_path = "app/src/test/kotlin/com/bydmate/app/util/AppStringsTest.kt"
            self.write(root, history_path, f'val stored = "{history}"\nval title = "{authored_text}"\n')
            self.write(root, belarusian_path, f'assertEquals("{belarusian}", actual)\nval title = "{authored_text}"\n')
            self.assertEqual(
                [(history_path, 2), (belarusian_path, 2)],
                [(finding.path, finding.line) for finding in CHECKER.scan(root)],
            )


if __name__ == "__main__":
    unittest.main()
