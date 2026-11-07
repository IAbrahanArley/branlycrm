import type { NextConfig } from "next";

/**
 * The browser always calls the API on its own origin, under /api (ADR-015, ADR-022), so the
 * session cookie and the CSRF token stay first-party and no CORS is needed.
 *
 * - Compose (localhost:8088): Nginx routes /api to the backend before Next sees it.
 * - `npm run dev` (localhost:3000): this rewrite forwards /api to the Compose stack.
 * - Vercel: API_PROXY_TARGET points at the backend origin (for example https://api.<domain>).
 */
const apiProxyTarget =
  process.env.API_PROXY_TARGET ??
  (process.env.NODE_ENV === "development" ? "http://localhost:8088" : undefined);

const nextConfig: NextConfig = {
  output: "standalone",
  turbopack: { root: process.cwd() },
  async rewrites() {
    if (!apiProxyTarget) return [];
    return [{ source: "/api/:path*", destination: `${apiProxyTarget}/api/:path*` }];
  },
};

export default nextConfig;
