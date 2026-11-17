---
name: sync-readme
description: Bring README.md in line with the current state of the SkyFeed repo. Use after finishing a phase or any change to services, commands, ports, topics, layout or requirements, and whenever the readme-check hook asks for it.
---

# Sync the README

The README is the public face of this portfolio project. A reader (a hiring engineer) must be able to trust every line, so it only states what exists in the repo and was actually run.

## Steps

1. Read `README.md`, `docs/requirements.md` and `git status` / `git diff --stat` to see what changed since the README was last touched.
2. Update only the sections the change affects:

| If this changed | Update |
|---|---|
| A phase was finished or started | the **Status** note at the top, and the checkbox in **Roadmap** |
| A capability now works end to end | its row in **Features** (`Planned` → `Done`, with what it was checked against) |
| A service, port, topic or env variable | **Architecture**, **Quick start**, **Configuration** |
| Tool versions or prerequisites | **Requirements**, **Tech stack** |
| How to build, run or test | **Installation**, **Quick start**, **Develop this repository** |
| Folders added, moved or removed | **Project layout** |
| A known gap or surprising behaviour was found | **Known limitations** |
| A requirement was added or covered by a test | `docs/requirements.md` (the README links to it) |

3. Run any command you add or change in the README before writing it down. If it cannot be run here, mark it as not yet verified.
4. Keep the section order and tone of the existing README. Do not add badges, marketing language or features that are not built.

## Rules

- `Done` means it ran here and the check is named (a test, a command, a screenshot). Otherwise it is `In progress` or `Planned`.
- Never describe a planned phase in the present tense.
- Screenshots go in `docs/images/` and must come from the running app with replay data, never mock-ups.
- English only, like the rest of the repository.
