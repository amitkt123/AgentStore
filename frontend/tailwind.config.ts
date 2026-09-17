import type { Config } from "tailwindcss";

const config: Config = {
  content: ["./src/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        paper: "var(--paper)",
        paper2: "var(--paper-2)",
        ink: "var(--ink)",
        ink2: "var(--ink-2)",
        muted: "var(--muted)",
        line: "var(--line)",
        green: "var(--green)",
        greenSoft: "var(--green-soft)",
        amber: "var(--amber)",
        amberSoft: "var(--amber-soft)",
        red: "var(--red)",
        redSoft: "var(--red-soft)",
        doc: "var(--doc)",
      },
      fontFamily: {
        sans: ["IBM Plex Sans", "system-ui", "sans-serif"],
        serif: ["IBM Plex Serif", "Georgia", "serif"],
      },
    },
  },
};

export default config;
