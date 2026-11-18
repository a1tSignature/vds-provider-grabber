import type { ReactNode } from "react";

/** Unknown payment codes render as generic chips with the raw code. Keep known brand labels and icons here. */
interface MethodView {
  label: string;
  background: string;
  outlined?: boolean;
  glyph: ReactNode;
}

function Text({ children, size, fill, italic }: { children: string; size: number; fill?: string; italic?: boolean }) {
  return (
    <text
      x="14"
      y="9.6"
      textAnchor="middle"
      dominantBaseline="central"
      fontSize={size}
      fontStyle={italic ? "italic" : undefined}
      style={fill === undefined ? undefined : { fill }}
    >
      {children}
    </text>
  );
}

const INK = "var(--pm-ink)";

const BANK = (
  <g style={{ fill: INK }}>
    <polygon points="14,2.8 21.5,6.6 6.5,6.6" />
    <rect x="8" y="7.6" width="2.4" height="5" />
    <rect x="12.8" y="7.6" width="2.4" height="5" />
    <rect x="17.6" y="7.6" width="2.4" height="5" />
    <rect x="6.5" y="13.4" width="15" height="1.8" />
  </g>
);

const METHODS: Record<string, MethodView> = {
  VISA_MASTERCARD: {
    label: "Visa / Mastercard",
    background: "var(--pm-card-bg)",
    glyph: (
      <>
        <circle cx="11.6" cy="9" r="5" style={{ fill: "var(--pm-mc-red)" }} />
        <circle cx="16.4" cy="9" r="5" style={{ fill: "var(--pm-mc-orange)", fillOpacity: 0.9 }} />
      </>
    ),
  },
  AMERICAN_EXPRESS: { label: "American Express", background: "var(--pm-amex)", glyph: <Text size={6.4}>AMEX</Text> },
  UNIONPAY: {
    label: "UnionPay",
    background: "var(--pm-light-bg)",
    outlined: true,
    glyph: (
      <>
        <polygon points="6.5,3.5 12,3.5 10,14.5 4.5,14.5" style={{ fill: "var(--pm-up-red)" }} />
        <polygon points="11.8,3.5 17.3,3.5 15.3,14.5 9.8,14.5" style={{ fill: "var(--pm-up-blue)" }} />
        <polygon points="17.1,3.5 22.6,3.5 20.6,14.5 15.1,14.5" style={{ fill: "var(--pm-up-teal)" }} />
      </>
    ),
  },
  MIR: { label: "Мир", background: "var(--pm-mir)", glyph: <Text size={7.4}>МИР</Text> },
  SBP: { label: "СБП", background: "var(--pm-sbp)", glyph: <Text size={7.4}>СБП</Text> },
  RU_BANK_TRANSFER: { label: "Банковский перевод (РФ)", background: "var(--pm-rub)", glyph: <Text size={13}>₽</Text> },
  BANK_WIRE: { label: "Банковский перевод (SWIFT)", background: "var(--pm-wire)", glyph: BANK },
  PAYPAL: {
    label: "PayPal",
    background: "var(--pm-paypal)",
    glyph: (
      <>
        <g transform="translate(1.4 0.8)">
          <Text size={12.5} italic fill="var(--pm-paypal-light)">
            P
          </Text>
        </g>
        <Text size={12.5} italic>
          P
        </Text>
      </>
    ),
  },
  ALIPAY: { label: "Alipay", background: "var(--pm-alipay)", glyph: <Text size={11.5}>支</Text> },
  WECHAT_PAY: {
    label: "WeChat Pay",
    background: "var(--pm-wechat)",
    glyph: (
      <>
        <ellipse cx="11.6" cy="8" rx="5" ry="4" style={{ fill: INK }} />
        <ellipse
          cx="17"
          cy="10.6"
          rx="4.2"
          ry="3.4"
          style={{ fill: INK, stroke: "var(--pm-wechat)", strokeWidth: 0.9 }}
        />
      </>
    ),
  },
  APPLE_PAY: {
    label: "Apple Pay",
    background: "var(--pm-apple)",
    glyph: (
      <>
        <circle cx="7.6" cy="10" r="3.2" style={{ fill: INK }} />
        <ellipse cx="8.6" cy="5.4" rx="0.9" ry="1.6" transform="rotate(35 8.6 5.4)" style={{ fill: INK }} />
        <g transform="translate(4.6 0)">
          <Text size={7}>Pay</Text>
        </g>
      </>
    ),
  },
  GOOGLE_PAY: {
    label: "Google Pay",
    background: "var(--pm-light-bg)",
    outlined: true,
    glyph: (
      <Text size={12.5} fill="var(--pm-google)">
        G
      </Text>
    ),
  },
  CRYPTO_BTC: { label: "Bitcoin", background: "var(--pm-btc)", glyph: <Text size={13}>₿</Text> },
  CRYPTO_ETH: {
    label: "Ethereum",
    background: "var(--pm-eth)",
    glyph: (
      <g style={{ fill: INK }}>
        <polygon points="14,2.4 18.4,9 14,11.6 9.6,9" />
        <polygon points="14,12.6 18.4,10 14,15.8 9.6,10" />
      </g>
    ),
  },
  CRYPTO_USDT: { label: "USDT", background: "var(--pm-usdt)", glyph: <Text size={13}>₮</Text> },
  CRYPTO_OTHER: {
    label: "Другая криптовалюта",
    background: "var(--pm-neutral)",
    glyph: (
      <g style={{ fill: "none", stroke: INK, strokeWidth: 1.4 }}>
        <circle cx="14" cy="9" r="5.4" />
        <circle cx="14" cy="9" r="2.4" />
      </g>
    ),
  },
  WEBMONEY: { label: "WebMoney", background: "var(--pm-webmoney)", glyph: <Text size={8}>WM</Text> },
  PERFECT_MONEY: { label: "Perfect Money", background: "var(--pm-perfect)", glyph: <Text size={8}>PM</Text> },
  CASH: {
    label: "Наличные",
    background: "var(--pm-cash)",
    glyph: (
      <g style={{ fill: "none", stroke: INK, strokeWidth: 1.3 }}>
        <rect x="5" y="4.6" width="18" height="8.8" rx="1" />
        <circle cx="14" cy="9" r="2.2" />
      </g>
    ),
  },
  OTHER: {
    label: "Другое",
    background: "var(--pm-neutral)",
    glyph: (
      <g style={{ fill: INK }}>
        <circle cx="9" cy="9" r="1.6" />
        <circle cx="14" cy="9" r="1.6" />
        <circle cx="19" cy="9" r="1.6" />
      </g>
    ),
  },
};

export function paymentMethodLabel(method: string): string {
  return METHODS[method]?.label ?? method;
}

/** Set `decorative` when a visible label already names the icon for assistive technology. */
export function PaymentMethodIcon({ method, decorative = false }: { method: string; decorative?: boolean }) {
  const view = METHODS[method];
  const label = view?.label ?? method;
  return (
    <svg
      className="pm-icon"
      viewBox="0 0 28 18"
      role={decorative ? undefined : "img"}
      aria-hidden={decorative ? true : undefined}
      aria-label={decorative ? undefined : label}
    >
      {!decorative && <title>{label}</title>}
      <rect
        x="0.5"
        y="0.5"
        width="27"
        height="17"
        rx="3"
        style={{
          fill: view?.background ?? "var(--pm-neutral)",
          stroke: view?.outlined ? "var(--line2)" : "none",
        }}
      />
      {view?.glyph ?? <Text size={9}>?</Text>}
    </svg>
  );
}
