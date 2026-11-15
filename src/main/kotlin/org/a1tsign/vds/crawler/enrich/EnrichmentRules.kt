package org.a1tsign.vds.crawler.enrich

import java.util.regex.Pattern

/*
 * Dictionaries, weights and patterns of the site enrichment heuristics.
 *
 * Every pattern here runs on third-party text of up to half a million characters, under
 * `-Xss512k`, and a running regex ignores coroutine cancellation. So no pattern puts an unbounded
 * quantifier inside a repeated group: name tokens are bounded and possessive
 * (`[\w&'.\-]{0,40}+`, never `[\w…]*`), whitespace runs are bounded (`\s{1,5}`), and every
 * repeated group has a fixed upper count. A bounded possessive token cannot backtrack, so each
 * start position costs a constant amount of work and a whole scan stays linear.
 */

/** `\w`, `\b` and case folding cover Cyrillic. */
internal fun rx(pattern: String, ignoreCase: Boolean = false): Pattern =
    Pattern.compile(pattern, Pattern.UNICODE_CHARACTER_CLASS or (if (ignoreCase) Pattern.CASE_INSENSITIVE else 0))

/** One word of an entity name; the separators that may follow it are outside the class. */
private const val NAME_TOKEN = "[\\w&'.\\-]{0,40}+"
private const val QUOTED_NAME_TOKEN = "[\\w&'.\\-»\"]{0,40}+"

internal object EnrichmentRules {


    /** URL/anchor hints: kind -> (pattern on the lower-cased path or anchor, weight). */
    val LINK_HINTS: Map<String, Pair<Pattern, Int>> = linkedMapOf(
        "pay" to (rx("payment|billing|pay-?methods|how-to-pay|оплат|способы") to 6),
        "faq" to (rx("\\bfaq|help|support|knowledge|вопрос") to 2),
        "legal" to (
            rx(
                "legal|terms|tos\\b|imprint|impressum|about|company|contact|privacy|aup|policy|реквизит|о-компании|оферт|договор|контакт",
            ) to 4
            ),
        "loc" to (
            rx("location|datacenter|data-center|data-centre|network|looking-?glass|regions|availability|дата-?центр|локаци|сеть") to 5
            ),
    )

    val SKIP_PATH: Pattern = rx(
        "\\.(?:pdf|jpe?g|png|gif|svg|webp|zip|gz|mp4|css|js|xml|ico)$|/blog/|/news/|/tag/|/author/|/tutorials?/|/community/|/docs/" +
            "|/careers?|/jobs?|/login|/signup|/register|/cart|/checkout|/account|/wp-admin|/feed|/qa/",
        ignoreCase = true,
    )

    val LEGAL_PAGE_BONUS: Pattern = rx(
        "terms-of-service|terms-and-conditions|/terms/?$|/tos/?$|imprint|impressum|legal-notice|/about|реквизит|/contact|оферт",
        ignoreCase = true,
    )
    const val LEGAL_PAGE_BONUS_WEIGHT = 3.0

    /** Dated or superseded legal pages. */
    val OUTDATED_PAGE: Pattern = rx("prior-versions|-agreement-\\w{1,40}+-20\\d\\d|20\\d\\d-\\d\\d")
    const val OUTDATED_PAGE_PENALTY = 6.0
    const val DEPTH_PENALTY_PER_SLASH = 0.3

    private val LOCALES = (
        "en ru de fr es it nl pl pt tr uk zh ja ko cs fi sv da no lt lv et ro bg sr kk uz hy ka ar he hu sk el id vi th ms"
        ).split(" ")
    val LOCALE_SEGMENT: Pattern =
        rx("^/((?:${LOCALES.joinToString("|")})(?:[-_][a-z]{2,4})?|asia|world|latam)(?=/|$)", ignoreCase = true)

    /** A per-locale dictionary chunk such as `ru.f11cd859.js`, matched on the lower-cased file name. */
    val LOCALE_CHUNK: Pattern = rx("^(?:${LOCALES.joinToString("|")})\\.\\w{1,64}+\\.js$")
    const val PRIMARY_LOCALE_CHUNK_BONUS = 10
    val CHUNK_NAME_HINT: Pattern = rx("layout|footer|index|server|pricing|tariff")
    const val CHUNK_NAME_HINT_WEIGHT = 2


    val ISO2: Set<String> = (
        "ad ae af ag ai al am ao aq ar as at au aw ax az ba bb bd be bf bg bh bi bj bl bm bn bo bq br bs bt bv bw by bz ca cc " +
            "cd cf cg ch ci ck cl cm cn co cr cu cv cw cx cy cz de dj dk dm do dz ec ee eg eh er es et fi fj fk fm fo fr ga gb gd " +
            "ge gf gg gh gi gl gm gn gp gq gr gs gt gu gw gy hk hm hn hr ht hu id ie il im in io iq ir is it je jm jo jp ke kg kh " +
            "ki km kn kp kr kw ky kz la lb lc li lk lr ls lt lu lv ly ma mc md me mf mg mh mk ml mm mn mo mp mq mr ms mt mu mv mw " +
            "mx my mz na nc ne nf ng ni nl no np nr nu nz om pa pe pf pg ph pk pl pm pn pr ps pt pw py qa re ro rs ru rw sa sb sc " +
            "sd se sg sh si sj sk sl sm sn so sr ss st sv sx sy sz tc td tf tg th tj tk tl tm tn to tr tt tv tw tz ua ug um us uy " +
            "uz va vc ve vg vi vn vu wf ws ye yt za zm zw"
        ).split(" ").map { it.uppercase() }.toSet()

    /** An uppercase ISO alpha-2 code, with `UK` read as `GB`; `null` for anything else. */
    fun isoCountry(code: String): String? {
        val upper = code.trim().uppercase()
        if (upper == "UK") return "GB"
        return upper.takeIf { it in ISO2 }
    }


    /** [attribute] runs on lower-cased attributes, [text] on visible text; `null` never matches. */
    class PaymentRule(val code: String, val attribute: Pattern?, val text: Pattern)

    val PAYMENT_RULES: List<PaymentRule> = listOf(
        payment(
            "VISA_MASTERCARD",
            "visa|master-?card|maestro|\\bmc[-_]?logo|cc-visa|credit-?card",
            "\\bvisa\\b|mastercard|master card|maestro|credit card|debit card|кредитн\\w{1,30} карт|банковск\\w{1,30} карт" +
                "|карт\\w{0,30} любого банка|оплат\\w{1,30} картой",
        ),
        payment("AMERICAN_EXPRESS", "amex|american-?express", "american express|\\bamex\\b"),
        payment("UNIONPAY", "union-?pay", "unionpay|union pay"),
        payment(
            "MIR",
            "(?:^|[/_\\-\\s])mir(?:[_\\-.]|pay|$)|mir-?logo|nspk",
            "\\bМИР\\b|карт\\w{0,30} «?Мир»?|платёжн\\w{1,30} систем\\w{1,30} «?Мир",
            caseSensitiveText = true,
        ),
        payment("SBP", "(?:^|[/_\\-\\s])sbp(?:[_\\-.]|$)", "\\bСБП\\b|[Сс]истем\\w{1,30} быстрых платежей", caseSensitiveText = true),
        payment("RU_BANK_TRANSFER", null, "безналичн\\w{1,30}|по сч[её]ту|банковск\\w{1,30} перевод|для юридическ\\w{1,30} лиц"),
        payment(
            "BANK_WIRE",
            "wire-?transfer|bank-?transfer|\\bswift\\b",
            "wire transfer|bank transfer|bank wire|\\bswift\\b|\\biban\\b|\\bsepa\\b",
        ),
        payment("PAYPAL", "paypal", "paypal"),
        payment("ALIPAY", "alipay", "alipay"),
        payment("WECHAT_PAY", "wechat", "wechat ?pay"),
        payment("APPLE_PAY", "apple-?pay", "apple pay"),
        payment("GOOGLE_PAY", "google-?pay|gpay", "google pay"),
        payment("CRYPTO_BTC", "bitcoin|(?:^|[/_\\-\\s])btc(?:[_\\-.]|$)", "[Bb]itcoin|биткоин|\\bBTC\\b", caseSensitiveText = true),
        payment("CRYPTO_ETH", "ethereum|(?:^|[/_\\-\\s])eth(?:[_\\-.]|$)", "[Ee]thereum|\\bETH\\b|\\bERC-?20\\b", caseSensitiveText = true),
        payment("CRYPTO_USDT", "usdt|tether", "\\bUSDT\\b|[Tt]ether\\b", caseSensitiveText = true),
        payment(
            "CRYPTO_OTHER",
            "litecoin|monero|(?:^|[/_\\-\\s])(?:ltc|xmr|trx|doge)(?:[_\\-.]|$)|crypto",
            "litecoin|monero|dogecoin|cryptocurrenc\\w{0,10}|криптовалют\\w{0,10}|\\bcrypto\\b|\\bTRC-?20\\b",
        ),
        payment("WEBMONEY", "webmoney", "webmoney"),
        payment("PERFECT_MONEY", "perfect-?money", "perfect money"),
        payment(
            "CASH",
            null,
            "pay(?:ing|ment)? (?:in|with|by) cash|cash payments?\\b(?! value)|наличными|наличн\\w{1,30} расч[её]т",
        ),
        payment("OTHER", "qiwi|yoomoney|payeer", "\\bQIWI\\b|ЮMoney|YooMoney|Payeer|ЮKassa|ЮКасса"),
    )

    private fun payment(code: String, attribute: String?, text: String, caseSensitiveText: Boolean = false) =
        PaymentRule(code, attribute?.let { rx(it, ignoreCase = true) }, rx(text, ignoreCase = !caseSensitiveText))

    /** Payment-gateway script hosts: (pattern on a script/link URL, codes, label). */
    val GATEWAYS: List<Triple<Pattern, List<String>, String>> = listOf(
        Triple(rx("js\\.stripe\\.com|stripe\\.com/v3|stripe\\.network", true), listOf("VISA_MASTERCARD"), "Stripe"),
        Triple(rx("paypal\\.com/sdk|paypalobjects", true), listOf("PAYPAL"), "PayPal SDK"),
        Triple(rx("braintree", true), listOf("VISA_MASTERCARD", "PAYPAL"), "Braintree"),
        Triple(rx("cryptomus|coinpayments|commerce\\.coinbase|btcpay|nowpayments|plisio|coingate", true), listOf("CRYPTO_OTHER"), "crypto gateway"),
        Triple(rx("yookassa|cloudpayments|tinkoff|robokassa|paymaster\\.ru|unitpay", true), listOf("VISA_MASTERCARD"), "RU card gateway"),
        Triple(rx("adyen|checkout\\.com|2checkout|paddle\\.com", true), listOf("VISA_MASTERCARD"), "card gateway"),
    )

    /** Applied to a snippet around a payment mention: a negated mention weighs nothing. */
    val NEGATION: Pattern = rx(
        "(?:not|n't|no longer|never)\\s{1,5}(?:\\w{1,30}+\\s{1,5}){0,3}(?:accept|support|offer)|не\\s{1,5}принима|не\\s{1,5}поддержива",
        ignoreCase = true,
    )


    val FLAG: Pattern = rx(
        "(?:\\bfi-|flag-icon-|\\bflag[-_]|/flags?/(?:[\\w-]{1,60}+/)?|\\bflag-)([a-z]{2})(?=\\b|[._\\-\"'])",
        ignoreCase = true,
    )

    /** (ISO code, city name, pattern). A Cyrillic entry is a stem and may be followed by an ending. */
    val CITIES: List<Triple<String, String, Pattern>> = linkedMapOf(
        "US" to listOf(
            "Los Angeles", "New York", "Newark", "Secaucus", "Las Vegas", "Dallas", "Chicago", "Seattle", "Miami", "Atlanta", "Ashburn",
            "San Jose", "Silicon Valley", "San Francisco", "Phoenix", "Denver", "Salt Lake City", "Kansas City", "Buffalo", "Piscataway",
            "Hillsboro", "Vint Hill", "Лос-Анджелес", "Нью-Йорк", "Майами", "Даллас", "Чикаго", "Нью-Джерси",
        ),
        "CA" to listOf("Toronto", "Montreal", "Vancouver", "Beauharnois", "Торонто", "Монреал"),
        "GB" to listOf("London", "Manchester", "Erith", "Лондон"),
        "NL" to listOf("Amsterdam", "Амстердам"),
        "DE" to listOf("Frankfurt", "Falkenstein", "Nuremberg", "Limburg", "Erfurt", "Франкфурт", "Нюрнберг", "Фалькенштейн"),
        "FR" to listOf("Paris", "Gravelines", "Roubaix", "Strasbourg", "Париж"),
        "FI" to listOf("Helsinki", "Хельсинки"),
        "SE" to listOf("Stockholm", "Стокгольм"),
        "PL" to listOf("Warsaw", "Варшав"),
        "ES" to listOf("Madrid", "Мадрид"),
        "IT" to listOf("Milan"),
        "CH" to listOf("Zurich", "Цюрих"),
        "AT" to listOf("Vienna"),
        "CZ" to listOf("Prague", "Праг"),
        "RO" to listOf("Bucharest", "Бухарест"),
        "BG" to listOf("Sofia"),
        "LV" to listOf("Riga"),
        "EE" to listOf("Tallinn", "Таллин"),
        "LT" to listOf("Vilnius", "Вильнюс"),
        "RU" to listOf(
            "Moscow", "Saint Petersburg", "St. Petersburg", "Novosibirsk", "Москв", "Санкт-Петербург", "Новосибирск", "Екатеринбург",
            "Казан", "Краснодар", "Владивосток", "Хабаровск", "Ростов-на-Дону", "Нижн* Новгород",
        ),
        "UA" to listOf("Kyiv", "Kiev"),
        "TR" to listOf("Istanbul", "Стамбул"),
        "IL" to listOf("Tel Aviv"),
        "AE" to listOf("Dubai", "Дуба"),
        "IN" to listOf("Mumbai", "Bangalore", "Bengaluru", "New Delhi", "Chennai", "Noida"),
        "SG" to listOf("Singapore", "Сингапур"),
        "HK" to listOf("Hong Kong", "Гонконг"),
        "JP" to listOf("Tokyo", "Osaka", "Токио"),
        "KR" to listOf("Seoul"),
        "AU" to listOf("Sydney", "Melbourne"),
        "BR" to listOf("Sao Paulo", "São Paulo"),
        "ZA" to listOf("Johannesburg"),
        "LU" to listOf("Luxembourg"),
        "KZ" to listOf("Almaty", "Алмат", "Астан"),
        "UZ" to listOf("Tashkent", "Ташкент"),
        "ID" to listOf("Jakarta"),
        "MY" to listOf("Kuala Lumpur"),
        "TW" to listOf("Taipei"),
        "MX" to listOf("Mexico City", "Queretaro"),
        "CL" to listOf("Santiago"),
        "NO" to listOf("Oslo"),
        "DK" to listOf("Copenhagen"),
        "IE" to listOf("Dublin"),
        "BE" to listOf("Brussels"),
        "PT" to listOf("Lisbon"),
        "GR" to listOf("Athens"),
        "RS" to listOf("Belgrade", "Белград"),
        "MD" to listOf("Chisinau", "Кишин"),
        "AM" to listOf("Yerevan", "Ереван"),
        "GE" to listOf("Tbilisi", "Тбилиси"),
    ).flatMap { (code, names) -> names.map { name -> Triple(code, name.replace("*", ""), cityPattern(name)) } }

    private fun cityPattern(name: String): Pattern {
        val cyrillic = name.any { it in 'а'..'я' || it in 'А'..'Я' }
        return if (cyrillic) {
            // "Нижн* Новгород": the stem takes any ending, as the city names do.
            rx("(?<![\\w-])" + name.split("*").joinToString("\\w{1,10}+") { Pattern.quote(it) } + "\\w{0,20}+")
        } else {
            rx("(?<![\\w-])" + Pattern.quote(name) + "(?!\\w)")
        }
    }

    /** The CPU generation, not the city. */
    val MILAN_CPU: Pattern = rx("EPYC|Milan[- ]?\\d|7\\d{2}3")

    /** Country names count as server locations only on location-type pages. */
    val COUNTRY_NAMES: Map<String, Pattern> = linkedMapOf(
        "DE" to "Germany|Германи", "NL" to "Netherlands|Нидерланд|Голланди", "FI" to "Finland|Финлянди", "SE" to "Sweden|Швеци",
        "PL" to "Poland|Польш", "FR" to "France|Франци", "GB" to "United Kingdom|Великобритани|Англи[ия]",
        "US" to "United States|\\bUSA\\b|\\bСША\\b", "RU" to "Russia\\b|Росси[ия]", "KZ" to "Kazakhstan|Казахстан",
        "TR" to "Turkey|Türkiye|Турци", "JP" to "Japan|Япони", "LV" to "Latvia|Латви", "EE" to "Estonia|Эстони",
        "LT" to "Lithuania|Литв", "MD" to "Moldova|Молдов", "AM" to "Armenia|Армени", "CH" to "Switzerland|Швейцари",
        "AT" to "Austria|Австри", "CZ" to "Czech|Чехи", "BG" to "Bulgaria|Болгари", "RO" to "Romania|Румыни",
        "RS" to "Serbia|Серби", "IT" to "Italy|Итали", "ES" to "Spain|Испани", "CA" to "Canada|Канад", "IN" to "\\bIndia\\b|Инди[ия]",
        "IL" to "Israel|Израил", "AU" to "Australia|Австрали", "BR" to "Brazil|Бразили", "LU" to "Люксембург", "AE" to "\\bUAE\\b|\\bОАЭ\\b",
    ).mapValues { (_, names) -> rx("(?<![\\w-])(?:$names)") }

    /** A country-name mention in legal, sanctions or citizenship context is not a location. */
    val NOT_A_LOCATION_CONTEXT: Pattern = rx(
        "закон|\\blaws?\\b|гражданин|citizen|резидент|санкц|sanction|OFAC|за пределами|outside|регламент|GDPR|Protection Act",
        ignoreCase = true,
    )

    val SERVERS_LOCATED_IN: Pattern = rx(
        "(?:servers?|data ?cent(?:er|re)s?|infrastructure|hardware)\\s{1,5}(?:are|is)\\s{1,5}(?:physically\\s{1,5})?" +
            "(?:located|hosted|based)\\s{1,5}in" +
            "|(?:сервер\\w{0,10}|дата-?центр\\w{0,10}|оборудовани\\w{0,10})\\s{1,5}(?:расположен\\w{0,10}|находятся|размещен\\w{0,10})\\s{1,5}в",
        ignoreCase = true,
    )

    /** Inline-script data such as `{name: "Germany", code: "DE"}`. */
    val JS_COUNTRY_OBJECT: Pattern = rx(
        "[{,]\\s{0,10}[\"']?(?:name|title|label)[\"']?\\s{0,10}:\\s{0,10}[\"']([^\"'\\n]{2,40})[\"']\\s{0,10},\\s{0,10}" +
            "[\"']?(?:code|countryCode|country_code|iso|iso2|country)[\"']?\\s{0,10}:\\s{0,10}[\"']([A-Z]{2})[\"']",
    )

    /** A mention inside an address is the company's office, not a server location. */
    val ADDRESS_CONTEXT: Pattern = rx(
        "Registration Number|Registered (?:office|address)|Street|\\bSt\\.|Suite \\d|\\bRoad\\b|\\bAve(?:nue)?\\b|P\\.?O\\.? Box|" +
            "Building \\d|Business Park|Floor|юридическ\\w{1,10} адрес|\\bул\\.|офис|ИНН|ОГРН",
        ignoreCase = true,
    )


    /**
     * Legal forms. The upper-case spellings let an ALL-CAPS entity such as
     * `HOSTINGER INTERNATIONAL LIMITED` (common in terms of service) be recognised.
     */
    private const val LEGAL_FORMS =
        "Inc|LLC|L\\.L\\.C|Ltd|Limited|GmbH|AG|B\\.V|BV|S\\.A\\.S|SAS|S\\.A|SRL|s\\.r\\.o|OÜ|Oy|AB|Pte\\.? Ltd|Pvt\\.? Ltd" +
            "|Private Limited|Corp|Corporation|LLP|PLC|Sp\\. z o\\.o|LP|FZCO|FZE|FZ-LLC|DMCC|Incorporated" +
            "|INC|LTD|LIMITED|GMBH|PTE\\.? LTD|PVT\\.? LTD|PRIVATE LIMITED|CORP|CORPORATION|INCORPORATED"

    val COMPANY: Pattern = rx("(?<![\\w&'.\\-])((?:[A-Z]$NAME_TOKEN\\s){0,5}[A-Z]$NAME_TOKEN,?\\s(?:$LEGAL_FORMS)\\b\\.?)")

    val HAS_LEGAL_FORM: Pattern = rx("\\b(?:$LEGAL_FORMS)\\b")

    val RU_COMPANY: Pattern = rx(
        "((?:ООО|АО|ПАО|ЗАО|ТОО|ИП)\\s{1,5}[«\"“][^»\"”]{2,60}[»\"”]" +
            "|Индивидуальный предприниматель\\s{1,5}[А-ЯЁ][а-яё]{1,30}+(?:\\s{1,5}[А-ЯЁ][а-яё.]{1,30}+){1,2})",
    )

    val COPYRIGHT: Pattern = rx(
        "(?:©|\\(c\\)|Copyright)\\s{0,5}(?:©\\s{0,5})?" +
            "(?:(?:19|20)\\d{2}\\s{0,5}(?:[-–—]\\s{0,5}(?:(?:19|20)\\d{2}|present|now))?\\s{0,5}[,.]?\\s{0,5})?" +
            "([A-ZА-ЯЁ]$NAME_TOKEN(?:[ ,][A-ZА-ЯЁ\"«]$QUOTED_NAME_TOKEN){0,5}(?:,?\\s(?:$LEGAL_FORMS)\\b\\.?)?)",
    )

    val NOT_A_COPYRIGHT_HOLDER: Pattern = rx("(?:All|Все|Copyright|Powered)\\b")

    val OPERATED_BY: Pattern = rx(
        "(?:operated|owned|provided|run|managed) by\\s{1,5}([A-Z]$NAME_TOKEN(?:\\s[A-Z]$NAME_TOKEN){0,5}(?:,?\\s(?:$LEGAL_FORMS)\\b\\.?)?)",
    )

    /** Cut-offs of a raw entity name: a following sentence, "All rights reserved". */
    val NAME_TAIL: Pattern = rx("\\.\\s{1,5}(?=[A-ZА-ЯЁ])|\\s{1,5}All [Rr]ights|\\s{1,5}Все права")
    val NAME_PREFIX: Pattern = rx("^(?:Copyright|All rights reserved|The)\\s{1,5}")

    /** Legal-form suffixes stripped from a comparison key, so Ltd ≡ Limited, Inc ≡ Incorporated, … */
    val FORM_SUFFIX: Pattern = rx("(?:incorporated|inc|llc|ltd|limited|gmbh|corporation|corp|sas|bv|oü)$")

    /**
     * Right after an entity name: `, Cyprus private limited company` or ` (Cyprus entity)`. Strong
     * evidence of where that entity is registered.
     */
    val ENTITY_COUNTRY_SUFFIX: Pattern = rx(
        ",?\\s{0,3}(?:an?\\s{1,3})?([A-Z][A-Za-z]{2,20}+(?:\\s[A-Z][A-Za-z]{2,20}+){0,2})\\s{1,3}" +
            "(?:private\\s{1,3})?(?:limited\\s{1,3})?(?:liability\\s{1,3})?company\\b" +
            "|\\s{0,3}\\(\\s{0,3}([A-Z][A-Za-z]{2,20}+(?:\\s[A-Z][A-Za-z]{2,20}+){0,2})\\s{1,3}entity\\s{0,3}\\)",
        ignoreCase = true,
    )
    const val ENTITY_COUNTRY_SUFFIX_WEIGHT = 4


    /** Legal form of the chosen entity -> country; the Russian/Kazakh forms are strong. */
    val FORM_COUNTRY: List<Pair<Pattern, String>> = listOf(
        rx("\\b(?:LLC|L\\.L\\.C|Inc|Corp|Corporation)\\b") to "US", rx("\\bGmbH\\b") to "DE", rx("\\bB\\.?V\\b") to "NL",
        rx("\\bS\\.?A\\.?S\\b") to "FR", rx("\\bOÜ\\b") to "EE", rx("\\bs\\.r\\.o\\b") to "CZ", rx("Sp\\. z o\\.o") to "PL",
        rx("Pvt\\.? Ltd|Private Limited") to "IN", rx("Pte\\.? Ltd") to "SG", rx("\\bOy\\b") to "FI",
        rx("\\b(?:FZCO|FZE|FZ-LLC|DMCC)\\b") to "AE", rx("\\bAB\\b") to "SE", rx("^(?:ООО|АО|ПАО|ЗАО|ИП)\\b|^Индивидуальный") to "RU",
        rx("^ТОО\\b") to "KZ",
    )

    /** Checked in this order; the first hit names the country of a phrase. */
    val COUNTRY_WORDS: List<Pair<String, Pattern>> = linkedMapOf(
        "US" to "United States|U\\.S\\.A?\\.|\\bUSA\\b|Delaware|California|Wyoming|Texas|New York|Florida|Nevada|Washington State|\\bСША\\b",
        "CA" to "Canada|Ontario|Quebec|British Columbia|Nova Scotia|Канад",
        "GB" to "United Kingdom|England and Wales|England|Scotland|\\bUK\\b|Великобритани|Англии",
        "IE" to "Ireland", "DE" to "Germany|Deutschland|Германи", "NL" to "Netherlands|Nederland|Нидерланд", "FR" to "France|Франци",
        "EE" to "Estonia|Эстони", "CY" to "Cyprus|Кипр", "SC" to "Seychelles|Сейшел", "HK" to "Hong Kong|Гонконг",
        "SG" to "Singapore|Сингапур", "IN" to "\\bIndia\\b|Indian", "LT" to "Lithuania|Литв", "LV" to "Latvia|Латви",
        "PL" to "Poland|Польш", "BG" to "Bulgaria|Болгари", "RO" to "Romania", "CZ" to "Czech|Чехи", "CH" to "Switzerland|Швейцари",
        "SE" to "Sweden", "FI" to "Finland", "BZ" to "Belize|Белиз", "PA" to "Panama|Панам",
        "VG" to "British Virgin Islands|\\bBVI\\b|Британских Виргинских", "LU" to "Luxembourg", "KZ" to "Kazakhstan|Казахстан",
        "AM" to "Armenia|Армени", "RS" to "Serbia|Серби", "MD" to "Moldova|Молдов", "UA" to "Ukraine|Украин", "TR" to "Turkey|Türkiye|Турци",
        "AE" to "United Arab Emirates|\\bUAE\\b|\\bОАЭ\\b",
        "RU" to "Russia\\b|Russian Federation|Российск\\w{1,10} Федерац\\w{1,10}|\\bРФ\\b|\\bРоссии\\b",
    ).map { (code, words) -> code to rx(words) }

    val SHOUTED_WORD: Pattern = rx("\\b[A-Z]{4,}+\\b")

    class StrongLegalRule(val pattern: Pattern, val label: String, val weight: Int, val needsSubject: Boolean)

    val STRONG_LEGAL: List<StrongLegalRule> = listOf(
        StrongLegalRule(
            rx(
                "(?:registered|incorporated|organi[sz]ed|established)\\s{1,5}(?:\\w{1,30}+\\s{1,5}){0,4}?" +
                    "(?:in|under the laws of)\\s{1,5}(?:the\\s{1,5})?([A-Z][\\w .]{2,40})",
            ),
            "registered in", 5, true,
        ),
        StrongLegalRule(
            rx(
                "(?:based|headquartered|located|domiciled)\\s{1,5}(?:\\w{1,30}+\\s{1,5}){0,2}?in\\s{1,5}(?:the\\s{1,5})?([A-Z][\\w .]{2,40})" +
                    "|[Ii]n\\s{1,5}([A-Z][\\w ]{2,30})\\s{1,5}\\((?:where\\s{1,5})?we\\s{1,5}are\\s{1,5}based\\)",
            ),
            "based in", 4, true,
        ),
        StrongLegalRule(rx("зарегистрирован\\w{0,10}\\s{1,5}(?:в|на территории)\\s{1,5}([А-ЯЁ][\\w ]{2,40})"), "registered in", 5, true),
        StrongLegalRule(
            rx(
                "(?:governed by|in accordance with|subject to|construed under)\\s{1,5}the\\s{1,5}laws\\s{1,5}of\\s{1,5}(?:the\\s{1,5})?" +
                    "([A-Z][\\w .]{2,40})|laws of the (?:State|Province) of ([A-Z][\\w ]{2,30})",
            ),
            "governing law", 2, false,
        ),
        StrongLegalRule(
            rx(
                "(?:законодательств\\w{1,10}|правом|законами)\\s{1,5}((?:Российской Федерации|РФ|Англии и Уэльса|Великобритании" +
                    "|Республики\\s{1,5}\\w{1,30}|[А-ЯЁ]\\w{1,30}))",
            ),
            "governing law", 2, false,
        ),
    )

    val WE_ARE: Pattern = rx("\\bwe\\s{1,5}are\\b")
    val SOMEONE_ELSE: Pattern = rx("customer|client|user|\\byou\\b|\\byour\\b|клиент|гражданин", ignoreCase = true)
    val SUBJECT: Pattern = rx("\\bwe\\b|\\bour\\b|\\bus\\b|company|компани\\w{0,10}|общество|исполнитель|провайдер", ignoreCase = true)

    /** (pattern, country); a `null` country is taken from the id's own prefix (group 1). */
    val TAX_IDS: List<Pair<Pattern, String?>> = listOf(
        rx("\\bИНН\\s{0,3}:?\\s{0,3}\\d{10,12}") to "RU",
        rx("\\bОГРН\\w{0,3}\\s{0,3}:?\\s{0,3}\\d{13,15}") to "RU",
        rx("\\bБИН\\s{0,3}:?\\s{0,3}\\d{12}") to "KZ",
        rx("\\b(?:VAT|USt-IdNr\\.?|TVA|BTW)(?:\\s{0,3}(?:ID|No\\.?|number|Reg\\.?))?\\s{0,3}:?\\s{0,3}([A-Z]{2})\\s?\\d[\\d ]{6,13}") to null,
        rx("\\bGSTIN\\s{0,3}:?\\s{0,3}\\d{2}[A-Z]{5}") to "IN",
        rx("\\bCIN\\s{0,3}:?\\s{0,3}[UL]\\d{5}[A-Z]{2}") to "IN",
        rx("Companies House|Company (?:No|Number|Registration Number)\\.?\\s{0,3}:?\\s{0,3}\\d{8}") to "GB",
        rx("\\bKvK\\b|Kamer van Koophandel") to "NL",
        rx("Handelsregister|\\bHRB\\s{0,3}\\d{1,10}") to "DE",
        rx("\\bRCS\\s{1,3}[A-Z][a-z]{1,30}|\\bSIRET\\b|\\bSIREN\\b") to "FR",
        rx("\\bUEN\\b") to "SG",
        rx("Business Registration (?:Number|No)\\.?\\s{0,3}:?\\s{0,3}\\d{8}") to "HK",
        rx("Registry code|Registrikood") to "EE",
    )

    val PHONE: Pattern = rx("\\+\\s?(\\d[\\d \\-()]{7,16}\\d)")

    val PHONE_PREFIX: Map<String, String> = mapOf(
        "1" to "US", "7" to "RU", "44" to "GB", "49" to "DE", "31" to "NL", "33" to "FR", "91" to "IN", "852" to "HK", "65" to "SG",
        "372" to "EE", "357" to "CY", "371" to "LV", "370" to "LT", "48" to "PL", "420" to "CZ", "359" to "BG", "40" to "RO",
        "380" to "UA", "375" to "BY", "90" to "TR", "971" to "AE", "41" to "CH", "43" to "AT", "46" to "SE", "358" to "FI",
        "353" to "IE", "61" to "AU", "81" to "JP", "374" to "AM", "373" to "MD", "995" to "GE", "998" to "UZ", "34" to "ES",
        "39" to "IT", "32" to "BE", "352" to "LU",
    )

    val TLD_COUNTRY: Map<String, String> = mapOf(
        "ru" to "RU", "su" to "RU", "in" to "IN", "de" to "DE", "nl" to "NL", "uk" to "GB", "fr" to "FR", "ee" to "EE", "kz" to "KZ",
        "pl" to "PL", "lt" to "LT", "lv" to "LV", "fi" to "FI", "se" to "SE", "ca" to "CA", "au" to "AU", "sg" to "SG", "hk" to "HK",
        "ch" to "CH", "at" to "AT",
    )

    /** Evidence kinds that alone can confirm a registration country. */
    val STRONG_COUNTRY_KINDS = setOf("json-ld", "registered in", "based in", "tax/registry id", "russian legal form", ADDRESS_OF_THE_COMPANY)

    const val ADDRESS_NEAR_PREFIX = "address near '"
    const val ADDRESS_OF_THE_COMPANY = "address of the company"
    const val ADDRESS_OF_ANOTHER_COMPANY = "address of another company"

    /** The weight an address next to another company's name keeps. */
    const val OTHER_COMPANY_ADDRESS_FACTOR = 0.3

    val JSON_LD_ORGANIZATION_TYPES = setOf("Organization", "Corporation", "LocalBusiness", "OnlineBusiness")
}
