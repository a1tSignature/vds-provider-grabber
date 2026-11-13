package org.a1tsign.vds.crawler.fetch

/**
 * Signs that a site answered with an anti-bot challenge instead of content. The crawler only
 * detects and stops: it never retries, solves or evades a challenge.
 */
object ChallengeMarkers {
    /** Header Cloudflare sets on a challenge response, whatever the status. */
    const val CF_MITIGATED_HEADER = "cf-mitigated"
    const val CF_MITIGATED_CHALLENGE = "challenge"

    /** Error statuses whose body is inspected for [BODY_MARKERS]. */
    val CHALLENGE_STATUSES = setOf(403, 429, 503)

    /** How much of such an error body is read for the check. */
    const val BODY_PROBE_BYTES = 64 * 1024

    /** Interstitial challenge pages (Cloudflare "Just a moment..."). */
    val BODY_MARKERS = listOf("Just a moment...", "cf-chl", "challenge-platform", "/cdn-cgi/challenge")

    /** CAPTCHA widgets that can sit inside an otherwise normal `200` page. */
    val CAPTCHA_WIDGET_MARKERS = listOf("challenges.cloudflare.com/turnstile", "g-recaptcha", "h-captcha", "hcaptcha.com")

    /** The first [BODY_MARKERS] entry found in [body], case-insensitively; `null` when none. */
    fun challengeMarkerIn(body: String): String? = BODY_MARKERS.firstOrNull { body.contains(it, ignoreCase = true) }

    /** The first [CAPTCHA_WIDGET_MARKERS] entry found in [body], case-insensitively; `null` when none. */
    fun captchaWidgetIn(body: String): String? = CAPTCHA_WIDGET_MARKERS.firstOrNull { body.contains(it, ignoreCase = true) }
}
