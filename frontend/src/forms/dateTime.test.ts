import { describe, expect, it } from "vitest";
import { dateTimeInputToIsoZ, isoToDateTimeInput } from "./dateTime";

describe("dateTimeInputToIsoZ", () => {
  it("appends Z, adding seconds when the input has none", () => {
    expect(dateTimeInputToIsoZ("2026-03-05T10:15")).toBe("2026-03-05T10:15:00Z");
    expect(dateTimeInputToIsoZ("2026-03-05T10:15:30")).toBe("2026-03-05T10:15:30Z");
  });

  it("is undefined for an empty input", () => {
    expect(dateTimeInputToIsoZ(" ")).toBeUndefined();
  });

  it("round-trips a loaded whole-second instant unchanged", () => {
    const loaded = "2026-03-05T10:15:30Z";
    expect(dateTimeInputToIsoZ(isoToDateTimeInput(loaded))).toBe(loaded);
  });
});
