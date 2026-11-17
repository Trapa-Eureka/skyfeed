# CMS (optional)

A [Strapi](https://strapi.io) 5 headless CMS that holds two kinds of content for the SkyFeed console:

| Content type | Fields | Where the console shows it |
|---|---|---|
| **Notice** | title, body, severity (`info` or `caution`) | a strip under the status bar |
| **Sensor site** | sensorId, name, location, notes | above the charts of the selected sensor |

It is not part of the default stack, and the console works the same without it (R-CMS-02).

## Run

```bash
pnpm cms:up      # builds the image if needed and starts the stack with the CMS
pnpm cms:check   # checks the CMS and the console's use of it
pnpm cms:down    # stops the CMS; the rest keeps running
```

- Admin panel: `http://localhost:1337/admin`. The first visit asks you to create the administrator account; that account exists only in your local data volume.
- An empty CMS is given example content on its first start: five made-up sensor sites and one notice.
- Content, the database (SQLite) and the generated keys live in the Docker volume `skyfeed_cms-data`.

## What it costs (measured on the development machine)

| Step | Peak memory | Time |
|---|---|---|
| Installing dependencies | 334 MB | 85 s |
| Building the admin panel | about 1.9 GB | 30 s |
| Running | 85 to 220 MB | starts in 4 s |

The build is the heavy part, and it fails if Node's heap is limited to 1280 MB or less. A Docker with 3 GB of memory cannot spare 1.9 GB beside the running stack, so `pnpm cms:up` stops the stack, builds, and starts everything again. That is why the CMS is opt-in and not built by `pnpm verify`.

## Who may do what

- Anyone who can reach the port may **read** notices and sensor sites (the public role is given `find` and `findOne` on the two types in `src/index.ts`).
- Changing content needs a signed-in administrator. Requests to create, change or delete without signing in are refused; `pnpm cms:check` tries.
- The browser never talks to the CMS. The console's server fetches the content (`apps/web/app/api/cms`) and passes on only the fields it shows.
- The port is published on `127.0.0.1` only. The keys Strapi signs sessions with are generated on the first start and kept on the data volume, not in the repository or the image.
- Usage statistics that Strapi would send to its makers are switched off.
