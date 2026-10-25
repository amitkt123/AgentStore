import { render, screen } from "@testing-library/react";
import { vi } from "vitest";

vi.mock("next/navigation", () => ({ usePathname: vi.fn() }));
vi.mock("./Topbar", () => ({ Topbar: () => null }));
vi.mock("./Rail", () => ({ Rail: () => null }));
import { usePathname } from "next/navigation";
import { AppShell } from "./AppShell";

const shell = () => render(<AppShell items={[]} orgName="Acme" fullName="A" email="a@x">page</AppShell>);

it("lets a run page use the full width", () => {
  vi.mocked(usePathname).mockReturnValue("/runs/R-10");
  shell();
  expect(screen.getByRole("main").className).toMatch(/wide/);
});

it("keeps other pages capped", () => {
  vi.mocked(usePathname).mockReturnValue("/runs");
  shell();
  expect(screen.getByRole("main").className).not.toMatch(/wide/);
});
