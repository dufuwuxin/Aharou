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
