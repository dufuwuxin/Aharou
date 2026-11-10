"""Source contracts only: no Compose rendering, gesture or device verification."""
import re
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
COMPONENT = ROOT / "app/src/main/java/com/aharou/feature/agent/presentation/component"
WORKBENCH = (COMPONENT / "ProjectTaskWorkbench.kt").read_text()
MAP = (COMPONENT / "ProjectTaskMap.kt").read_text()


def region(text, marker, opening="{"):
    """Extract a balanced Kotlin block/call, excluding quoted/comment delimiters."""
    start = text.index(opening, text.index(marker) + len(marker))
    closing = {"{": "}", "(": ")"}[opening]
    depth = 0
    for token in re.finditer(r'"(?:\\.|[^"\\])*"|//[^\n]*|/\*.*?\*/|[{}()]', text[start:], re.DOTALL):
        if token.group() == opening:
            depth += 1
        elif token.group() == closing:
            depth -= 1
            if depth == 0:
                return text[start + 1:start + token.start()]
    raise AssertionError("Unclosed Kotlin region: " + marker)


class ProjectCompactLayoutContract(unittest.TestCase):
    def assert_branch_submit(self, workbench):
        submit = region(workbench, "onSubmit =", opening="{")
        self.assertIn("viewModel.createRequirementBranch(projectId, description, parentId = addingParentId)", submit)
        self.assertIn("onSelectRequirementBranch(branchId)", submit)
        self.assertNotIn("addTaskByDescription", submit)

    def assert_header(self, workbench):
        header = region(workbench, "val header:")
        self.assertEqual(1, header.count('testTag("project_compact_header")'))
        self.assertIn("IconButton(onClick = onBack, modifier = Modifier.size(48.dp))", header)
        host = region(header, '            Box ')
        self.assertIn("IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(48.dp))", host)
        self.assertIn("DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false })", host)
        menu = region(host, "onDismissRequest = { menuOpen = false })")
        self.assertIn("mapMenu { menuOpen = false }", menu)
        self.assertIn("sessionMenu { menuOpen = false }", menu)
        self.assertIn('onClick = { expandedId = null; viewMode = if (viewMode == "map") "tree" else "map"; menuOpen = false }', menu)
        self.assertIn("header = header, process = process)", workbench)
        self.assertIn("else Column(Modifier.fillMaxSize()) {\n                header {}", workbench)

    def assert_stop(self, workbench):
        menu = region(region(workbench, "val header:"), "onDismissRequest = { menuOpen = false })")
        stop = region(menu, "if (projectId in state.activeProjectIds) DropdownMenuItem", "(")
        self.assertNotRegex(stop, r"\benabled\s*=")
        self.assertIn('modifier = Modifier.testTag("project_flow_stop")', stop)
        self.assertIn("onClick = { menuOpen = false; viewModel.stopProject(projectId) }", stop)
        start = region(menu, "else DropdownMenuItem", "(")
        self.assertIn("val executableTasks = tasks.filterNot { it.isRequirementBranch }", workbench)
        self.assertIn("enabled = !state.loading && !state.busy && !planning && executableTasks.any { !ProjectWorkflowPlan.delivered(it) }", start)
        self.assertIn("onClick = { menuOpen = false; viewModel.startProject(projectId) }", start)

    def assert_search(self, task_map):
        self.assertIn("var showSearch by rememberSaveable(projectId) { mutableStateOf(false) }", task_map)
        back = region(task_map, "BackHandler(enabled = showSearch)")
        close = region(task_map[task_map.index('IconButton(onClick = { showSearch = false;'):], 'IconButton', "(")
        menu = region(task_map, "        header")
        toggle = region(menu, "DropdownMenuItem", "(")
        for callback in (back, region(close, "onClick ="), region(toggle, "onClick =")):
            self.assertIn('query = ""', callback)
            self.assertIn("keyboard?.hide()", callback)
        self.assertIn("showSearch = false", back)
        self.assertIn("showSearch = false", close)
        self.assertIn('showSearch = !showSearch; if (!showSearch)', toggle)
        search = region(task_map, "if (showSearch) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically)")
        self.assertIn('IconButton(onClick = { query = "" })', search)
        self.assertIn('AppTextField(query, { query = it }', search)
        self.assertLess(task_map.index("if (showSearch) Row"), task_map.index('testTag("map_viewport")'))
        self.assertIn("ProjectTaskOutline.find(tasks, query)", task_map)
        self.assertIn("LaunchedEffect(query, firstHit) { if (searching) bringIntoView(firstHit) }", task_map)
        viewport = region(task_map, "fun bringIntoView(id: String?)")
        self.assertIn("panX = viewW / 2f - (node.x + node.headerWidth / 2f) * zoom", viewport)
        self.assertIn("panY = viewH * FOCUS_ROW - node.dotY * zoom", viewport)

    def test_single_action_bar_without_duplicate_navigation(self):
        self.assert_header(WORKBENCH)
        for original, replacement in (("mapMenu { menuOpen = false }", ""),
                                      ("DropdownMenu(expanded = menuOpen,", "RemovedMenu(expanded = menuOpen,")):
            mutant = WORKBENCH.replace(original, replacement, 1)
            self.assertNotEqual(mutant, WORKBENCH)
            with self.assertRaises(AssertionError):
                self.assert_header(mutant)
        self.assertEqual(1, WORKBENCH.count('testTag("project_compact_header")'))
        self.assertIn("BackHandler(enabled = expandedId != null)", WORKBENCH)
        for old in ("project_action_bar", "project_view_menu", "project_more_menu", "SingleChoiceSegmentedButtonRow", "SegmentedButton("):
            self.assertNotIn(old, WORKBENCH)
        panel = (COMPONENT / "AIChatPanel.kt").read_text()
        self.assertEqual(1, panel.count("ChatHeader("))
        self.assertIn("if (bodyOverride == null) ChatHeader(", region(panel, "topBar ="))

    def test_stop_remains_enabled_while_busy(self):
        self.assert_stop(WORKBENCH)
        mutant = WORKBENCH.replace("if (projectId in state.activeProjectIds) DropdownMenuItem(",
                                   "if (projectId in state.activeProjectIds) DropdownMenuItem(enabled = !state.busy,", 1)
        self.assertNotEqual(mutant, WORKBENCH)
        with self.assertRaises(AssertionError):
            self.assert_stop(mutant)

    def test_search_is_opt_in_and_close_clears_query(self):
        fixture = MAP
        self.assert_search(fixture)
        cleanup = 'query = ""; keyboard?.hide()'
        positions = [match.start() for match in re.finditer(re.escape(cleanup), fixture)]
        self.assertEqual(3, len(positions))
        for position in positions:
            mutant = fixture[:position] + fixture[position:].replace(cleanup, "keyboard?.hide()", 1)
            self.assertNotEqual(mutant, fixture)
            with self.assertRaises(AssertionError):
                self.assert_search(mutant)
        self.assert_search(MAP)

    def test_legend_is_only_on_demand(self):
        # The restored compact source removed the legend and persistent zoom rail.
        for old in ("R.string.project_task_dependency_legend", "R.string.project_map_legend", '"map_zoom_in"', '"map_zoom_out"', '"map_reset"', "ProjectMapIcon("):
            self.assertNotIn(old, WORKBENCH + MAP)
        menu = region(MAP, "        header")
        self.assertEqual(3, menu.count("DropdownMenuItem("))
        self.assertIn("if (allOpen) { expanded = emptySet(); measured.clear() } else expanded = tasks.mapTo(HashSet()) { it.id }", menu)
        self.assertIn("suppressed = emptySet(); dismiss()", menu)
        fit = menu[menu.index("R.string.project_map_fit"):]
        for contract in ("zoom = ProjectTaskMapLayout.fitZoom(", "panX = (viewportWidth - layout.width * zoom) / 2f", "panY = (viewportHeight - layout.height * zoom) / 2f", "dismiss()"):
            self.assertIn(contract, fit)
        gesture = region(MAP, "            detectTransformGestures")
        for contract in ("onDrag()", "ProjectTaskMapLayout.clampZoom(zoom * scale, minimum, MAX_ZOOM)", "panX = centroid.x", "panY = centroid.y", "zoom = next"):
            self.assertIn(contract, gesture)

    def test_icon_controls_have_48dp_targets(self):
        header = region(WORKBENCH, "val header:")
        self.assertIn('heightIn(min = 48.dp).testTag("project_compact_header")', header)
        buttons = list(re.finditer(r"\bIconButton(?=\()", header))
        self.assertEqual(2, len(buttons))
        for button in buttons:
            self.assertIn("modifier = Modifier.size(48.dp)", region(header[button.start():], "IconButton", "("))
        menu = region(header, "onDismissRequest = { menuOpen = false })")
        self.assertEqual(5, menu.count("DropdownMenuItem("))
        for controls in (menu, region(MAP, "        header")):
            self.assertNotRegex(controls, r"\b(?:TextButton|IconButton|Button)\(")
            self.assertNotIn(".clickable", controls)

    def test_empty_branch_root_and_process_contracts_remain(self):
        for contract in ("ProjectTaskAddSheet(", "viewModel.createRequirementBranch(",
                         "addingParentId = row.task.id", "onAddChild = { task ->",
                         "rootConversation()", "rootConversation = rootConversation",
                         "onAccept = { viewModel.accept(task) }", "readOnly = true",
                         "(maxHeight * 0.9f).coerceIn(240.dp, 640.dp)"):
            self.assertIn(contract, WORKBENCH)
        self.assertIn("onAddChild(selected)", MAP)
        self.assertIn("rootConversation()", MAP)
        self.assertIn("fillMaxHeight(0.9f)", MAP)
        self.assert_branch_submit(WORKBENCH)
        for resource in ("project_add_task", "project_node_add_child"):
            strings = ET.parse(ROOT / "app/src/main/res/values/strings.xml").getroot()
            self.assertEqual("添加需求分支", strings.find(f"string[@name='{resource}']").text)

    def test_branch_exclusion_and_creation_mutants_are_rejected(self):
        for old, new in (("tasks.filterNot { it.isRequirementBranch }", "tasks"),
                         ("executableTasks.any { !ProjectWorkflowPlan.delivered(it) }", "tasks.any { !ProjectWorkflowPlan.delivered(it) }")):
            mutant = WORKBENCH.replace(old, new, 1)
            self.assertNotEqual(mutant, WORKBENCH)
            with self.assertRaises(AssertionError):
                self.assert_stop(mutant)
        for old, new in (("viewModel.createRequirementBranch(", "viewModel.addTaskByDescription("),
                         ("parentId = addingParentId", "parentId = null"),
                         ("onSelectRequirementBranch(branchId)", "onSelectRequirementBranch(null)")):
            mutant = WORKBENCH.replace(old, new, 1)
            self.assertNotEqual(mutant, WORKBENCH)
            with self.assertRaises(AssertionError):
                self.assert_branch_submit(mutant)
        self.assertIn("tasks.count { !it.isRequirementBranch && ProjectWorkflowPlan.delivered(it) }", MAP)
        self.assertIn("tasks.count { !it.isRequirementBranch }", MAP)

    def test_menu_semantics_use_existing_chinese_resources(self):
        strings = {item.attrib["name"]: "".join(item.itertext()) for item in ET.parse(ROOT / "app/src/main/res/values/strings.xml").getroot() if item.tag == "string"}
        for resource in ("project_view_actions", "project_task_overview", "project_map_search_close", "project_add_task", "project_task_tree", "project_task_map", "project_map_fit"):
            self.assertIn("R.string." + resource, WORKBENCH + MAP)
            self.assertRegex(strings[resource], r"[\u4e00-\u9fff]")
        for resource in set(re.findall(r"R\.string\.(\w+)", WORKBENCH + MAP)):
            self.assertIn(resource, strings)
            self.assertTrue(strings[resource])


if __name__ == "__main__":
    unittest.main()
