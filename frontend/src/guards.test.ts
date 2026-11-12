import { readdirSync, readFileSync } from "node:fs";
import { join, relative } from "node:path";
import { describe, expect, it } from "vitest";

/**
 * One repo-wide convention, enforced by regex instead of review discipline. Scans every source
 * file under `src/` and fails on any match outside the rule's whitelist.
 *
 * To add a rule: push a `Rule` below and, if some files are a deliberate exception (like
 * `tokens.css` for the hex-colour rule), list their `src/`-relative path in `whitelist`.
 */
interface Rule {
  name: string;
  pattern: RegExp;
  /** `src/`-relative paths exempt from this rule. */
  whitelist?: string[];
}

/**
 * Every value of a backend `domain.*` enum (`src/main/kotlin/org/a1tsign/vds/domain/Enums.kt`)
 * that the frontend can receive as catalog/business data. Deliberately excludes `Role`
 * (`ADMIN`/`EDITOR`/`VIEWER`): there is no `/api/v1/dictionaries` entry for it, so
 * `auth/SessionContext.tsx`'s `canEdit` comparing against those two names directly is not "an enum
 * value smuggled in instead of reading the dictionary" the way a catalog enum would be -- do not
 * add `Role`'s values here.
 */
const BACKEND_ENUM_LITERALS = [
  "ACTIVE", "INACTIVE", "UNKNOWN", "CANDIDATE", "CONFIGURED", "REJECTED",
  "DISABLED",
  "MANUAL", "CRAWLER", "WHOIS", "PROVIDER_SITE",
  "HIGH", "MEDIUM", "LOW", "UNVERIFIED",
  "VISA_MASTERCARD", "AMERICAN_EXPRESS", "UNIONPAY", "MIR", "SBP", "RU_BANK_TRANSFER",
  "BANK_WIRE", "PAYPAL", "ALIPAY", "WECHAT_PAY", "APPLE_PAY", "GOOGLE_PAY",
  "CRYPTO_BTC", "CRYPTO_ETH", "CRYPTO_USDT", "CRYPTO_OTHER", "WEBMONEY", "PERFECT_MONEY", "CASH", "OTHER",
  "NVME", "SSD", "HDD",
  "KVM", "OPENVZ", "LXC", "XEN", "HYPERV",
  "HOURLY", "MONTHLY", "QUARTERLY", "SEMIANNUAL", "ANNUAL", "BIENNIAL", "TRIENNIAL",
];

const RULES: Rule[] = [
  {
    name: "no `any` type",
    pattern: /:\s*any\b|\bas\s+any\b|<\s*any\s*>|\bany\[\]/,
  },
  {
    name: "no literal hex colour outside tokens.css",
    pattern: /#[0-9a-fA-F]{3,8}(?![0-9a-fA-F])/,
    whitelist: ["styles/tokens.css"],
  },
  {
    name: "no absolute URL pointing at the API",
    pattern: /localhost|:8080|https?:\/\/[^\s"'`]*\/api\//,
  },
  {
    name: "no dangerouslySetInnerHTML",
    pattern: /dangerouslySetInnerHTML/,
  },
  {
    name: "no backend enum literal outside the whitelisted modules",
    pattern: new RegExp(`(['"\`])(?:${BACKEND_ENUM_LITERALS.join("|")})\\1`),
    whitelist: ["api/types.ts", "domain/systemStatusLabels.ts", "domain/paymentMethods.tsx"],
  },
  {
    name: "no parseFloat/Number(",
    pattern: /parseFloat|Number\(/,
  },
];

const SRC_DIR = import.meta.dirname;
const SCANNED_EXTENSIONS = [".ts", ".tsx", ".css"];

/** Source files under `src/`, excluding this file's own siblings (`*.test.ts(x)`). */
function listSourceFiles(dir: string): string[] {
  const files: string[] = [];
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) {
      files.push(...listSourceFiles(path));
      continue;
    }
    if (entry.name.endsWith(".test.ts") || entry.name.endsWith(".test.tsx")) {
      continue;
    }
    if (SCANNED_EXTENSIONS.some((ext) => entry.name.endsWith(ext))) {
      files.push(path);
    }
  }
  return files;
}

function lineOf(content: string, index: number): number {
  return content.slice(0, index).split("\n").length;
}

describe("source guards", () => {
  for (const rule of RULES) {
    it(`repo is clean: ${rule.name}`, () => {
      const violations: string[] = [];
      for (const file of listSourceFiles(SRC_DIR)) {
        const relativePath = relative(SRC_DIR, file);
        if (rule.whitelist?.includes(relativePath)) {
          continue;
        }
        const content = readFileSync(file, "utf-8");
        const match = rule.pattern.exec(content);
        if (match !== null) {
          violations.push(`${relativePath}:${lineOf(content, match.index)}`);
        }
      }
      expect(violations, `rule "${rule.name}" violated at: ${violations.join(", ")}`).toEqual([]);
    });
  }

  // Proves each matcher actually fires, so a rule that silently stopped matching (a typo in the
  // regex, an overly narrow whitelist check) fails loudly instead of just never finding anything.
  it.each([
    ["no `any` type", "const value: any = 1;"],
    ["no literal hex colour outside tokens.css", "const colour = '#ff00ff';"],
    ["no absolute URL pointing at the API", "fetch('http://localhost:8080/api/v1/providers')"],
    ["no dangerouslySetInnerHTML", "<div dangerouslySetInnerHTML={{ __html: value }} />"],
    ["no backend enum literal outside the whitelisted modules", "const period = \"MONTHLY\";"],
    ["no parseFloat/Number(", "const amount = Number(raw);"],
  ])("%s: fires on a planted violation", (ruleName, violatingSnippet) => {
    const rule = RULES.find((candidate) => candidate.name === ruleName);
    if (rule === undefined) {
      throw new Error(`no such rule: ${ruleName}`);
    }
    expect(rule.pattern.test(violatingSnippet)).toBe(true);
  });

  it("the `any` rule does not flag the word 'any' used outside a type position", () => {
    const rule = RULES[0];
    expect(rule?.pattern.test('const label: string = "any device";')).toBe(false);
  });
});
