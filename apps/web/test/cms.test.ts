import { describe, expect, it } from "vitest";
import { isNotice, isSensorSite, readCmsAnswer, toNotices, toSensorSites } from "../lib/cms";

// The shape Strapi 5 answers with for a collection.
const strapi = (data: unknown[]) => ({ data, meta: { pagination: { page: 1, pageSize: 25, pageCount: 1, total: data.length } } });

describe("content from the optional CMS", () => {
  it("R-CMS-01 notices are taken from the CMS answer as entered", () => {
    const notices = toNotices(strapi([
      { id: 1, documentId: "abc", title: "Demo system", body: "Readings are simulated.", severity: "info", createdAt: "2026-11-17T11:54:15.151Z" },
      { id: 2, documentId: "def", title: "Runway work", body: "Expect gaps near Manila.", severity: "caution" },
    ]));
    expect(notices).toEqual([
      { id: "abc", title: "Demo system", body: "Readings are simulated.", severity: "info" },
      { id: "def", title: "Runway work", body: "Expect gaps near Manila.", severity: "caution" },
    ]);
  });

  it("R-CMS-01 sensor sites are taken from the CMS answer, with empty fields as unknown", () => {
    const sites = toSensorSites(strapi([
      { id: 1, sensorId: "gs-iloilo", name: "Iloilo ground station", location: "Cabatuan, Iloilo", notes: "Unattended site." },
      { id: 2, sensorId: "gs-cebu", name: "Cebu ground station", location: null, notes: "" },
    ]));
    expect(sites).toEqual([
      { sensorId: "gs-iloilo", name: "Iloilo ground station", location: "Cabatuan, Iloilo", notes: "Unattended site." },
      { sensorId: "gs-cebu", name: "Cebu ground station", location: null, notes: null },
    ]);
  });

  it("R-CMS-01 an entry that is incomplete is left out, and an unknown severity is shown as an ordinary notice", () => {
    expect(toNotices(strapi([{ id: 1, title: "No body" }, { id: 2, body: "No title" }, "junk", null]))).toEqual([]);
    expect(toNotices(strapi([{ id: 7, title: "T", body: "B", severity: "critical!" }]))).toEqual([{ id: "7", title: "T", body: "B", severity: "info" }]);
    expect(toSensorSites(strapi([{ id: 1, name: "No sensor id" }, { id: 2, sensorId: "gs-a" }]))).toEqual([]);
  });

  it("R-CMS-02 an answer that is not a CMS answer gives nothing instead of failing", () => {
    for (const answer of [null, "error page", [], {}, { data: "nope" }, { error: { status: 500 } }]) {
      expect(toNotices(answer)).toEqual([]);
      expect(toSensorSites(answer)).toEqual([]);
    }
  });

  it("R-CMS-02 without a CMS, or with one that does not answer, the console gets no items", () => {
    const notice = { id: "abc", title: "T", body: "B", severity: "info" };
    expect(readCmsAnswer({ state: "off", items: [] }, isNotice)).toEqual({ state: "off", items: [] });
    expect(readCmsAnswer({ state: "unavailable", items: [notice] }, isNotice)).toEqual({ state: "unavailable", items: [] });
    expect(readCmsAnswer({ state: "ok", items: [notice, { title: "broken" }] }, isNotice)).toEqual({ state: "ok", items: [notice] });
    expect(readCmsAnswer("<html>502</html>", isNotice)).toEqual({ state: "off", items: [] });
    expect(readCmsAnswer({ state: "something new", items: [notice] }, isNotice)).toEqual({ state: "off", items: [] });
    expect(readCmsAnswer({ state: "ok", items: [{ sensorId: "gs-a", name: "A" }, { sensorId: 5 }] }, isSensorSite).items).toHaveLength(1);
  });
});
