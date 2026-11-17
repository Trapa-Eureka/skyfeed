import type { Metadata, Viewport } from "next";
import type { ReactNode } from "react";
import "maplibre-gl/dist/maplibre-gl.css";
import "./globals.css";

export const metadata: Metadata = {
  title: "SkyFeed console",
  description: "Live aircraft map and sensor status. A demo: not for navigation or any operational use.",
};

export const viewport: Viewport = { colorScheme: "dark", themeColor: "#1a1a19" };

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
