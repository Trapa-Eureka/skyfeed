"use client";

import { type GeoJSONSource, Map as MapLibreMap, Marker, NavigationControl, setWorkerUrl, type StyleSpecification } from "maplibre-gl";
import { useEffect, useRef, useState } from "react";
import { ageSeconds, formatAge, formatAltitude, sensorStatus } from "@/lib/age";
import type { AdsbState, Area, ConsoleConfig, Sensor } from "@/lib/types";

interface Props {
  config: ConsoleConfig;
  aircraft: AdsbState[];
  sensors: Sensor[];
  now: number | null;
  selectedAircraft: string | null;
  selectedSensor: string | null;
  onAreaChange: (area: Area) => void;
  onSelectAircraft: (icao24: string | null) => void;
  onSelectSensor: (sensorId: string | null) => void;
}

/** The centre of the recorded area (central Philippines). */
const START = { center: [122, 12.5] as [number, number], zoom: 5.6 };

/** A map with no outside data: a dark background and a line every 5 degrees. Used when the style is "none". */
function gridStyle(): StyleSpecification {
  const lines: { type: "Feature"; properties: Record<string, never>; geometry: { type: "LineString"; coordinates: [number, number][] } }[] = [];
  for (let lon = -180; lon <= 180; lon += 5) lines.push({ type: "Feature", properties: {}, geometry: { type: "LineString", coordinates: [[lon, -85], [lon, 85]] } });
  for (let lat = -85; lat <= 85; lat += 5) lines.push({ type: "Feature", properties: {}, geometry: { type: "LineString", coordinates: [[-180, lat], [180, lat]] } });
  return {
    version: 8,
    sources: { grid: { type: "geojson", data: { type: "FeatureCollection", features: lines } } },
    layers: [
      { id: "background", type: "background", paint: { "background-color": "#12161a" } },
      { id: "grid", type: "line", source: "grid", paint: { "line-color": "#2c3238", "line-width": 1 } },
    ],
  };
}

const clamp = (value: number, min: number, max: number) => Math.min(max, Math.max(min, value));

const AIRCRAFT_ICON =
  '<svg class="ac-icon" viewBox="-12 -12 24 24" width="24" height="24" aria-hidden="true"><path d="M0,-10 L6,8 L0,4 L-6,8 Z"/></svg>';

function buildAircraftElement(): HTMLDivElement {
  const element = document.createElement("div");
  element.className = "ac";
  element.setAttribute("role", "button");
  element.tabIndex = 0;
  element.innerHTML = `${AIRCRAFT_ICON}<div class="ac-label"><span class="ac-callsign"></span><span class="ac-alt"></span><span class="ac-age"></span><span class="ac-stale">STALE</span></div>`;
  return element;
}

function buildSensorElement(sensorId: string): HTMLDivElement {
  const element = document.createElement("div");
  element.className = "sensor-marker";
  element.setAttribute("role", "button");
  element.tabIndex = 0;
  element.dataset.sensorId = sensorId;
  element.innerHTML = `<span class="sensor-shape" aria-hidden="true"></span><span class="sensor-name"></span>`;
  element.querySelector(".sensor-name")!.textContent = sensorId;
  return element;
}

function onActivate(element: HTMLElement, action: () => void) {
  element.addEventListener("click", (event) => {
    event.stopPropagation();
    action();
  });
  element.addEventListener("keydown", (event) => {
    if (event.key === "Enter" || event.key === " ") {
      event.preventDefault();
      action();
    }
  });
}

/**
 * The map: every aircraft at its last reported position with heading, callsign and altitude (R-UI-01), each
 * with the age of its data and a STALE mark when that age passes the limit (R-UI-02). Aircraft and sensors are
 * ordinary page elements placed on the map, so they can be read by keyboard, screen reader and tests alike.
 */
export function MapView({ config, aircraft, sensors, now, selectedAircraft, selectedSensor, onAreaChange, onSelectAircraft, onSelectSensor }: Props) {
  const container = useRef<HTMLDivElement>(null);
  const map = useRef<MapLibreMap | null>(null);
  const aircraftMarkers = useRef(new globalThis.Map<string, Marker>());
  const sensorMarkers = useRef(new globalThis.Map<string, Marker>());
  const [ready, setReady] = useState(false);
  const [mapError, setMapError] = useState<string | null>(null);
  // The latest callbacks, so the map (created once) never calls a stale one.
  const handlers = useRef({ onAreaChange, onSelectAircraft, onSelectSensor });
  handlers.current = { onAreaChange, onSelectAircraft, onSelectSensor };
  const selection = useRef({ selectedAircraft, selectedSensor });
  selection.current = { selectedAircraft, selectedSensor };

  useEffect(() => {
    if (!container.current) return;
    let instance: MapLibreMap;
    try {
      // The worker file is served by this app itself (copied by scripts/copy-map-worker.mjs).
      setWorkerUrl("/maplibre/maplibre-gl-worker.mjs");
      instance = new MapLibreMap({
        container: container.current,
        style: config.mapStyleUrl === "none" ? gridStyle() : config.mapStyleUrl,
        center: START.center,
        zoom: START.zoom,
        attributionControl: { compact: true },
      });
    } catch (error) {
      setMapError(error instanceof Error ? error.message : "the map could not be started");
      return;
    }
    map.current = instance;
    instance.addControl(new NavigationControl({ showCompass: false }), "top-left");

    const reportArea = () => {
      const bounds = instance.getBounds();
      const area = {
        lamin: clamp(bounds.getSouth(), -90, 90),
        lomin: clamp(bounds.getWest(), -180, 180),
        lamax: clamp(bounds.getNorth(), -90, 90),
        lomax: clamp(bounds.getEast(), -180, 180),
      };
      if (area.lamin < area.lamax && area.lomin < area.lomax) handlers.current.onAreaChange(area);
    };
    instance.on("load", () => {
      instance.addSource("track", { type: "geojson", data: { type: "FeatureCollection", features: [] } });
      instance.addLayer({ id: "track", type: "line", source: "track", paint: { "line-color": "#3987e5", "line-width": 2, "line-opacity": 0.9 } });
      setReady(true);
      reportArea();
    });
    instance.on("moveend", reportArea);
    instance.on("click", () => handlers.current.onSelectAircraft(null));
    // The background map failing to load (no network) must not take the picture down: aircraft are still drawn.
    instance.on("error", () => setMapError("The background map could not be loaded. Aircraft and sensors are still shown."));

    const markers = aircraftMarkers.current;
    const sensorsOnMap = sensorMarkers.current;
    return () => {
      markers.clear();
      sensorsOnMap.clear();
      map.current = null;
      setReady(false);
      instance.remove();
    };
  }, [config.mapStyleUrl]);

  // Aircraft: add, move, relabel and remove markers so they match the picture. Runs every second (now changes).
  useEffect(() => {
    const instance = map.current;
    if (!instance || now === null) return;
    const markers = aircraftMarkers.current;
    const present = new Set<string>();
    for (const state of aircraft) {
      present.add(state.icao24);
      let marker = markers.get(state.icao24);
      if (!marker) {
        const element = buildAircraftElement();
        element.dataset.icao24 = state.icao24;
        onActivate(element, () => handlers.current.onSelectAircraft(selection.current.selectedAircraft === state.icao24 ? null : state.icao24));
        marker = new Marker({ element, anchor: "center" }).setLngLat([state.lon, state.lat]).addTo(instance);
        markers.set(state.icao24, marker);
      } else {
        marker.setLngLat([state.lon, state.lat]);
      }
      const element = marker.getElement();
      const age = ageSeconds(state.lastContact, now);
      const stale = age >= config.aircraftStaleSeconds;
      const name = state.callsign ?? state.icao24;
      const altitude = formatAltitude(state.baroAltitudeM ?? state.geoAltitudeM, state.onGround);
      element.dataset.stale = String(stale);
      element.dataset.selected = String(selectedAircraft === state.icao24);
      element.dataset.heading = state.headingDeg === null ? "" : String(Math.round(state.headingDeg));
      element.dataset.age = String(age);
      (element.querySelector(".ac-icon") as SVGElement).style.transform = `rotate(${state.headingDeg ?? 0}deg)`;
      element.classList.toggle("ac-no-heading", state.headingDeg === null);
      element.querySelector(".ac-callsign")!.textContent = name;
      element.querySelector(".ac-alt")!.textContent = altitude;
      element.querySelector(".ac-age")!.textContent = formatAge(age);
      element.setAttribute(
        "aria-label",
        `${name}, ${altitude}, ${state.headingDeg === null ? "heading unknown" : `heading ${Math.round(state.headingDeg)} degrees`}, updated ${formatAge(age)} ago${stale ? ", stale" : ""}`,
      );
    }
    for (const [icao24, marker] of markers) {
      if (!present.has(icao24)) {
        marker.remove();
        markers.delete(icao24);
      }
    }
  }, [aircraft, now, ready, selectedAircraft, config.aircraftStaleSeconds]);

  // Sensors.
  useEffect(() => {
    const instance = map.current;
    if (!instance || now === null) return;
    const markers = sensorMarkers.current;
    for (const sensor of sensors) {
      let marker = markers.get(sensor.sensorId);
      if (!marker) {
        const element = buildSensorElement(sensor.sensorId);
        onActivate(element, () => handlers.current.onSelectSensor(selection.current.selectedSensor === sensor.sensorId ? null : sensor.sensorId));
        marker = new Marker({ element, anchor: "center" }).setLngLat([sensor.lon, sensor.lat]).addTo(instance);
        markers.set(sensor.sensorId, marker);
      }
      const age = ageSeconds(sensor.latest.ts, now);
      const status = sensorStatus(age, config.sensorDegradedSeconds, config.sensorOfflineSeconds);
      const element = marker.getElement();
      element.dataset.status = status;
      element.dataset.selected = String(selectedSensor === sensor.sensorId);
      element.setAttribute("aria-label", `Sensor ${sensor.sensorId}, ${status}, last reading ${formatAge(age)} ago`);
    }
  }, [sensors, now, ready, selectedSensor, config.sensorDegradedSeconds, config.sensorOfflineSeconds]);

  // Track of the selected aircraft: stored positions from the REST API, then extended by the live position.
  const track = useRef<{ icao24: string; points: [number, number][] } | null>(null);
  useEffect(() => {
    const instance = map.current;
    if (!instance || !ready) return;
    const source = instance.getSource("track") as GeoJSONSource | undefined;
    const draw = (points: [number, number][]) =>
      source?.setData({
        type: "FeatureCollection",
        features: points.length > 1 ? [{ type: "Feature", properties: {}, geometry: { type: "LineString", coordinates: points } }] : [],
      });
    if (!selectedAircraft) {
      track.current = null;
      draw([]);
      return;
    }
    let cancelled = false;
    track.current = { icao24: selectedAircraft, points: [] };
    draw([]);
    fetch(`/api/v1/aircraft/${selectedAircraft}/track`, { cache: "no-store" })
      .then((response) => (response.ok ? response.json() : []))
      .then((points: { lat: number; lon: number }[]) => {
        if (cancelled || track.current?.icao24 !== selectedAircraft) return;
        track.current.points = points.map((point) => [point.lon, point.lat]);
        draw(track.current.points);
      })
      .catch(() => {});
    return () => {
      cancelled = true;
    };
  }, [selectedAircraft, ready]);

  useEffect(() => {
    const current = track.current;
    const instance = map.current;
    if (!current || !instance || !ready) return;
    const state = aircraft.find((a) => a.icao24 === current.icao24);
    if (!state) return;
    const last = current.points[current.points.length - 1];
    if (last && last[0] === state.lon && last[1] === state.lat) return;
    current.points = [...current.points, [state.lon, state.lat]];
    (instance.getSource("track") as GeoJSONSource | undefined)?.setData({
      type: "FeatureCollection",
      features: current.points.length > 1 ? [{ type: "Feature", properties: {}, geometry: { type: "LineString", coordinates: current.points } }] : [],
    });
  }, [aircraft, ready]);

  return (
    <div className="map-wrap">
      <div ref={container} className="map" data-testid="map" data-ready={ready} />
      {mapError && (
        <p className="map-note" role="status">
          {mapError}
        </p>
      )}
    </div>
  );
}
