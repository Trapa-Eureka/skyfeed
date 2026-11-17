import { describe, expect, it } from "vitest";
import { ageSeconds, formatAge, formatAltitude, formatSpeed, sensorStatus } from "../lib/age";

const NOW = Date.parse("2026-11-17T06:00:30Z");

describe("data age", () => {
  it("R-UI-02 the age is the whole seconds since the timestamp, never negative", () => {
    expect(ageSeconds("2026-11-17T06:00:26.400Z", NOW)).toBe(3);
    expect(ageSeconds("2026-11-17T06:00:30Z", NOW)).toBe(0);
    expect(ageSeconds("2026-11-17T06:00:45Z", NOW)).toBe(0); // a clock that runs ahead
  });

  it("R-UI-02 an age is written short and with its unit", () => {
    expect(formatAge(4)).toBe("4 s");
    expect(formatAge(125)).toBe("2 min 05 s");
    expect(formatAge(3780)).toBe("1 h 03 min");
  });

  it("R-UI-04 a sensor is online, then degraded, then offline as its latest reading ages", () => {
    expect(sensorStatus(0, 10, 30)).toBe("online");
    expect(sensorStatus(9, 10, 30)).toBe("online");
    expect(sensorStatus(10, 10, 30)).toBe("degraded");
    expect(sensorStatus(29, 10, 30)).toBe("degraded");
    expect(sensorStatus(30, 10, 30)).toBe("offline");
  });

  it("R-UI-01 altitude is shown in feet to the nearest hundred, speed in knots", () => {
    expect(formatAltitude(10980.42, false)).toBe("36,000 ft");
    expect(formatAltitude(null, false)).toBe("alt ?");
    expect(formatAltitude(12, true)).toBe("GND");
    expect(formatSpeed(245.53)).toBe("477 kt");
    expect(formatSpeed(null)).toBe("?");
  });
});
