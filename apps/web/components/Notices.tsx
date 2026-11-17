import type { CmsState, Notice } from "@/lib/cms";

/**
 * Operator notices from the CMS, under the status bar. Nothing at all is shown when no CMS is configured; when one
 * is configured but does not answer, one quiet line says so, because a missing notice could matter.
 */
export function Notices({ state, notices }: { state: CmsState; notices: Notice[] }) {
  if (state === "off") return null;
  if (state === "unavailable") {
    return (
      <div className="notices" data-testid="notices" data-state="unavailable">
        <p className="notice-unavailable">Operator notices could not be loaded from the CMS.</p>
      </div>
    );
  }
  if (notices.length === 0) return null;
  return (
    <div className="notices" data-testid="notices" data-state="ok" role="region" aria-label="Operator notices">
      {notices.map((notice) => (
        <p key={notice.id} className="notice" data-testid="notice" data-severity={notice.severity}>
          <span className="notice-tag">{notice.severity === "caution" ? "Caution" : "Notice"}</span>
          <strong>{notice.title}</strong> {notice.body}
        </p>
      ))}
    </div>
  );
}
