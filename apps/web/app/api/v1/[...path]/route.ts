// Passes GET /api/v1/... on to the api service, so the browser talks to one origin and the api service
// needs no CORS rules. Only this fixed upstream, only GET, only simple path segments.
const upstream = () => process.env.API_URL ?? "http://localhost:8080";
const SEGMENT = /^[A-Za-z0-9._-]{1,64}$/;

export const dynamic = "force-dynamic";

export async function GET(request: Request, context: { params: Promise<{ path: string[] }> }) {
  const { path } = await context.params;
  if (path.length > 6 || !path.every((segment) => SEGMENT.test(segment) && segment !== "." && segment !== "..")) {
    return Response.json({ title: "Bad Request", status: 400, detail: "unsupported path" }, { status: 400 });
  }
  const target = `${upstream()}/api/v1/${path.join("/")}${new URL(request.url).search}`;
  try {
    const response = await fetch(target, { cache: "no-store", signal: AbortSignal.timeout(10_000) });
    return new Response(response.body, {
      status: response.status,
      headers: { "content-type": response.headers.get("content-type") ?? "application/json", "cache-control": "no-store" },
    });
  } catch {
    return Response.json({ title: "Bad Gateway", status: 502, detail: "the api service did not answer" }, { status: 502 });
  }
}
