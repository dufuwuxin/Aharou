#!/usr/bin/env python3
"""Source contracts only: no Compose rendering, gesture or device verification."""
import re
import unittest
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
COMPONENT = ROOT / "app/src/main/java/com/aharou/feature/agent/presentation/component"
WORKBENCH = (COMPONENT / "ProjectTaskWorkbench.kt").read_text()
MAP = (COMPONENT / "ProjectTaskMap.kt").read_text()


class ProjectCompactLayoutContract(unittest.TestCase):
    def test_single_action_bar_without_duplicate_navigation(self):
        self.assertIn('testTag("project_action_bar")', WORKBENCH)
        self.assertNotIn("onClick = onBack", WORKBENCH)
        self.assertIn("BackHandler(enabled = expandedId != null)", WORKBENCH)
        self.assertNotIn("SingleChoiceSegmentedButtonRow", WORKBENCH)
        self.assertNotIn("SegmentedButton(", WORKBENCH)
        self.assertIn('testTag("project_view_menu")', WORKBENCH)
        self.assertIn('testTag("project_more_menu")', WORKBENCH)
        self.assertIn('semantics { selected = viewMode == mode }', WORKBENCH)
        self.assertIn('if (viewMode == mode) Icon(FeatherIcons.Check', WORKBENCH)
        for mode in ('"tree" to R.string.project_task_tree', '"map" to R.string.project_task_map'):
            self.assertIn(mode, WORKBENCH)

    def test_stop_remains_enabled_while_busy(self):
        self.assertIn("if (active) viewModel.stopProject(projectId) else viewModel.startProject(projectId)", WORKBENCH)
        self.assertIn("enabled = active || (", WORKBENCH)
        self.assertIn('if (active) "project_flow_stop" else "project_flow_start"', WORKBENCH)

    def test_search_is_opt_in_and_close_clears_query(self):
        self.assertIn("var searchOpen by rememberSaveable(projectId) { mutableStateOf(false) }", MAP)
        search = MAP.index("if (searchOpen) {")
        field = MAP.index("AppTextField(query,")
        viewport = MAP.index('testTag("map_viewport")')
        self.assertLess(search, field)
        self.assertLess(field, viewport)
        self.assertIn('query = ""; searchOpen = false', MAP)
        self.assertIn('if (searchOpen) { query = ""; keyboard?.hide() }', MAP)
        self.assertIn('BackHandler(enabled = searchOpen) { query = ""; searchOpen = false; keyboard?.hide() }', MAP)
        self.assertIn('"map_search_clear") { query = "" }', MAP)
        self.assertIn("ProjectTaskOutline.find(tasks, query)", MAP)
        self.assertIn("LaunchedEffect(query, firstHit)", MAP)
        self.assertIn("if (searching) bringIntoView(firstHit)", MAP)

    def test_legend_is_only_on_demand(self):
        self.assertNotIn("R.string.project_task_dependency_legend", MAP)
        self.assertIn("if (legend) AlertDialog(", WORKBENCH)
        self.assertIn("onClick = { legend = true; viewMenu = false }", WORKBENCH)
        self.assertIn("Text(stringResource(R.string.project_task_dependency_legend)", WORKBENCH)

    def test_icon_controls_have_48dp_targets(self):
        helper = MAP[MAP.index("private fun ProjectMapIcon("):MAP.index("/** Header row:")]
        self.assertIn("IconButton(onClick = onClick, modifier = Modifier.size(48.dp).testTag(tag))", helper)
        self.assertIn("Icon(icon, label, modifier = Modifier.size(Spacing.lg))", helper)
        controls = MAP[MAP.index("Surface(shape = RoundedCornerShape(Radius.pill)"):MAP.index("if (processId ==")]
        self.assertNotIn("TextButton(", controls)
        self.assertIn("Column {", controls)
        for tag in ("map_search_toggle", "map_expand_all", "map_zoom_in", "map_zoom_out", "map_fit", "map_reset"):
            self.assertIn('"' + tag + '"', controls)
        self.assertIn("modifier = Modifier.size(48.dp).testTag(if (active)", WORKBENCH)

    def test_manual_add_root_and_process_contracts_remain(self):
        for contract in ("ProjectTaskAddSheet(", "viewModel.addTaskByDescription(",
                         "addingParentId = row.task.id", "onAddChild = { task ->",
                         "rootConversation()", "rootConversation = rootConversation",
                         "onAccept = { viewModel.accept(task) }", "readOnly = true",
                         "(maxHeight * 0.9f).coerceIn(240.dp, 640.dp)"):
            self.assertIn(contract, WORKBENCH)
        self.assertIn("onAddChild(selected)", MAP)
        self.assertIn("rootConversation()", MAP)
        self.assertIn("fillMaxHeight(0.9f)", MAP)

    def test_menu_semantics_use_existing_chinese_resources(self):
        strings = {item.attrib["name"]: item.text for item in ET.parse(ROOT / "app/src/main/res/values/strings.xml").getroot() if item.tag == "string"}
        for resource in ("project_view_options", "project_more_options", "project_task_overview",
                         "project_map_legend", "project_map_search_close", "project_add_task"):
            self.assertIn("R.string." + resource, WORKBENCH + MAP)
            self.assertTrue(strings[resource])
        for resource in set(re.findall(r"R\.string\.(\w+)", WORKBENCH + MAP)):
            self.assertIn(resource, strings)


if __name__ == "__main__":
    unittest.main()
