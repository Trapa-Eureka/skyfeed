// Content from the optional headless CMS (Strapi): operator notices and what is known about each sensor's site.
// The CMS is not part of the default stack. Everything here must behave well when it is absent or unreachable.

export interface Notice {
  id: string;
  title: string;
  body: string;
  severity: "info" | "caution";
}

export interface SensorSite {
  sensorId: string;
  name: string;
  location: string | null;
  notes: string | null;
}

/** off: no CMS is configured. ok: it answered. unavailable: it is configured but did not answer. */
export type CmsState = "off" | "ok" | "unavailable";

export interface CmsAnswer<T> {
  state: CmsState;
  items: T[];
}

const isObject = (value: unknown): value is Record<string, unknown> => typeof value === "object" && value !== null && !Array.isArray(value);
const text = (value: unknown): string | null => (typeof value === "string" && value.trim() !== "" ? value : null);

/** The entries of a Strapi collection answer ({ data: [...] }); anything else gives none. */
function entries(answer: unknown): Record<string, unknown>[] {
  return isObject(answer) && Array.isArray(answer.data) ? answer.data.filter(isObject) : [];
}

/** Notices from a Strapi answer. An entry without a title or a body is left out; an unknown severity counts as info. */
export function toNotices(answer: unknown): Notice[] {
  return entries(answer).flatMap((entry) => {
    const title = text(entry.title);
    const body = text(entry.body);
    const id = text(entry.documentId) ?? (typeof entry.id === "number" ? String(entry.id) : null);
    if (!title || !body || !id) return [];
    return [{ id, title, body, severity: entry.severity === "caution" ? "caution" : "info" }];
  });
}

/** Sensor sites from a Strapi answer. An entry without a sensor id or a name is left out. */
export function toSensorSites(answer: unknown): SensorSite[] {
  return entries(answer).flatMap((entry) => {
    const sensorId = text(entry.sensorId);
    const name = text(entry.name);
    if (!sensorId || !name) return [];
    return [{ sensorId, name, location: text(entry.location), notes: text(entry.notes) }];
  });
}

/** Reads what the console's own /api/cms/... route answered. Anything unexpected counts as "no CMS". */
export function readCmsAnswer<T>(value: unknown, isItem: (item: unknown) => item is T): CmsAnswer<T> {
  if (!isObject(value) || !Array.isArray(value.items)) return { state: "off", items: [] };
  const state: CmsState = value.state === "ok" || value.state === "unavailable" ? value.state : "off";
  return { state, items: state === "ok" ? value.items.filter(isItem) : [] };
}

export const isNotice = (item: unknown): item is Notice =>
  isObject(item) && typeof item.id === "string" && typeof item.title === "string" && typeof item.body === "string" && (item.severity === "info" || item.severity === "caution");

export const isSensorSite = (item: unknown): item is SensorSite => isObject(item) && typeof item.sensorId === "string" && typeof item.name === "string";
