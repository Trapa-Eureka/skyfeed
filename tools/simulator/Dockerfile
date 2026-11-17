# Build context is the repository root (the pnpm lockfile lives there).
FROM node:24-alpine
WORKDIR /repo
RUN corepack enable
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
COPY tools/simulator/package.json tools/simulator/
RUN pnpm install --frozen-lockfile --prod --filter @skyfeed/simulator
COPY tools/simulator/src tools/simulator/src
COPY tools/simulator/fixtures tools/simulator/fixtures
WORKDIR /repo/tools/simulator
USER node
EXPOSE 18090
# Node 24 runs TypeScript directly (types are stripped at load time), so there is no build step.
CMD ["node", "src/main.ts"]
