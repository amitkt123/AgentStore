import { edgeMidpoint, edgePath, isSideways } from "./geometry";

const a = { x: 0, y: 0, w: 100, h: 40 };

it("curves right between side-by-side boxes", () => {
  expect(edgePath(a, { x: 160, y: 0, w: 100, h: 40 })).toBe("M 100 20 C 130 20 130 20 160 20");
});

it("curves down when the next box is below", () => {
  expect(edgePath(a, { x: 0, y: 80, w: 100, h: 40 })).toBe("M 50 40 C 50 60 50 60 50 80");
});

it("finds the midpoint", () => {
  expect(edgeMidpoint(a, { x: 160, y: 0, w: 100, h: 40 })).toEqual({ x: 130, y: 20 });
});

it("tells a sideways edge from a downward one", () => {
  expect(isSideways(a, { x: 160, y: 0, w: 100, h: 40 })).toBe(true);
  expect(isSideways(a, { x: 0, y: 80, w: 100, h: 40 })).toBe(false);
});
