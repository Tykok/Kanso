import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // Produces a self-contained server bundle, so the runtime image needs neither
  // node_modules nor a package manager.
  output: "standalone",
};

export default nextConfig;
