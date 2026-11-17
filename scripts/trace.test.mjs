import assert from "node:assert/strict";
import { test } from "node:test";
import { findIds, malformedIds, parseRequirements, trace } from "./trace.mjs";

// Made-up ids are put together at run time, so this file itself names no requirement that does not exist.
const id = (area, number) => `R-${area}-${number}`;
const A = id("DEMO", "01");
const B = id("DEMO", "02");
const TABLE = `# Requirements

| Id | Requirement | Phase | Covered by |
|---|---|---|---|
| ${A} | The first thing. | 1 | \`SomeTest\` |
| ${B} | The second thing, with a | pipe in a later cell. | 2 | |

Not a row: ${id("DEMO", "09")} mentioned in prose.
`;

test("R-AI-02 the requirement rows are read from the table, and prose is not taken for a row", () => {
  assert.deepEqual(parseRequirements(TABLE).map((requirement) => requirement.id), [A, B]);
  assert.equal(parseRequirements(TABLE)[0].text, "The first thing.");
});

test("R-AI-02 ids are found wherever a test names them, each once", () => {
  assert.deepEqual(findIds(`@DisplayName("${A} ${B} does something") // ${A} again`), [A, B]);
  assert.deepEqual(findIds("no ids here, not even R-1 or XR-AB-01x"), []);
});

test("R-AI-02 a requirement that no test names is reported", () => {
  const result = trace(parseRequirements(TABLE), [{ path: "a/FirstTest.java", text: `covers ${A}` }]);
  assert.deepEqual(result.uncovered, [B]);
  assert.deepEqual(result.requirements[0].tests, ["a/FirstTest.java"]);
  assert.deepEqual(result.unknown, []);
});

test("R-AI-02 a test that names a requirement which does not exist is reported", () => {
  const ghost = id("DEMO", "77");
  const result = trace(parseRequirements(TABLE), [{ path: "a/Test.java", text: `${A} ${B} ${ghost}` }]);
  assert.deepEqual(result.uncovered, []);
  assert.deepEqual(result.unknown, [{ id: ghost, path: "a/Test.java" }]);
});

test("R-AI-02 a row whose id is not of the form the check understands is reported, not skipped", () => {
  const long = `R-${"GATEWAYSERVICE"}-01`;
  const threeDigits = `${id("DEMO", "100")}`;
  const odd = `${TABLE}| ${long} | Too long an area. | 1 | |\n| ${threeDigits} | Three digits. | 1 | |\n`;
  assert.deepEqual(malformedIds(odd), [long, threeDigits]);
  assert.deepEqual(malformedIds(TABLE), []);
});

test("R-AI-02 an id that appears twice in the requirements is reported", () => {
  const twice = `${TABLE}| ${A} | The first thing again. | 1 | |\n`;
  assert.deepEqual(trace(parseRequirements(twice), []).duplicates, [A]);
});
