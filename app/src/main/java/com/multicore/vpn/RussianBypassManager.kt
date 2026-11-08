package com.multicore.vpn

import android.net.VpnService
import android.util.Log

/**
 * Manages full bypass for Russian state services, banks, marketplaces, VK, and Yandex.
 *
 * Provides:
 * 1. OS-level VpnService bypass (addDisallowedApplication) so banking & state apps
 *    never even see the VPN network interface and cannot detect it.
 * 2. Mihomo L3/L7 routing rules (DIRECT) for all .ru, .su, .рф domains and specific
 *    services so that web browsers access them with the local Russian IP.
 */
object RussianBypassManager {
    private const val TAG = "RussianBypassManager"

    /**
     * Package names of popular Russian services that should bypass the VPN at the OS kernel level.
     * When added to VpnService.Builder.addDisallowedApplication(), the Android system hides
     * the VPN from these apps completely.
     */
    val RU_APP_PACKAGES = setOf(
        // Государственные услуги и налоги
        "ru.gosuslugi.net",
        "ru.gosuslugi.pos",
        "com.minsvyaz.gosuslugi",
        "ru.rostel",
        "ru.nalog.fl",
        "ru.fns.billcheck",
        "ru.mos.app",

        // Банки и платежи
        "ru.sberbankmobile",
        "ru.sberbank.sberid",
        "com.idamob.tinkoff.android",
        "ru.tinkoff.invest.etf",
        "com.tcsbank.business",
        "ru.tbank.mobile",
        "ru.alfabank.mobile.android",
        "ru.vtb24.mobilebanking.android",
        "ru.gazprombank.android.mobilebank.app",
        "ru.raiffeisennews",
        "ru.sovcomcard.halva.v1",
        "ru.ozon.fintech.bank",
        "com.yandex.bank",
        "ru.nspk.mirpay",
        "ru.nspk.sbp",

        // Соцсети и коммуникации (VK, Mail.ru)
        "com.vkontakte.android",
        "com.vk.im",
        "com.vk.calls",
        "com.vk.clips",
        "com.vk.music",
        "ru.mail.my",
        "ru.mail.mailapp",
        "ru.mail.cloud",

        // Яндекс
        "ru.yandex.searchplugin",
        "ru.yandex.yandexmaps",
        "ru.yandex.yandexnavi",
        "ru.yandex.taxi",
        "ru.yandex.music",
        "ru.kinopoisk",
        "ru.yandex.disk",
        "ru.yandex.mail",
        "com.yandex.browser",

        // Маркетплейсы и ритейл
        "ru.ozon.app.android",
        "ru.ozon.seller",
        "com.wildberries.wbwb",
        "com.avito.android",
        "com.sbermarket",
        "com.lamoda.lite",
        "ru.dns.shop.android",

        // Мобильные операторы
        "ru.mts.mymts",
        "ru.megafon.mlk",
        "ru.beeline.services",
        "ru.tele2.mytele2"
    )

    /**
     * Applies OS-level app disallowance for all installed Russian applications.
     */
    fun applyOsAppBypass(builder: VpnService.Builder) {
        var bypassedCount = 0
        for (pkg in RU_APP_PACKAGES) {
            try {
                builder.addDisallowedApplication(pkg)
                bypassedCount++
            } catch (e: Exception) {
                // Throws NameNotFoundException if the app is not installed on this device.
                // We silently ignore non-installed packages.
            }
        }
        Log.i(TAG, "Applied OS-level bypass for $bypassedCount installed Russian apps (banks, Gosuslugi, VK, etc.)")
    }

    /**
     * Returns Mihomo DIRECT rules for Russian domains, banks, and services.
     */
    fun getMihomoDirectRules(): List<String> {
        return listOf(
            // Top-Level Domains (RU, SU, РФ)
            "DOMAIN-SUFFIX,ru,DIRECT",
            "DOMAIN-SUFFIX,su,DIRECT",
            "DOMAIN-SUFFIX,xn--p1ai,DIRECT", // .рф

            // Госуслуги и ведомства
            "DOMAIN-SUFFIX,gosuslugi.ru,DIRECT",
            "DOMAIN-SUFFIX,esia.gosuslugi.ru,DIRECT",
            "DOMAIN-SUFFIX,gu-st.ru,DIRECT",
            "DOMAIN-SUFFIX,mos.ru,DIRECT",
            "DOMAIN-SUFFIX,nalog.gov.ru,DIRECT",
            "DOMAIN-SUFFIX,pfr.gov.ru,DIRECT",
            "DOMAIN-SUFFIX,sfr.gov.ru,DIRECT",
            "DOMAIN-SUFFIX,cbr.ru,DIRECT",
            "DOMAIN-KEYWORD,gosuslugi,DIRECT",

            // Банки и платёжные системы
            "DOMAIN-SUFFIX,sberbank.ru,DIRECT",
            "DOMAIN-SUFFIX,sber.ru,DIRECT",
            "DOMAIN-SUFFIX,sberdevices.ru,DIRECT",
            "DOMAIN-SUFFIX,tbank.ru,DIRECT",
            "DOMAIN-SUFFIX,tinkoff.ru,DIRECT",
            "DOMAIN-SUFFIX,tcsbank.ru,DIRECT",
            "DOMAIN-SUFFIX,alfabank.ru,DIRECT",
            "DOMAIN-SUFFIX,alfa-bank.ru,DIRECT",
            "DOMAIN-SUFFIX,vtb.ru,DIRECT",
            "DOMAIN-SUFFIX,vtb.com,DIRECT",
            "DOMAIN-SUFFIX,gazprombank.ru,DIRECT",
            "DOMAIN-SUFFIX,raiffeisen.ru,DIRECT",
            "DOMAIN-SUFFIX,sovcombank.ru,DIRECT",
            "DOMAIN-SUFFIX,nspk.ru,DIRECT",
            "DOMAIN-SUFFIX,mir-pay.ru,DIRECT",
            "DOMAIN-SUFFIX,mironline.ru,DIRECT",
            "DOMAIN-SUFFIX,sbp.nspk.ru,DIRECT",
            "DOMAIN-KEYWORD,sberbank,DIRECT",
            "DOMAIN-KEYWORD,tinkoff,DIRECT",
            "DOMAIN-KEYWORD,alfabank,DIRECT",

            // ВКонтакте и Mail.ru
            "DOMAIN-SUFFIX,vk.com,DIRECT",
            "DOMAIN-SUFFIX,vk.me,DIRECT",
            "DOMAIN-SUFFIX,vk.ru,DIRECT",
            "DOMAIN-SUFFIX,vkuser.net,DIRECT",
            "DOMAIN-SUFFIX,userapi.com,DIRECT",
            "DOMAIN-SUFFIX,dzen.ru,DIRECT",
            "DOMAIN-SUFFIX,mail.ru,DIRECT",
            "DOMAIN-SUFFIX,imgsmail.ru,DIRECT",
            "DOMAIN-SUFFIX,cloud.mail.ru,DIRECT",

            // Яндекс
            "DOMAIN-SUFFIX,yandex.ru,DIRECT",
            "DOMAIN-SUFFIX,ya.ru,DIRECT",
            "DOMAIN-SUFFIX,yandex.net,DIRECT",
            "DOMAIN-SUFFIX,yastatic.net,DIRECT",
            "DOMAIN-SUFFIX,kinopoisk.ru,DIRECT",
            "DOMAIN-SUFFIX,auto.ru,DIRECT",

            // Маркетплейсы
            "DOMAIN-SUFFIX,ozon.ru,DIRECT",
            "DOMAIN-SUFFIX,ozonusercontent.com,DIRECT",
            "DOMAIN-SUFFIX,ozon-st.ru,DIRECT",
            "DOMAIN-SUFFIX,wildberries.ru,DIRECT",
            "DOMAIN-SUFFIX,wb.ru,DIRECT",
            "DOMAIN-SUFFIX,wbstatic.net,DIRECT",
            "DOMAIN-SUFFIX,avito.ru,DIRECT",
            "DOMAIN-SUFFIX,avito.st,DIRECT",
            "DOMAIN-SUFFIX,megamarket.ru,DIRECT",

            // Геолокационные справочники Clash/Mihomo
            "GEOSITE,category-ru,DIRECT",
            "GEOIP,RU,DIRECT"
        )
    }
}
