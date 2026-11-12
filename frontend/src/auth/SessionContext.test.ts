import { describe, expect, it } from "vitest";
import { canEdit } from "./SessionContext";

describe("canEdit", () => {
  it("is true for EDITOR and ADMIN", () => {
    expect(canEdit(["EDITOR"])).toBe(true);
    expect(canEdit(["ADMIN"])).toBe(true);
    expect(canEdit(["VIEWER", "EDITOR"])).toBe(true);
  });

  it("is false for VIEWER alone or no roles", () => {
    expect(canEdit(["VIEWER"])).toBe(false);
    expect(canEdit([])).toBe(false);
  });
});
