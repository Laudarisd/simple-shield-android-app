package com.simpleshield.adblocker

/**
 * BlockedDomainList.kt
 *
 * Conservative list of advertising domains. Analytics, social widgets,
 * malware, and adult-content categories deliberately do not belong here:
 * this app promises ad blocking, and broad non-ad rules can break apps.
 *
 * HOW MATCHING WORKS: a queried domain is blocked if it EQUALS an entry
 * here, OR ends with "." + an entry here (so blocking "doubleclick.net"
 * also blocks "ads.doubleclick.net", the same way most ad blockers treat a
 * domain rule as covering all of its subdomains).
 *
 * TO ADD MORE DOMAINS: just add a string to the set below. No other code
 * needs to change.
 */
object BlockedDomainList {

    /** Which stat tile a blocked domain should be counted under in the UI. */
    enum class Category { ADS, SPONSORED }

    private val adDomains: Set<String> = setOf(
        // --- Google advertising ---
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "adservice.google.com",

        // --- Major ad exchanges/networks ---
        "adnxs.com", "rubiconproject.com", "pubmatic.com", "openx.net",
        "casalemedia.com", "criteo.com", "criteo.net", "adsrvr.org",
        "bidswitch.net", "media.net", "smartadserver.com", "yieldmo.com",
        "sharethrough.com", "sovrn.com", "adform.net", "advertising.com",
        "spotxchange.com", "improvedigital.com", "gumgum.com", "33across.com",
        "indexexchange.com", "sonobi.com", "triplelift.com", "contextweb.com",
        "amazon-adsystem.com", "moatads.com", "adroll.com", "flashtalking.com",
        "yieldlab.net", "adition.com", "smaato.com",

        // --- Pop/redirect ad networks ---
        "propellerads.com", "popads.net", "poptm.com", "adcash.com",
        "exoclick.com", "juicyads.com", "trafficjunky.com", "adsterra.com",
        "hilltopads.net", "hilltopads.com", "clickadu.com", "clickadilla.com",
        "clickaine.com", "galaksion.com", "popcash.net", "popmyads.com",
        "propellerclick.com", "onclickads.net", "onclickperformance.com",
        "ad-maven.com", "bidvertiser.com", "revenuehits.com", "adskeeper.com",
        "a-ads.com", "adspyglass.com", "trafficstars.com", "tsyndicate.com",
        "exosrv.com", "exdynsrv.com", "realsrv.com", "pemsrv.com",
        "twinrdsrv.com", "dtscout.com",
        // Adsterra's rotating ad-serving domains
        "profitableratecpmnetwork.com", "highperformanceformat.com",
        "effectivegatecpm.com", "effectiveratecpm.com", "highrevenuegate.com",

        // --- Explicit mobile in-app ad SDK hosts (the ad-serving endpoints
        // only, not the vendors' broader company/developer domains, so we
        // never risk blocking an app's actual login/data/sync traffic) ---
        "unityads.unity3d.com", "ads.unity3d.com", "pubnative.net",
        "applovin.com", "applvn.com", "chartboost.com", "vungle.com",
        "ironsrc.com", "supersonicads.com", "startapp.com", "adcolony.com",
        "mintegral.com", "inmobi.com",

        // --- Video ad servers ---
        "2mdn.net", "aniview.com", "springserve.com"
    )

    // "Sponsored"/native-content networks: the boxes of outside links/articles
    // labeled "Sponsored" or "You may like" at the bottom of news sites, etc.
    private val sponsoredDomains: Set<String> = setOf(
        "outbrain.com", "taboola.com", "revcontent.com", "mgid.com",
        "content.ad", "nativo.com", "adblade.com",
    )

    /** All blocked domains regardless of category — kept for anything that
     * just needs the full list, not a per-category breakdown. */
    val blockedDomains: Set<String> = adDomains + sponsoredDomains

    /** True if `queriedDomain` is (or is a subdomain of) a blocked domain. */
    fun isBlocked(queriedDomain: String): Boolean = categorize(queriedDomain) != null

    /** Which category `queriedDomain` (or a domain it's a subdomain of)
     * falls under, or null if it isn't blocked at all. */
    fun categorize(queriedDomain: String): Category? {
        val domain = queriedDomain.trimEnd('.').lowercase()
        fun matches(blocked: String) = domain == blocked || domain.endsWith(".$blocked")
        if (adDomains.any(::matches)) return Category.ADS
        if (sponsoredDomains.any(::matches)) return Category.SPONSORED
        return null
    }
}
