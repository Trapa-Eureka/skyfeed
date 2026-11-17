#!/usr/bin/env node
// Stop hook: keeps README.md in step with the project.
// When Claude is about to finish a turn and product files changed but README.md did not,
// it sends Claude back once to run the `sync-readme` skill (or to say no update is needed).
// Input:  JSON on stdin ({ stop_hook_active, ... }).
// Output: {"decision":"block","reason":...} on stdout to continue the turn; exit 0 with no output to allow the stop.
// No dependencies. A broken hook must never trap the session, so every error path allows the stop.

import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";

// Paths whose changes can make the README wrong (features, commands, layout, status).
const WATCHED = /^(services|apps|tools|infra|contracts|docs|scripts|\.githooks)\/|^(compose[^/]*\.ya?ml|docker-compose[^/]*\.ya?ml|package\.json|pnpm-workspace\.yaml)/;

/** @param {string} porcelain output of `git status --porcelain` */
export function changedPaths(porcelain) {
  return porcelain
    .split("\n")
    .filter((line) => line.length > 3)
    .map((line) => line.slice(3).replace(/^"|"$/g, ""))
    .map((p) => (p.includes(" -> ") ? p.split(" -> ")[1] : p));
}

/** @returns {{ nudge: boolean, files?: string[] }} */
export function evaluate(paths) {
  if (paths.includes("README.md")) return { nudge: false };
  const files = paths.filter((p) => WATCHED.test(p));
  return files.length ? { nudge: true, files } : { nudge: false };
}

async function main() {
  let raw = "";
  for await (const chunk of process.stdin) raw += chunk;
  let input = {};
  try {
    input = JSON.parse(raw);
  } catch {
    process.exit(0);
  }
  // Already sent back once in this turn: never loop.
  if (input.stop_hook_active) process.exit(0);

  let porcelain = "";
  try {
    porcelain = execFileSync("git", ["status", "--porcelain", "--untracked-files=all"], {
      cwd: process.env.CLAUDE_PROJECT_DIR || process.cwd(),
      encoding: "utf8",
      stdio: ["ignore", "pipe", "ignore"],
    });
  } catch {
    process.exit(0);
  }

  const { nudge, files } = evaluate(changedPaths(porcelain));
  if (!nudge) process.exit(0);

  const shown = files.slice(0, 8).join(", ") + (files.length > 8 ? `, and ${files.length - 8} more` : "");
  process.stdout.write(
    JSON.stringify({
      decision: "block",
      reason:
        `[readme-check] Product files changed (${shown}) but README.md did not. ` +
        "Run the sync-readme skill: update the status line, Features table, commands, layout and Roadmap so they match what now exists and was actually run. " +
        "If nothing in the README is affected, say so in one line and stop.",
    }),
  );
  process.exit(0);
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  main();
}
