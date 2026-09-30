export type IconName = "home" | "store" | "runs" | "files" | "search" | "bell" | "check" | "x" | "upload" | "lock";

const paths: Record<IconName, string> = {
  home: "M3 11l9-8 9 8M5 10v10h5v-6h4v6h5V10",
  store: "M4 9l1-5h14l1 5M4 9v11h16V9M4 9h16M9 20v-6h6v6",
  runs: "M5 4v16l14-8z",
  files: "M6 3h8l4 4v14H6zM14 3v4h4",
  search: "M11 4a7 7 0 100 14 7 7 0 000-14zM21 21l-5-5",
  bell: "M6 16V11a6 6 0 1112 0v5l2 2H4zM10 20a2 2 0 004 0",
  check: "M5 12l5 5 9-10",
  x: "M6 6l12 12M18 6L6 18",
  upload: "M12 16V4M7 9l5-5 5 5M4 20h16",
  lock: "M6 11h12v9H6zM8 11V8a4 4 0 118 0v3",
};

export function Icon({ name }: { name: IconName }) {
  return (
    <svg aria-hidden="true" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
      <path d={paths[name]} />
    </svg>
  );
}
