package io.portal.vpn

/**
 * Built-in AdBlock engine for Portal VPN.
 *
 * Injects high-performance L7/DNS REJECT rules into Mihomo core.
 * Blocks aggressive Russian ad networks (Yandex Direct, AdFox, Begun),
 * global trackers (DoubleClick, Google AdServices, Taboola, Outbrain, Criteo),
 * mobile app/game ad SDKs (UnityAds, AppLovin, IronSource, Vungle, Mintegral),
 * and intrusive popup/casino redirectors.
 */
object AdBlockManager {

    /**
     * Curated list of high-impact ad and telemetry domains to reject locally.
     * These rules prevent ads from downloading, saving up to 30-40% of mobile data
     * and accelerating page loads across all browsers and apps.
     */
    val AD_BLOCK_DOMAINS = listOf(
        // Российские рекламные сети и трекеры
        "an.yandex.ru",
        "mc.yandex.ru",
        "awaps.yandex.net",
        "adfox.yandex.ru",
        "adfox.ru",
        "begun.ru",
        "relap.io",
        "marketgid.com",
        "smi2.ru",
        "target.my.com",
        "target-ad.mail.ru",
        "target.mail.ru",
        "ads.vk.com",
        "top-fwz1.mail.ru",
        "matchid.adriver.ru",
        "adriver.ru",
        "buzzoola.com",
        "rambler.ru/ads",
        "ad.adriver.ru",
        "moevideo.biz",
        "viqeo.tv",

        // Мобильные рекламные сети (игры и приложения)
        "unityads.unity3d.com",
        "applvn.com",
        "applovin.com",
        "ironsrc.mobi",
        "supersonicads.com",
        "vungle.com",
        "mintegral.com",
        "chartboost.com",
        "inmobi.com",
        "adcolony.com",
        "fyber.com",
        "tapjoy.com",
        "pangle.io",
        "pangolin-sdk-toutiao.com",
        "liftoff.io",
        "admob.com",
        "startappservice.com",
        "adservice.google.com",

        // YouTube реклама, баннеры в ленте и видео-трекеры
        "ads.youtube.com",
        "ad.youtube.com",
        "youtubeads.g.doubleclick.net",
        "video-stats.l.google.com",
        "video-stats.google.com",
        "pubads.g.doubleclick.net",
        "securepubads.g.doubleclick.net",
        "static.doubleclick.net",

        // Глобальные баннерные сети и трекеры
        "doubleclick.net",
        "googleadservices.com",
        "googlesyndication.com",
        "pagead2.googlesyndication.com",
        "criteo.com",
        "criteo.net",
        "taboola.com",
        "outbrain.com",
        "adroll.com",
        "advertising.com",
        "scorecardresearch.com",
        "quantserve.com",
        "adnxs.com",
        "rubiconproject.com",
        "pubmatic.com",
        "openx.net",
        "casalemedia.com",
        "yieldmo.com",
        "bidswitch.net",
        "teads.tv",
        "moatads.com",
        "smartadserver.com",

        // Навязчивая реклама, казино и поп-апы
        "popads.net",
        "popcash.net",
        "propellerads.com",
        "exoclick.com",
        "adsterra.com",
        "trafficjunky.com",
        "mgid.com",
        "clickadu.com",
        "zeroredirect1.com",
        "hilltopads.net",
        "traffichaus.com"
    )

    /**
     * Generates Mihomo REJECT rules for the ad blocking list.
     */
    fun getMihomoAdBlockRules(): List<String> {
        return AD_BLOCK_DOMAINS.map { "DOMAIN-SUFFIX,$it,REJECT" }
    }
}
