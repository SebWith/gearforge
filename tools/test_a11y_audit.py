import json
import unittest
import xml.etree.ElementTree as ET

import a11y_audit


def node(path, **kw):
    base = {"kind": "node", "w": 1, "p": path, "cls": "android.view.View", "text": None, "desc": None,
            "visible": True, "clickable": False, "checkable": False, "editable": False,
            "longClickable": False, "b": [0, 0, 200, 200]}
    base.update(kw)
    return base


class TreeAuditTest(unittest.TestCase):
    def audit(self, *nodes):
        text = "\n".join(json.dumps(n) for n in nodes)
        return a11y_audit.audit_tree(a11y_audit.load_tree(text), 2.75)

    def test_a_button_is_named_by_its_text_child(self):
        self.assertEqual([], self.audit(node("0", clickable=True), node("0.0", text="Export")))

    def test_an_unnamed_button_is_reported(self):
        findings = self.audit(node("0", clickable=True))
        self.assertTrue(any("no accessible name" in f for f in findings))

    def test_a_field_that_only_shows_its_value_has_no_name(self):
        findings = self.audit(node("0", editable=True, text="20"))
        self.assertTrue(any("text field without a name" in f for f in findings))

    def test_a_field_with_a_content_description_is_named(self):
        self.assertEqual([], self.audit(node("0", editable=True, text="20", desc="Teeth")))

    def test_a_small_target_is_reported_in_dp(self):
        findings = self.audit(node("0", clickable=True, desc="Reset", b=[0, 0, 200, 55]))
        self.assertTrue(any("target 73x20dp" in f for f in findings))

    def test_clipped_characters_are_reported(self):
        clipped = node("0", text="Settings", charLoc={"n": 8, "hidden": 3, "partial": 0})
        self.assertTrue(any("clipped text" in f for f in self.audit(clipped)))

    def test_centred_text_with_every_box_shifted_out_is_not_clipping(self):
        shifted = node("0", text="Saved files", b=[464, 1955, 684, 2010],
                       charLoc={"n": 11, "hidden": 10, "partial": 0, "firstHidden": 0})
        self.assertEqual([], self.audit(shifted))

    def test_a_second_line_cut_by_a_fixed_height_is_clipping(self):
        cut = node("0", text="Saved files", b=[1000, 849, 1210, 959],
                   charLoc={"n": 11, "hidden": 5, "partial": 1, "firstHidden": 6, "ink": [1000, 849, 1169, 959]})
        self.assertTrue(any("clipped text" in f for f in self.audit(cut)))

    def test_a_chip_half_scrolled_out_of_its_row_is_not_a_finding(self):
        row = node("0", scrollable=True, b=[0, 0, 980, 132])
        chip = node("0.0", clickable=True, checkable=True, b=[946, 0, 980, 132])
        self.assertEqual([], self.audit(row, chip))


class XmlAuditTest(unittest.TestCase):
    def test_a_chip_cut_by_a_scrolling_row_is_not_counted_as_unlabelled_or_small(self):
        xml = ('<hierarchy><node scrollable="true" clickable="false" bounds="[0,0][980,132]">'
               '<node clickable="true" text="" content-desc="" bounds="[946,0][980,132]"/></node></hierarchy>')
        clickable, unlabelled, small = a11y_audit.audit(ET.fromstring(xml), 2.75)
        self.assertEqual((1, [], []), (clickable, unlabelled, small))

    def test_a_small_unlabelled_button_is_still_reported(self):
        xml = '<hierarchy><node clickable="true" text="" content-desc="" bounds="[0,0][55,55]"/></hierarchy>'
        clickable, unlabelled, small = a11y_audit.audit(ET.fromstring(xml), 2.75)
        self.assertEqual(1, len(unlabelled))
        self.assertEqual(1, len(small))

    def test_a_row_under_the_bottom_of_the_window_is_not_small_but_still_needs_a_label(self):
        xml = ('<hierarchy><node clickable="false" bounds="[0,0][1080,2340]">'
               '<node clickable="true" text="" content-desc="Delete Gear 1" bounds="[926,2228][1058,2340]"/>'
               '<node clickable="true" text="" content-desc="" bounds="[22,2228][926,2340]"/></node></hierarchy>')
        clickable, unlabelled, small = a11y_audit.audit(ET.fromstring(xml), 2.75)
        self.assertEqual((2, 1, []), (clickable, len(unlabelled), small))


class SourceLintTest(unittest.TestCase):
    def test_a_filter_chip_outside_controls_is_reported(self):
        src = 'FilterChip(selected = a, onClick = {}, label = { Text("x") })'
        self.assertEqual(1, len(a11y_audit.lint_source("GearWorkspace.kt", src)))
        self.assertEqual([], a11y_audit.lint_source("Controls.kt", src))

    def test_a_plain_dialog_title_is_reported(self):
        src = 'AlertDialog(title = { Text(I18n.t(lang, "export")) })'
        self.assertIn("DialogTitle", a11y_audit.lint_source("X.kt", src)[0])
        self.assertEqual([], a11y_audit.lint_source("X.kt", 'AlertDialog(title = { DialogTitle(t) })'))

    def test_an_icon_button_without_a_name_is_reported(self):
        bad = "IconButton(onClick = {}) { Icon(Icons.Filled.Close, contentDescription = null) }"
        good = 'IconButton(onClick = {}) { Icon(Icons.Filled.Close, contentDescription = I18n.t(lang, "close")) }'
        self.assertEqual(1, len(a11y_audit.lint_source("X.kt", bad)))
        self.assertEqual([], a11y_audit.lint_source("X.kt", good))

    def test_a_text_field_needs_a_label_or_a_description(self):
        bad = "OutlinedTextField(value = v, onValueChange = { v = it }, singleLine = true)"
        labelled = "OutlinedTextField(value = v, onValueChange = {}, label = { Text(x) })"
        described = "OutlinedTextField(value = v, onValueChange = {}, modifier = Modifier.semantics { contentDescription = l })"
        self.assertEqual(1, len(a11y_audit.lint_source("X.kt", bad)))
        self.assertEqual([], a11y_audit.lint_source("X.kt", labelled))
        self.assertEqual([], a11y_audit.lint_source("X.kt", described))

    def test_a_switch_with_its_own_callback_is_reported(self):
        self.assertEqual(1, len(a11y_audit.lint_source("X.kt", "Switch(checked = v, onCheckedChange = { v = it })")))
        self.assertEqual([], a11y_audit.lint_source("X.kt", "Switch(checked = v, onCheckedChange = null)"))

    def test_quotes_inside_string_templates_do_not_unbalance_the_scan(self):
        src = 'OutlinedTextField(value = v, label = { Text("${I18n.t(lang, "valid_range")} 1)") })\nFilterChip(selected = a)'
        findings = a11y_audit.lint_source("X.kt", src)
        self.assertEqual(1, len(findings))
        self.assertIn("FilterChip", findings[0])


if __name__ == "__main__":
    unittest.main()
