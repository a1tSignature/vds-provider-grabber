/** Compares non-negative decimal strings without float rounding. Values with different scales can compare equal. */
export function compareDecimalStrings(a: string, b: string): -1 | 0 | 1 {
  const left = splitDecimal(a);
  const right = splitDecimal(b);

  if (left.integerPart.length !== right.integerPart.length) {
    return left.integerPart.length < right.integerPart.length ? -1 : 1;
  }
  if (left.integerPart !== right.integerPart) {
    return left.integerPart < right.integerPart ? -1 : 1;
  }

  const fractionLength = Math.max(left.fractionPart.length, right.fractionPart.length);
  const leftFraction = left.fractionPart.padEnd(fractionLength, "0");
  const rightFraction = right.fractionPart.padEnd(fractionLength, "0");
  if (leftFraction === rightFraction) return 0;
  return leftFraction < rightFraction ? -1 : 1;
}

function splitDecimal(value: string): { integerPart: string; fractionPart: string } {
  const [integerPart, fractionPart = ""] = value.split(".");
  const stripped = (integerPart ?? "").replace(/^0+(?=\d)/, "");
  return { integerPart: stripped, fractionPart };
}
