#!/usr/bin/env node
// Requirement-to-test check (R-AI-02): lists every requirement in docs/requirements.md with the tests that name
// it, and fails when a requirement is named by no test, or a test names a requirement that does not exist.
// Usage: node scripts/trace.mjs            exit code 1 if anything is missing
//        node scripts/trace.mjs --json     the same result as JSON
import { globSync, readFileSync } from "node:fs";
import { dirname, join, relative } from "node:path";
import { fileURLToPath } from "node:url";

const ID = /\bR-[A-Z]{2,5}-\d{2}\b/g;

/** Where a test, or a scripted check, may name a requirement. */
export const TEST_FILES = [
  "services/*/src/test/**/*.java",
  "tools/*/src/test/**/*.java",
  "tools/*/test/**/*.ts",
  "apps/*/test/**/*.ts",
  "apps/*/e2e/**/*.ts",
  "scripts/*.test.mjs",
  "scripts/smoke.sh",
  "scripts/drill.sh",
  "scripts/cms.sh",
];

/** The requirements table rows of docs/requirements.md: id and text. */
export function parseRequirements(markdown) {
  const requirements = [];
  for (const line of markdown.split("\n")) {
    const cells = line.split("|").map((cell) => cell.trim());
    if (cells.length >= 5 && /^R-[A-Z]{2,5}-\d{2}$/.test(cells[1])) requirements.push({ id: cells[1], text: cells[2] });
  }
  return requirements;
}

/**
 * Table rows whose first cell starts like an id but is not one this check understands (for example a long
 * area name or three digits). Such a row would otherwise be skipped without a word, and so never be checked.
 */
export function malformedIds(markdown) {
  const found = [];
  for (const line of markdown.split("\n")) {
    const cells = line.split("|").map((cell) => cell.trim());
    if (cells.length >= 3 && /^R-/.test(cells[1]) && !/^R-[A-Z]{2,5}-\d{2}$/.test(cells[1])) found.push(cells[1]);
  }
  return found;
}

/** Every requirement id written in a text, each once. */
export function findIds(text) {
  return [...new Set(text.match(ID) ?? [])];
}

/**
 * @param requirements from parseRequirements
 * @param files [{ path, text }] for every test file
 * @returns for each requirement the files naming it, plus what is wrong
 */
export function trace(requirements, files) {
  const known = new Set(requirements.map((requirement) => requirement.id));
  const namedBy = new Map(requirements.map((requirement) => [requirement.id, []]));
  const unknown = [];
  for (const file of files) {
    for (const id of findIds(file.text)) {
      if (known.has(id)) namedBy.get(id).push(file.path);
      else unknown.push({ id, path: file.path });
    }
  }
  const duplicates = requirements.map((r) => r.id).filter((id, index, all) => all.indexOf(id) !== index);
  return {
    requirements: requirements.map((requirement) => ({ ...requirement, tests: namedBy.get(requirement.id) })),
    uncovered: requirements.filter((requirement) => namedBy.get(requirement.id).length === 0).map((requirement) => requirement.id),
    unknown,
    duplicates: [...new Set(duplicates)],
  };
}

export function run(root) {
  const markdown = readFileSync(join(root, "docs/requirements.md"), "utf8");
  const requirements = parseRequirements(markdown);
  const files = TEST_FILES.flatMap((pattern) => globSync(pattern, { cwd: root, exclude: (name) => name === "node_modules" || name === "target" }))
    .sort()
    .map((path) => ({ path, text: readFileSync(join(root, path), "utf8") }));
  return { ...trace(requirements, files), malformed: malformedIds(markdown), filesRead: files.length };
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  const root = join(dirname(fileURLToPath(import.meta.url)), "..");
  const result = run(root);
  if (process.argv.includes("--json")) {
    console.log(JSON.stringify(result, null, 2));
  } else {
    for (const requirement of result.requirements) {
      const tests = requirement.tests.map((path) => relative(".", path).split("/").pop());
      console.log(`${requirement.tests.length ? "ok  " : "MISS"}  ${requirement.id.padEnd(9)} ${tests.length ? [...new Set(tests)].join(", ") : "no test names this requirement"}`);
    }
    console.log(`\n${result.requirements.length} requirements, ${result.requirements.length - result.uncovered.length} named by a test, ${result.filesRead} test files read.`);
    for (const { id, path } of result.unknown) console.log(`UNKNOWN  ${path} names ${id}, which is not in docs/requirements.md`);
    for (const id of result.duplicates) console.log(`DUPLICATE  ${id} appears more than once in docs/requirements.md`);
    for (const id of result.malformed) console.log(`MALFORMED  ${id} in docs/requirements.md is not of the form R-AREA-NN, so it cannot be checked`);
  }
  const failed =
    result.uncovered.length > 0 || result.unknown.length > 0 || result.duplicates.length > 0 || result.malformed.length > 0 || result.requirements.length === 0;
  process.exit(failed ? 1 : 0);
}
