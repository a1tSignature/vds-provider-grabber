import { useRef, useState } from "react";
import { createPortal } from "react-dom";
import { countryFlag, countryName } from "../domain/countries";

export function CountryFlag({ code }: { code: string }) {
  const flagRef = useRef<HTMLSpanElement>(null);
  const [tooltip, setTooltip] = useState<{ x: number; y: number }>();
  const flag = countryFlag(code);
  const name = countryName(code);
  function showTooltip() {
    const rect = flagRef.current?.getBoundingClientRect();
    if (rect !== undefined) setTooltip({ x: rect.left + rect.width / 2, y: rect.top });
  }
  return (
    <>
      <span
        ref={flagRef}
        className="country-flag"
        role="img"
        aria-label={name}
        title={name}
        tabIndex={0}
        onMouseEnter={showTooltip}
        onMouseLeave={() => setTooltip(undefined)}
        onFocus={showTooltip}
        onBlur={() => setTooltip(undefined)}
      >
        {flag ?? code.toUpperCase()}
      </span>
      {tooltip !== undefined && createPortal(
        <span className="country-tooltip" role="tooltip" style={{ left: tooltip.x, top: tooltip.y }}>
          {name}
        </span>,
        document.body,
      )}
    </>
  );
}
