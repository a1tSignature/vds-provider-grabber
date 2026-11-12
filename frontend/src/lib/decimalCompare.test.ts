import { describe, expect, it } from "vitest";
import { compareDecimalStrings } from "./decimalCompare";

describe("compareDecimalStrings", () => {
  it("treats equal values at different scales as equal", () => {
    expect(compareDecimalStrings("4.5", "4.5000")).toBe(0);
    expect(compareDecimalStrings("4.5000", "4.5")).toBe(0);
  });

  it("compares by integer-part length before lexicographic order", () => {
    expect(compareDecimalStrings("10", "9.99")).toBe(1);
    expect(compareDecimalStrings("9.99", "10")).toBe(-1);
  });

  it("compares fractional digits at differing scales correctly", () => {
    expect(compareDecimalStrings("0.1", "0.10001")).toBe(-1);
    expect(compareDecimalStrings("0.10001", "0.1")).toBe(1);
  });

  it("is equal for identical values", () => {
    expect(compareDecimalStrings("100", "100")).toBe(0);
  });

  it("ignores leading zeros in the integer part", () => {
    expect(compareDecimalStrings("007", "7")).toBe(0);
    expect(compareDecimalStrings("0", "0.0")).toBe(0);
  });
});
