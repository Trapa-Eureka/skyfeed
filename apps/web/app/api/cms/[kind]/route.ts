// Passes the two kinds of CMS content on to the browser, already reduced to what the console shows. The browser
// never talks to the CMS itself. Without CMS_URL the CMS is simply "off", which is the default.
import { toNotices, toSensorSites, type CmsAnswer } from "@/lib/cms";

export const dynamic = "force-dynamic";

const KINDS = {
  notices: { path: "/api/notices?sort=createdAt:desc&pagination[pageSize]=20", map: toNotices },
  "sensor-sites": { path: "/api/sensor-sites?sort=sensorId&pagination[pageSize]=100", map: toSensorSites },
} as const;

const answer = (body: CmsAnswer<unknown>) => Response.json(body, { headers: { "cache-control": "no-store" } });

export async function GET(_request: Request, context: { params: Promise<{ kind: string }> }) {
  const { kind } = await context.params;
  if (!Object.hasOwn(KINDS, kind)) return Response.json({ title: "Not Found", status: 404 }, { status: 404 });
  const base = process.env.CMS_URL;
  if (!base) return answer({ state: "off", items: [] });

  const { path, map } = KINDS[kind as keyof typeof KINDS];
  try {
    const response = await fetch(`${base}${path}`, { cache: "no-store", signal: AbortSignal.timeout(3000) });
    if (!response.ok) return answer({ state: "unavailable", items: [] });
    return answer({ state: "ok", items: map(await response.json()) });
  } catch {
    return answer({ state: "unavailable", items: [] });
  }
}
