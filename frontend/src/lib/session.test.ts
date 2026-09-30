import { decodeToken, needsRefresh } from "./session";

function jwt(payload: object) {
  const b64 = (o: object) => Buffer.from(JSON.stringify(o)).toString("base64url");
  return `${b64({ alg: "HS256" })}.${b64(payload)}.sig`;
}

it("decodes claims including exp", () => {
  const t = jwt({ sub: "u1", orgId: "o1", role: "BUYER_ADMIN", exp: 2000 });
  expect(decodeToken(t)).toEqual({ userId: "u1", orgId: "o1", role: "BUYER_ADMIN", exp: 2000 });
});

it("returns null for garbage", () => {
  expect(decodeToken("nope")).toBeNull();
  expect(decodeToken(jwt({ sub: "u1" }))).toBeNull();
});

it("asks for a refresh when the token is missing, bad, or about to expire", () => {
  const soon = jwt({ sub: "u", orgId: "o", role: "BUYER_ADMIN", exp: 1010 });
  const later = jwt({ sub: "u", orgId: "o", role: "BUYER_ADMIN", exp: 1100 });
  expect(needsRefresh(undefined, 1000)).toBe(true);
  expect(needsRefresh("bad", 1000)).toBe(true);
  expect(needsRefresh(soon, 1000)).toBe(true);
  expect(needsRefresh(later, 1000)).toBe(false);
});
