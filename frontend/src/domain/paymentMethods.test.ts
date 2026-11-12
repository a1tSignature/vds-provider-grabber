import { describe, expect, it } from "vitest";
import { paymentMethodLabel } from "./paymentMethods";

const BACKEND_PAYMENT_METHODS = [
  "VISA_MASTERCARD", "AMERICAN_EXPRESS", "UNIONPAY", "MIR", "SBP", "RU_BANK_TRANSFER",
  "BANK_WIRE", "PAYPAL", "ALIPAY", "WECHAT_PAY", "APPLE_PAY", "GOOGLE_PAY",
  "CRYPTO_BTC", "CRYPTO_ETH", "CRYPTO_USDT", "CRYPTO_OTHER", "WEBMONEY", "PERFECT_MONEY", "CASH", "OTHER",
];

describe("paymentMethodLabel", () => {
  it("has a human name for every backend payment method", () => {
    for (const method of BACKEND_PAYMENT_METHODS) expect(paymentMethodLabel(method)).not.toBe(method);
  });

  it("falls back to the raw code for a method it does not know", () => {
    expect(paymentMethodLabel("NEW_METHOD")).toBe("NEW_METHOD");
  });
});
