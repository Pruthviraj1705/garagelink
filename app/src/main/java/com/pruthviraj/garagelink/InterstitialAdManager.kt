package com.pruthviraj.garagelink

import android.app.Activity
import android.content.Context
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback

// Google's official test interstitial ad unit ID — swap for your real one once
// your AdMob account/app is approved.
private const val TEST_INTERSTITIAL_AD_UNIT_ID = "ca-app-pub-3940256099942544/1033173712"

object InterstitialAdManager {
    private var interstitialAd: InterstitialAd? = null

    fun preload(context: Context) {
        if (interstitialAd != null) return
        InterstitialAd.load(
            context,
            TEST_INTERSTITIAL_AD_UNIT_ID,
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interstitialAd = ad
                }
                override fun onAdFailedToLoad(adError: LoadAdError) {
                    interstitialAd = null
                }
            }
        )
    }

    // Shows the ad if one is ready; otherwise just runs onDone immediately so
    // the user is never blocked by a missing ad. Always preloads the next one.
    fun showIfReady(activity: Activity, onDone: () -> Unit) {
        val ad = interstitialAd
        if (ad == null) {
            onDone()
            return
        }
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                interstitialAd = null
                preload(activity)
                onDone()
            }
            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                interstitialAd = null
                preload(activity)
                onDone()
            }
        }
        ad.show(activity)
    }
}