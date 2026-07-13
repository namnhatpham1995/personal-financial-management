import createNextIntlPlugin from "next-intl/plugin";

const withNextIntl = createNextIntlPlugin("./src/i18n/request.ts");

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

export default withNextIntl(nextConfig);
