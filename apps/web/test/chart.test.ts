import { describe, expect, it } from "vitest";
import { segments } from "../lib/chart";

describe("chart series", () => {
  it("R-UI-02 a period without readings splits the line, so missing data is not drawn as if it were measured", () => {
    const at = (seconds: number) => ({ t: seconds * 1000, v: seconds });
    const parts = segments([at(0), at(2), at(4), at(300), at(302), at(900)], 10_000);
    expect(parts.map((part) => part.map((point) => point.t / 1000))).toEqual([[0, 2, 4], [300, 302], [900]]);
  });

  it("a series without gaps stays one line, and an empty one gives nothing", () => {
    expect(segments([{ t: 0, v: 1 }, { t: 2000, v: 2 }], 10_000)).toHaveLength(1);
    expect(segments([], 10_000)).toEqual([]);
  });
});
