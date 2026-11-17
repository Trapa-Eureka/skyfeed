import assert from "node:assert/strict";
import type { AddressInfo } from "node:net";
import { test } from "node:test";
import { createOpenSkyMock } from "../src/opensky-mock.ts";
import { frameAt, loadFrames, loopSeconds, parseFrames, withinBox, type Frame } from "../src/replay.ts";
import { readJson, repoPath, toAdsbState, validatorFor } from "./helpers.ts";

// Three frames 10 s apart; one aircraft moving east, a second one without a position.
const FRAMES: Frame[] = [1000, 1010, 1020].map((time, i) => ({
  time,
  states: [
    ["abc123", "TEST1   ", "Nowhere", time - 1, time, 120 + i, 12, 1000, false, 200, 90, 0, null, 1100, "1200", false, 0],
    ["def456", null, "Nowhere", null, time, null, null, null, false, null, null, null, null, null, null, false, 0],
  ],
}));
const START = 5_000_000_000; // ms

test("R-ING-06 replay is deterministic: same file, start, time and speed give the same response", () => {
  for (const elapsed of [0, 9_999, 10_000, 25_000, 31_000, 3_600_000]) {
    assert.deepEqual(frameAt(FRAMES, START, START + elapsed), frameAt(FRAMES, START, START + elapsed));
    assert.deepEqual(frameAt(FRAMES, START, START + elapsed, 4), frameAt(FRAMES, START, START + elapsed, 4));
  }
});

test("R-ING-06 the recorded file replays to the same sequence on every run", () => {
  const run = () => {
    const frames = loadFrames(repoPath("tools/simulator/fixtures/opensky-states.jsonl"));
    return JSON.stringify(Array.from({ length: 80 }, (_, i) => frameAt(frames, START, START + i * 5000)));
  };
  assert.equal(run(), run());
});

test("frames advance with time and recorded times are moved to the start time", () => {
  const lonAt = (elapsedMs: number) => frameAt(FRAMES, START, START + elapsedMs).states[0]![5];
  assert.equal(lonAt(0), 120);
  assert.equal(lonAt(9_999), 120);
  assert.equal(lonAt(10_000), 121);
  assert.equal(lonAt(20_000), 122);

  const frame = frameAt(FRAMES, START, START + 10_000);
  assert.equal(frame.time, START / 1000 + 10);
  assert.equal(frame.states[0]![3], START / 1000 + 9); // time_position was 1 s before the frame
  assert.equal(frame.states[0]![4], START / 1000 + 10);
  assert.equal(frame.states[1]![3], null); // unknown stays unknown
});

test("after the last frame the replay starts over and time keeps moving forward", () => {
  assert.equal(loopSeconds(FRAMES), 30);
  const again = frameAt(FRAMES, START, START + 30_000);
  assert.equal(again.states[0]![5], 120);
  assert.equal(again.time, START / 1000 + 30);

  let previous = 0;
  for (let elapsed = 0; elapsed < 100_000; elapsed += 1000) {
    const { time } = frameAt(FRAMES, START, START + elapsed);
    assert.ok(time >= previous, `time went backwards at ${elapsed} ms`);
    previous = time;
  }
});

test("speed 2 plays the recording twice as fast", () => {
  assert.equal(frameAt(FRAMES, START, START + 5_000, 2).states[0]![5], 121);
  assert.equal(frameAt(FRAMES, START, START + 5_000, 2).time, START / 1000 + 5);
});

test("a file with no frames, or frames out of time order, is refused", () => {
  assert.throws(() => parseFrames("\n"), /no frames/);
  assert.throws(() => parseFrames('{"time":2,"states":[]}\n{"time":1,"states":[]}\n'), /time order/);
});

test("the bounding box keeps aircraft inside it and drops those without a position", () => {
  const all = frameAt(FRAMES, START, START);
  assert.equal(withinBox(all, { lamin: 10, lomin: 119, lamax: 15, lomax: 125 }).states.length, 1);
  assert.equal(withinBox(all, { lamin: 13, lomin: 119, lamax: 15, lomax: 125 }).states.length, 0);
});

test("the replay server answers like OpenSky /api/states/all", async () => {
  let now = START;
  const server = createOpenSkyMock({ frames: FRAMES, startMs: START, now: () => now });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  try {
    const first = (await (await fetch(`${base}/api/states/all`)).json()) as Frame;
    assert.equal(first.states.length, 2);
    assert.equal(first.states[0]![5], 120);

    now = START + 10_000;
    const boxed = (await (await fetch(`${base}/api/states/all?lamin=10&lomin=119&lamax=15&lomax=125`)).json()) as Frame;
    assert.equal(boxed.states.length, 1);
    assert.equal(boxed.states[0]![5], 121);

    assert.equal((await fetch(`${base}/api/states/all?lamin=x&lomin=1&lamax=2&lomax=3`)).status, 400);
    assert.equal((await fetch(`${base}/nope`)).status, 404);
    assert.equal((await fetch(`${base}/health`)).status, 200);
  } finally {
    server.close();
  }
});

test("every recorded aircraft with a position maps to a valid adsb.state.v1 message", () => {
  const validate = validatorFor("adsb.state.v1.schema.json");
  const frames = loadFrames(repoPath("tools/simulator/fixtures/opensky-states.jsonl"));
  let checked = 0;
  for (const frame of frames) {
    for (const state of frame.states) {
      if (typeof state[5] !== "number" || typeof state[6] !== "number" || typeof state[3] !== "number") continue;
      const message = toAdsbState(state);
      assert.ok(validate(message), `${JSON.stringify(message)}: ${JSON.stringify(validate.errors)}`);
      checked++;
    }
  }
  assert.ok(checked > 0, "the recording holds no aircraft with a position");
});

test("the adsb.state.v1 examples: the valid one passes, the invalid one fails", () => {
  const validate = validatorFor("adsb.state.v1.schema.json");
  assert.ok(validate(readJson("contracts/examples/adsb.state.v1.valid.json")), JSON.stringify(validate.errors));
  assert.equal(validate(readJson("contracts/examples/adsb.state.v1.invalid.json")), false);
});
