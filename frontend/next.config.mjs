/** @type {import('next').NextConfig} */
const nextConfig = {
  output: "standalone",
  async redirects() {
    return [
      {
        source: "/dashboard/analytics",
        destination: "/dashboard",
        permanent: true,
      },
      {
        source: "/dashboard/accounts",
        destination: "/dashboard",
        permanent: true,
      },
      {
        source: "/dashboard/recurring",
        destination: "/dashboard/transactions?tab=recurring",
        permanent: true,
      },
    ];
  },
};

export default nextConfig;
