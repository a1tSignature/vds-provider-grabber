site("partvps-com", "PartVPS", "body", "tr[data-monthly]", {
  "name": T("th span.font-mono"),
  "vcpuCount": T("td:nth-of-type(1)"), "ramMb": T("td:nth-of-type(2)"),
  "diskGb": T("td:nth-of-type(3)"), "diskType": V("UNKNOWN"),
  "ipv4Count": V(1), "virtualization": V("UNKNOWN"),
  "prices": [P("MONTHLY", "tr", "USD", attr="data-monthly")]})
site("qde-com", "QDE", "body", "div.rounded-xl:has(h2):has(span.text-4xl)", {
  "name": T("h2"),
  "vcpuCount": T("span.text-sm:contains(CPU)"), "ramMb": T("span.text-sm:contains(RAM)"),
  "diskGb": T("span.text-sm:contains(Storage)"), "diskType": DT("span.text-sm:contains(Storage)"),
  "trafficLimitGb": T("span.text-sm:contains(Bandwidth)", required=False),
  "ipv4Count": V(1), "virtualization": V("UNKNOWN"),
  "prices": [P("MONTHLY", "span.text-4xl", "EUR")]})
site("racknode-net", "RackNode", "section#vds-plans", "div.plan-card", {
  "externalId": A("a.btn", "href"), "name": T("div.plan-card-name"), "cpuModel": T("div.plan-card-cpu", required=False),
  "vcpuCount": T("li:contains(vCPU)"), "ramMb": T("li:contains(RAM)"),
  "diskGb": T("li:contains(NVMe), li:contains(SSD)"), "diskType": DT("li:contains(NVMe), li:contains(SSD)"),
  "trafficLimitGb": T("li:contains(Bandwidth)", required=False),
  "ipv4Count": V(0), "ipv6Supported": V("true"), "virtualization": V("UNKNOWN"),
  "prices": [P("MONTHLY", "div.plan-card-price", "$", grouping=",")]})
RW = "li:has(span.spec-label:containsOwn({})) span.spec-value"
site("rackworks-net", "RackWorks", "section#plans", "div.product-card:has(span.price-amount[data-monthly])", {
  "name": T("h3"),
  "vcpuCount": T(RW.format("CPU")), "ramMb": T(RW.format("RAM")),
  "diskGb": T(RW.format("Storage")), "diskType": DT(RW.format("Storage")),
  "ipv4Count": V(1), "ipv6Supported": V("true"), "virtualization": V("KVM"),
  "inStock": {"selector": "span.stock-badge", "text": True, "required": False, "values": {"Out of Stock": False, "In Stock": True}, "default": True},
  "prices": [P("MONTHLY", "span.price-amount", "USD", attr="data-monthly"),
             P("ANNUAL", "span.price-amount", "USD", attr="data-annual", required=False)]})
RH = "div.pricing-plans-detail-bold-text:contains({})"
site("rosehosting-com", "RoseHosting", "div:has(> div.vps-pricing-plan-inner)", "div.vps-pricing-plan-inner", {
  "name": T("div.vps-pricing-ssd"),
  "vcpuCount": T(RH.format("Cores")), "ramMb": T(RH.format("RAM")),
  "diskGb": T(RH.format("Disk")), "diskType": DT(RH.format("Disk")),
  "ipv4Count": V(1), "virtualization": V("UNKNOWN"),
  "prices": [P("MONTHLY", "p.vps-price-text", "$", grouping=",")]})
site("rumahweb-com", "Rumahweb", "section#pricing", "div[data-pricing-card]", {
  "name": T("div.mb-6 span"),
  "vcpuCount": T("span.font-semibold:contains(vCPU)"), "ramMb": T("span.font-semibold:contains(RAM)"),
  "diskGb": T("span.font-semibold:contains(SSD), span.font-semibold:contains(NVMe)"), "diskType": DT("span.font-semibold:contains(SSD), span.font-semibold:contains(NVMe)"),
  "ipv4Count": V(1), "virtualization": V("UNKNOWN"),
  "prices": [P("MONTHLY", "div.text-3xl", "Rp", grouping=".", decimal=",")]})
SL = "tr:has(td:containsOwn({})) td:nth-of-type(2)"
site("selectel-ru", "Selectel", "section.custom", "a.custom-card:has(td:containsOwn(vCPU))", {
  "name": T("h5"),
  "vcpuCount": T(SL.format("vCPU")), "ramMb": T(SL.format("RAM"), unit="GB"),
  "diskGb": T(SL.format("Диск"), unit="GB"), "diskType": V("UNKNOWN"),
  "ipv4Count": V(1), "virtualization": V("KVM"),
  "prices": [P("MONTHLY", "div.custom-card__price p", "RUB", grouping=" ")]}, complete=False)
site("server-unesty-net", "Unesty", "section#product", "div.item.vps", {
  "name": T("h2"),
  "vcpuCount": T("li:contains(Core)"), "ramMb": T("li:contains(RAM)"),
  "diskGb": T("li:contains(NVMe), li:contains(SSD)"), "diskType": DT("li:contains(NVMe), li:contains(SSD)"),
  "ipv4Count": T("li:contains(IPv4)"), "virtualization": V("KVM"),
  "inStock": {"selector": "h4", "text": True, "required": False, "values": {"Sold out": False}, "default": True},
  "prices": [P("MONTHLY", "h1", "€", grouping=",")]})
site("servereasy-it", "ServerEasy", "body", "tr:has(td.server-price)", {
  "externalId": A("a.order-button", "href"), "name": T("td:nth-of-type(1)"),
  "vcpuCount": T("td:nth-of-type(2)"), "ramMb": T("td:nth-of-type(3)"),
  "diskGb": T("td:nth-of-type(4)"), "diskType": V("UNKNOWN"),
  "ipv4Count": V(1), "virtualization": V("UNKNOWN"),
  "prices": [P("MONTHLY", "td.server-price", "€", grouping=",")]})
site("serverpoint-com", "ServerPoint", "body", "div.plan-card[data-price]", {
  "disambiguateNames": True, "name": T("span.font-bold"),
  "vcpuCount": T("li:contains(vCPU)"), "ramMb": A("div.plan-card", "data-ram", unit="GB"),
  "diskGb": A("div.plan-card", "data-disk"), "diskType": V("UNKNOWN"),
  "trafficLimitGb": A("div.plan-card", "data-bandwidth", required=False),
  "ipv4Count": V(1), "virtualization": V("UNKNOWN"),
  "prices": [P("MONTHLY", "span.price-linux", "$", grouping=",")]})
SK = "div.row:has(p:containsOwn({})) p:last-child"
site("skillhost-pl", "SkillHost", "div.products_box", "div.boxer_product", {
  "externalId": A("a.btn_primary", "href"), "name": T("div.boxer_product_head h3"),
  "vcpuCount": T(SK.format("CPU")), "ramMb": T(SK.format("RAM")),
  "diskGb": T(SK.format("Dysk")), "diskType": DT("div.row:has(p:containsOwn(Dysk)) p:first-child"),
  "ipv4Count": V(1), "virtualization": V("UNKNOWN"),
  "prices": [P("MONTHLY", "p[data-currency-value]", "PLN", attr="data-currency-value")]})
TS = "> div:nth-of-type(1) > div:containsOwn({})"
site("theserverhost-com", "TheServerHost", "div#pricing", "div:has(> h3):has(> a[href*=cart.php])", {
  "externalId": A("> a", "href"), "name": T("> h3"),
  "vcpuCount": T(TS.format("Core")), "ramMb": T(TS.format("RAM")),
  "diskGb": T(TS.format("Disk")), "diskType": V("UNKNOWN"),
  "trafficLimitGb": T(TS.format("Bandwidth"), required=False),
  "ipv4Count": V(1), "virtualization": V("UNKNOWN"),
  "prices": [P("MONTHLY", "> div:nth-of-type(2)", "$", grouping=",")]})
TN = "span[role=cell]:contains({})"
site("tier-net", "Tier.Net", "body", "div.pt__option__item-2", {
  "name": T("h2"),
  "vcpuCount": T(TN.format("CPU:")), "ramMb": T(TN.format("RAM:")),
  "diskGb": T(TN.format("Storage:")), "diskType": DT(TN.format("Storage:")),
  "trafficLimitGb": T(TN.format("Bandwidth:"), required=False),
  "ipv4Count": T(TN.format("IPs:")), "ipv6Supported": V("true"), "virtualization": V("UNKNOWN"),
  "prices": [P("MONTHLY", "h4", "$", grouping=",")]})
