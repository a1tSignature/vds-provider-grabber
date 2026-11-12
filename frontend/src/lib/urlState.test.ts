import { describe, expect, it } from "vitest";
import {
  enumField,
  intField,
  parseUrlState,
  repeatableEnumField,
  serializeUrlState,
  sortField,
  stringField,
} from "./urlState";

const COLOURS = ["AMBER", "TEAL", "SLATE"] as const;
const SORTABLE = ["name", "createdAt"] as const;

const schema = {
  q: stringField(),
  colour: enumField(COLOURS, "AMBER"),
  tags: repeatableEnumField(COLOURS),
  page: intField(0, 0),
  sort: sortField(SORTABLE),
};

describe("url state round-trip", () => {
  it("round-trips a fully populated state", () => {
    const state = {
      q: "ovh",
      colour: "TEAL" as const,
      tags: ["TEAL", "SLATE"] as (typeof COLOURS)[number][],
      page: 3,
      sort: { field: "name" as const, descending: true },
    };
    const search = serializeUrlState(schema, state);
    expect(parseUrlState(schema, search)).toEqual(state);
  });

  it("omits every field at its default", () => {
    const search = serializeUrlState(schema, {
      q: "",
      colour: "AMBER",
      tags: [],
      page: 0,
      sort: undefined,
    });
    expect(search).toBe("");
  });

  it("falls back to defaults for unknown or invalid values", () => {
    const state = parseUrlState(schema, "colour=BOGUS&page=-5&sort=unknownField,asc&tags=NOT_A_TAG");
    expect(state.colour).toBe("AMBER");
    expect(state.page).toBe(0);
    expect(state.sort).toBeUndefined();
    expect(state.tags).toEqual([]);
  });

  it("preserves repeatable order and drops duplicates", () => {
    const state = parseUrlState(schema, "tags=TEAL&tags=SLATE&tags=TEAL");
    expect(state.tags).toEqual(["TEAL", "SLATE"]);
  });

  it("round-trips through a leading '?'", () => {
    const search = serializeUrlState(schema, { q: "x", colour: "AMBER", tags: [], page: 0, sort: undefined });
    expect(parseUrlState(schema, `?${search}`).q).toBe("x");
  });
});
