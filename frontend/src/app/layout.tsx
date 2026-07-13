import type { Metadata } from "next";
import { Newsreader, Inter, Spline_Sans_Mono } from "next/font/google";
import { NextIntlClientProvider } from "next-intl";
import { getLocale } from "next-intl/server";
import "./globals.css";
import { Providers } from "./providers";

const newsreader = Newsreader({
  subsets: ["latin"],
  weight: ["400", "500", "600"],
  style: ["normal", "italic"],
  variable: "--font-display",
});
const inter = Inter({
  subsets: ["latin", "vietnamese"],
  variable: "--font-sans",
  // Inter has no CJK glyphs — explicit system-font fallback for Chinese text
  // instead of shipping a multi-MB CJK webfont.
  fallback: ["ui-sans-serif", "system-ui", "PingFang SC", "Microsoft YaHei", "Noto Sans SC", "sans-serif"],
});
const splineSansMono = Spline_Sans_Mono({
  subsets: ["latin"],
  weight: ["500", "600", "700"],
  variable: "--font-mono",
});

export const metadata: Metadata = {
  title: "Fintrack — Personal Finance Manager",
  description: "Track your income, expenses, budgets, and financial goals.",
};

export default async function RootLayout({ children }: { children: React.ReactNode }) {
  const locale = await getLocale();

  return (
    <html
      lang={locale}
      suppressHydrationWarning
      className={`${newsreader.variable} ${inter.variable} ${splineSansMono.variable}`}
    >
      <body className={inter.className}>
        <NextIntlClientProvider>
          <Providers>{children}</Providers>
        </NextIntlClientProvider>
      </body>
    </html>
  );
}
