package org.a1tsign.vds.crawler.fetch

/** Stop on an anti-bot challenge without retrying. */
object ChallengeMarkers {
    /** Header Cloudflare sets on a challenge response, whatever the status. */
    const val CF_MITIGATED_HEADER = "cf-mitigated"
    const val CF_MITIGATED_CHALLENGE = "challenge"

    /** Error statuses whose body is inspected for [BODY_MARKERS]. */
    val CHALLENGE_STATUSES = setOf(403, 429, 503)

    const val BODY_PROBE_BYTES = 64 * 1024

    /** Interstitial challenge pages (Cloudflare "Just a moment..."). */
    val BODY_MARKERS = listOf("Just a moment...", "cf-chl", "challenge-platform", "/cdn-cgi/challenge")

    /** CAPTCHA widgets that can sit inside an otherwise normal `200` page. */
    val CAPTCHA_WIDGET_MARKERS = listOf("challenges.cloudflare.com/turnstile", "g-recaptcha", "h-captcha", "hcaptcha.com")

    fun challengeMarkerIn(body: String): String? = BODY_MARKERS.firstOrNull { body.contains(it, ignoreCase = true) }

    fun captchaWidgetIn(body: String): String? = CAPTCHA_WIDGET_MARKERS.firstOrNull { body.contains(it, ignoreCase = true) }
}
