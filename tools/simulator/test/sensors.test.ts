import assert from "node:assert/strict";
import { test } from "node:test";
import { reading, SENSORS, topicFor } from "../src/sensors.ts";
import { readJson, validatorFor } from "./helpers.ts";

const T0 = Date.UTC(2026, 10, 17, 6, 0, 0);

test("every simulated reading is valid telemetry.v1", () => {
  const validate = validatorFor("telemetry.v1.schema.json");
  for (const sensor of SENSORS) {
    for (let seq = 0; seq < 500; seq++) {
      const message = reading(sensor, seq, T0 + seq * 2000, "skyfeed");
      assert.ok(validate(message), `${sensor.id} seq ${seq}: ${JSON.stringify(validate.errors)}`);
    }
  }
});

test("readings are repeatable: same seed, sensor, seq and time give the same message", () => {
  const sensor = SENSORS[0]!;
  assert.deepEqual(reading(sensor, 7, T0, "a"), reading(sensor, 7, T0, "a"));
  assert.notDeepEqual(reading(sensor, 7, T0, "a"), reading(sensor, 7, T0, "b"));
  assert.notDeepEqual(reading(sensor, 7, T0, "a"), reading(sensor, 8, T0, "a"));
});

test("sensor ids are unique and the MQTT topic carries the id", () => {
  assert.equal(new Set(SENSORS.map((s) => s.id)).size, SENSORS.length);
  assert.equal(topicFor("gs-iloilo"), "sensors/gs-iloilo/telemetry");
});

test("the telemetry.v1 examples: the valid one passes, the invalid one fails", () => {
  const validate = validatorFor("telemetry.v1.schema.json");
  assert.ok(validate(readJson("contracts/examples/telemetry.v1.valid.json")), JSON.stringify(validate.errors));
  assert.equal(validate(readJson("contracts/examples/telemetry.v1.invalid.json")), false);
});
