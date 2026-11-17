import { test } from "node:test";
import assert from "node:assert/strict";
import { changedPaths, evaluate } from "./readme-check.mjs";

test("parses porcelain lines, renames and quoted paths", () => {
  const out = changedPaths(' M services/api/pom.xml\n?? apps/web/app/page.tsx\nR  old.md -> docs/requirements.md\n M "docs/a b.md"\n');
  assert.deepEqual(out, ["services/api/pom.xml", "apps/web/app/page.tsx", "docs/requirements.md", "docs/a b.md"]);
});

test("nudges when product files changed and README did not", () => {
  const r = evaluate(["services/ingest/src/Main.java", "notes.txt"]);
  assert.equal(r.nudge, true);
  assert.deepEqual(r.files, ["services/ingest/src/Main.java"]);
});

test("does not nudge when README changed too", () => {
  assert.equal(evaluate(["services/ingest/src/Main.java", "README.md"]).nudge, false);
});

test("does not nudge for files outside the watched folders", () => {
  assert.equal(evaluate([".claude/hooks/x.mjs", "CLAUDE.md", ".gitignore"]).nudge, false);
});

test("watches compose files, scripts and git hooks", () => {
  assert.equal(evaluate(["compose.yaml"]).nudge, true);
  assert.equal(evaluate(["scripts/verify.sh"]).nudge, true);
  assert.equal(evaluate([".githooks/pre-push"]).nudge, true);
});
