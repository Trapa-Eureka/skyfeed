export interface Point {
  /** Time in ms. */
  t: number;
  v: number;
}

/**
 * Splits a series where two neighbouring points are further apart than `maxGapMs`. Each part is drawn as its
 * own line, so a period without readings shows as a gap and is never bridged by a line that looks like data.
 */
export function segments(points: readonly Point[], maxGapMs: number): Point[][] {
  const parts: Point[][] = [];
  let current: Point[] = [];
  for (const point of points) {
    const previous = current[current.length - 1];
    if (previous && point.t - previous.t > maxGapMs) {
      parts.push(current);
      current = [];
    }
    current.push(point);
  }
  if (current.length > 0) parts.push(current);
  return parts;
}
