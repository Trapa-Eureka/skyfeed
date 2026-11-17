// MapLibre GL runs part of its work in a web worker that is a separate file. The bundler does not pick it up,
// so it is copied from the installed package into public/ before `next dev` and `next build`, and the map is
// told where it is (see components/MapView.tsx). The copy is not committed.
import { copyFileSync, mkdirSync } from "node:fs";
import { createRequire } from "node:module";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const require = createRequire(import.meta.url);
const source = join(dirname(require.resolve("maplibre-gl/package.json")), "dist", "maplibre-gl-worker.mjs");
const target = join(dirname(fileURLToPath(import.meta.url)), "..", "public", "maplibre");
mkdirSync(target, { recursive: true });
copyFileSync(source, join(target, "maplibre-gl-worker.mjs"));
