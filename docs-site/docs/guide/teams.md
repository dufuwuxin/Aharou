# Teams

Teams use Markdown files in `~/.aharou/teams/` or the workspace's `.aharou/teams/`. Project definitions override global definitions with the same name. Editing a team affects the next run; it does not rewrite an in-flight run.

## One administrator

The existing YAML `admins` list remains supported and must contain exactly one entry. A member administrator must still belong to `members`. To make the main agent the administrator, use the reserved value `__main_agent__`:

```yaml
---
name: Example
admins: [__main_agent__]
members:
  - name: Builder
  - name: Reviewer
stages:
  - id: build
    title: Build
    tasks:
      - id: implement
        agent: Builder
        prompt: Implement the authorized change and report verifiable evidence.
        writePaths: [src/example.kt]
---
Report failures and unverified checks explicitly; never claim execution from a plan.
```

`__main_agent__` is an administrator identifier, not an agent profile. Do not add it to `members`, stage members, or task agents. The settings editor labels this option as the main agent. Existing member-administrator YAML needs no migration.

## Visibility

`task(action="teams")` returns each team's name, description and charter. For a named team, the same three fields are always available. Only a persisted root session querying a team whose administrator is `__main_agent__` receives complete `admins`, `members`, `stages`, task prompts, write paths, interfaces and `routes`. Other teams do not reveal internal workflow details. The repository is selected using the calling session's actual workspace, not a caller-supplied workspace argument.

The tool does not accept caller claims such as `admin`, `mainSession`, `includeDetails`, hidden pipeline fields, or arbitrary member commands. The stored session's parent relationship determines main-session authority.

## Reassigning an existing task

A main-agent administrator can select a different existing roster member for an existing task:

```text
task(action="team_assign", team="Example", stageId="build", taskId="implement", member="Reviewer")
```

This operation saves the same scoped team definition and returns `state="assigned"`, `appliesTo="next_run"`. It preserves the task identifier, prompt, interfaces, write paths and routes. It cannot create tasks or members, expand file authority, inject a new prompt, or merge another task already assigned to the same member in that stage. Invalid assignments and non-administrator callers are rejected.

Run with `task(action="team_run", team="Example", goal="The authorized objective")`. The existing TeamRunner and stage executor resolve the selected real member profile and run the pipeline with the calling session as parent. No administrator child session is synthesized. Normal role resolution, roster checks, write leases, review and rejection routes remain enforced.

A successful assignment is not execution evidence. Check the subsequent run and accepted member outputs; a blocked or failed run must not be reported as completed.

### Scoped team editing and stage conditions

Enable the scoped team-editing channel in Special Permissions only when you want the agent to edit team configuration. Its address is bound to the current workspace; it is not general permission to run shell commands or inspect unrelated settings. Global edits affect every project using that team, while project edits apply to the bound workspace. Disabling the channel, restarting the app or reaching its one-hour expiry revokes the address.

Ask the agent to inspect the available fields before proposing an edit. Read the current value and revision, then submit the change with that revision. If another edit has changed the value, read it again and review the new proposal rather than retrying with the old revision. The enabled scoped channel also offers role creation, full role-form editing and deletion. Each operation uses an explicit global or project scope and the current revision. Global edits can affect other projects. Deletion and renaming reject references found in the current workspace and global teams or workflow leads; the check does not claim to inspect every unopened project. Inspectable task nodes do not grant arbitrary file or shell access.

The team editor puts members and their relationship graph before the ordered stage settings. Tap one member and then another to open the relationship editor; source and target sets can each contain multiple members. Collaboration edges are stored with the task interfaces. They may be bidirectional and may include a coordination note; they inform prompts without changing execution order. Execution, review, and rejection edges use the existing validated flow graph. A cycle or invalid rejection target is rejected before saving. With no explicit route, the runner keeps legacy sequential stage execution.

Member levels remain readable in existing team files but no longer form numbered member groups or affect execution. The role picker adds a new selection to the team roster in the same change. Missing historical roles remain marked unavailable until replaced, and department-and-keyword tasks retain their original selection rules. Stage settings remain ordered and can contain multiple tasks, separate prompts, write paths, review stages, and language conditions. Task write paths and other low-frequency controls remain under advanced options; task identifiers and existing relationship edges are preserved when editing unrelated fields.

Stages accept `condition: always|never|rust|python|kotlin`; the default is `always`. Set a language condition only when every task in that stage may be skipped for projects without that language. For a stage mixing several languages or responsibilities, keep `always` or separate the work into stages. Conditions apply to the whole stage, not individual tasks, and routes are not language conditions.

Language conditions inspect the bound project files. Nonmatching stages are recorded as skipped without launching agents or creating delivery evidence; successors continue. Unknown conditions or inaccessible workspace files stop execution explicitly.

Conditional stages run through the existing team executor and retain its routing, review and delivery-evidence checks. Skipped stages produce an explicit skip record without fabricated member output. Directory read failures and incomplete scans are reported as unknown instead of silently skipping a language stage.

Keep the stage's communication guide alongside its communication policy and message limit. Changing the policy does not replace the guide, and permission to communicate does not expand tool or file-write authority.

## Editing a workflow

In the workflow editor, choose an existing team for each layer. Use the move-up and move-down controls to change layer order, and choose a lead or leave the team's default lead selected. Configure which layers accept rejection and the maximum rejection rounds deliberately; adding a layer does not automatically create a repair path.

Save the workflow before starting it. A saved order or lead selection is configuration, not evidence that those teams have run. Verify the resulting execution and delivery records separately. Global definitions may be shared by several projects, so check the selected scope before saving.
