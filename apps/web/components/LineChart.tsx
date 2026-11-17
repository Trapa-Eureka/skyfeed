"use client";

import { useMemo, useState, type PointerEvent } from "react";
import { segments, type Point } from "@/lib/chart";

export type { Point };

/** Readings come every few seconds; a longer silence than this is drawn as a gap. */
const MAX_GAP_MS = 10_000;

interface Props {
  title: string;
  unit: string;
  points: Point[];
  /** The time window shown, in ms: [from, to]. */
  window: [number, number];
  digits?: number;
}

const W = 328;
const H = 96;
const PAD = { left: 40, right: 8, top: 8, bottom: 18 };

const hhmmss = (t: number) => new Date(t).toISOString().slice(11, 19);

/** A good-looking step for about `count` ticks across `span`. */
function niceStep(span: number, count: number): number {
  const raw = span / count;
  const power = 10 ** Math.floor(Math.log10(raw));
  const fraction = raw / power;
  return (fraction <= 1 ? 1 : fraction <= 2 ? 2 : fraction <= 5 ? 5 : 10) * power;
}

/** One measure over time: a single 2px line broken where readings are missing, a quiet grid, the latest value named, a crosshair and readout on hover. */
export function LineChart({ title, unit, points, window, digits = 1 }: Props) {
  const [hover, setHover] = useState<Point | null>(null);
  const [from, to] = window;

  const scale = useMemo(() => {
    const values = points.map((point) => point.v);
    let min = values.length ? Math.min(...values) : 0;
    let max = values.length ? Math.max(...values) : 1;
    if (max - min < 1e-9) {
      min -= 1;
      max += 1;
    }
    const step = niceStep(max - min, 3);
    min = Math.floor(min / step) * step;
    max = Math.ceil(max / step) * step;
    const ticks: number[] = [];
    for (let tick = min; tick <= max + step / 2; tick += step) ticks.push(tick);
    const x = (t: number) => PAD.left + ((t - from) / Math.max(1, to - from)) * (W - PAD.left - PAD.right);
    const y = (v: number) => PAD.top + (1 - (v - min) / (max - min)) * (H - PAD.top - PAD.bottom);
    return { x, y, ticks, decimals: step < 1 ? Math.ceil(-Math.log10(step)) : 0 };
  }, [points, from, to]);

  const visible = points.filter((point) => point.t >= from && point.t <= to);
  const parts = segments(visible, MAX_GAP_MS);
  const path = parts
    .filter((part) => part.length > 1)
    .map((part) => part.map((point, i) => `${i === 0 ? "M" : "L"}${scale.x(point.t).toFixed(1)},${scale.y(point.v).toFixed(1)}`).join(" "))
    .join(" ");
  const latest = visible[visible.length - 1];

  const onMove = (event: PointerEvent<SVGSVGElement>) => {
    if (visible.length === 0) return;
    const box = event.currentTarget.getBoundingClientRect();
    const px = ((event.clientX - box.left) / box.width) * W;
    let nearest = visible[0]!;
    for (const point of visible) if (Math.abs(scale.x(point.t) - px) < Math.abs(scale.x(nearest.t) - px)) nearest = point;
    setHover(nearest);
  };

  return (
    <figure className="chart" data-testid="chart">
      <figcaption>
        <span className="chart-title">{title}</span>
        <span className="chart-readout" data-testid="chart-readout">
          {hover
            ? `${hover.v.toFixed(digits)} ${unit} at ${hhmmss(hover.t)}`
            : latest
              ? `latest ${latest.v.toFixed(digits)} ${unit}`
              : "no readings in the last 15 minutes"}
        </span>
      </figcaption>
      <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label={`${title} over the last 15 minutes, ${visible.length} readings`} onPointerMove={onMove} onPointerLeave={() => setHover(null)}>
        {scale.ticks.map((tick) => (
          <g key={tick}>
            <line className="grid" x1={PAD.left} x2={W - PAD.right} y1={scale.y(tick)} y2={scale.y(tick)} />
            <text className="tick" x={PAD.left - 6} y={scale.y(tick)} textAnchor="end" dominantBaseline="middle">
              {tick.toFixed(scale.decimals)}
            </text>
          </g>
        ))}
        <text className="tick" x={PAD.left} y={H - 4} textAnchor="start">{hhmmss(from)}</text>
        <text className="tick" x={W - PAD.right} y={H - 4} textAnchor="end">{hhmmss(to)}</text>
        <path className="series" d={path} data-testid="chart-line" data-points={visible.length} data-segments={parts.length} data-latest={latest?.t ?? ""} />
        {/* A reading with no neighbour close enough to join has no line: it is drawn as a small dot. */}
        {parts.filter((part) => part.length === 1).map((part) => (
          <circle key={part[0]!.t} className="lone" cx={scale.x(part[0]!.t)} cy={scale.y(part[0]!.v)} r={1.5} />
        ))}
        {latest && !hover && <circle className="dot" cx={scale.x(latest.t)} cy={scale.y(latest.v)} r={4} />}
        {hover && (
          <>
            <line className="crosshair" x1={scale.x(hover.t)} x2={scale.x(hover.t)} y1={PAD.top} y2={H - PAD.bottom} />
            <circle className="dot" cx={scale.x(hover.t)} cy={scale.y(hover.v)} r={4} />
          </>
        )}
      </svg>
    </figure>
  );
}
