import path from "node:path";
import type { NextConfig } from "next";

const config: NextConfig = {
  // A self-contained server for the Docker image. The repository root is the tracing root because pnpm keeps
  // the dependencies there.
  output: "standalone",
  outputFileTracingRoot: path.join(import.meta.dirname, "../.."),
  // Types are checked by `pnpm typecheck` (part of `pnpm verify`), not a second time during the build.
  typescript: { ignoreBuildErrors: true },
  poweredByHeader: false,
};

export default config;
