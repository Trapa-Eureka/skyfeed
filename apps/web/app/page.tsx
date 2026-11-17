import { Console } from "@/components/Console";
import type { ConsoleConfig } from "@/lib/types";

// The settings are read from the environment for every request, so one image runs anywhere.
export const dynamic = "force-dynamic";

const seconds = (name: string, fallback: number) => {
  const value = Number(process.env[name]);
  return Number.isFinite(value) && value > 0 ? value : fallback;
};

export default function Page() {
  const config: ConsoleConfig = {
    wsUrl: process.env.GATEWAY_WS_URL ?? "ws://localhost:8082/ws/v1",
    mapStyleUrl: process.env.MAP_STYLE_URL ?? "https://tiles.openfreemap.org/styles/dark",
    aircraftStaleSeconds: seconds("AIRCRAFT_STALE_SECONDS", 30),
    aircraftExpireSeconds: seconds("AIRCRAFT_EXPIRE_SECONDS", 120),
    sensorDegradedSeconds: seconds("SENSOR_DEGRADED_SECONDS", 10),
    sensorOfflineSeconds: seconds("SENSOR_OFFLINE_SECONDS", 30),
  };
  return <Console config={config} />;
}
