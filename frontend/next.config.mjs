/** @type {import('next').NextConfig} */
const nextConfig = {
  // Server actions default to a 1 MB body; the PAN image can be 5 MB.
  experimental: { serverActions: { bodySizeLimit: "6mb" } },
};

export default nextConfig;
