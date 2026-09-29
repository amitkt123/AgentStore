import "./globals.css";
import { Instrument_Sans } from "next/font/google";
import { GeistMono } from "geist/font/mono";

const sans = Instrument_Sans({ subsets: ["latin"], variable: "--font-instrument", display: "swap" });

export const metadata = { title: "Karya" };

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en" className={`${sans.variable} ${GeistMono.variable}`}>
      <body>{children}</body>
    </html>
  );
}
