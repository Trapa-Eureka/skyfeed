# Build context is cms/. Building the admin panel takes about 1.9 GB of memory for half a minute (measured);
# scripts/cms.sh stops the rest of the stack while this image is built.
FROM node:22-slim AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
ENV NODE_ENV=production STRAPI_TELEMETRY_DISABLED=true
RUN npm run build && npm prune --omit=dev --no-audit --no-fund

FROM node:22-slim
WORKDIR /app
ENV NODE_ENV=production STRAPI_TELEMETRY_DISABLED=true HOST=0.0.0.0 PORT=1337 \
    DATABASE_CLIENT=sqlite DATABASE_FILENAME=/data/data.db
COPY --from=build --chown=node:node /app ./
RUN mkdir /data && chown node:node /data
USER node
EXPOSE 1337
ENTRYPOINT ["./docker-entrypoint.sh"]
