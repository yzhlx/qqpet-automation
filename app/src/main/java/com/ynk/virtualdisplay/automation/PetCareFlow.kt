package com.ynk.virtualdisplay.automation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.TimeoutCancellationException
import org.json.JSONObject
import java.io.File

/** Extracted measured cookie / soap flow. Unknown is never converted to a number. */
class PetCareFlow(private val flow: PetFlowContext,private val navigation: PetNavigation,
    private val onHealth: (HealthObservations)->Unit={}) {
    var careStatistics=JSONObject();private set
    suspend fun readHealth(label: String, parent: PetProfile.Probe = flow.home): PetCareReading = with(flow) {
        val started = SystemClock.elapsedRealtime()
        navigation.tap(parent,PointF(516f,160f),healthPanel,"open-health")
        val f = frame(true)
        val h = try {
            save(f,label)
            val snapshot=PetHealthCheck.observe(context,f,System.currentTimeMillis(),File(evidence,"round-$round-$label.png").absolutePath)
            capture.facts.health(snapshot)
            onHealth(snapshot)
            recovery.health(snapshot)
            PetCareReading(snapshot)
        } finally { f.recycle() }
        check(h.energy in 10..100 && h.cleanliness in 10..100) { "HEALTH_VALUE_ABNORMAL" }
        emit(label,result=healthJson(h).put("read_duration_ms",SystemClock.elapsedRealtime()-started).toString())
        navigation.tap(healthPanel,PointF(355f,454f),parent,"close-health")
        h
    }
    suspend fun recover(initial: PetCareReading): PetCareReading = with(flow) {
        state="RECOVER_IF_NEEDED"
        var h = initial
        val recovery=JSONObject().put("feed_count",0).put("feed_duration_ms",0L)
            .put("clean_count",0).put("clean_duration_ms",0L)
        careStatistics=recovery
        var feeds=0
        while(h.energy<90) {
            check(feeds<5) { "FEED_RETRY_LIMIT" }
            val before=h.energy; val started=SystemClock.elapsedRealtime(); val wall=System.currentTimeMillis()
            navigation.tap(room,PointF(638f,720f),foodScene,"enter-food")
            verify(listOf(foodScene),"feed-before",latest=true)
            safety(); input.tap(360f,1287f); delay(2500)
            navigation.dismissCareUpgradeIfPresent()
            verify(listOf(foodScene),"feed-after")
            h=readHealth("feed-health",foodScene)
            check(h.energy>before && h.energy-before in 5..12) { "FEED_NO_EXPECTED_GAIN_OR_EMPTY" }
            navigation.tap(foodScene,PointF(80f,160f),room,"leave-food")
            feeds++; recovery.put("feed_count",feeds)
            recovery.put("feed_duration_ms",recovery.getLong("feed_duration_ms")+SystemClock.elapsedRealtime()-started)
            emit("feed",result=JSONObject().put("feed_start_ts",wall).put("feed_end_ts",System.currentTimeMillis())
                .put("feed_duration_ms",SystemClock.elapsedRealtime()-started).put("stamina_before",before)
                .put("stamina_after",h.energy).put("inventory_before","UNKNOWN").put("inventory_after","UNKNOWN").toString())
        }
        var cleans=0
        while(h.cleanliness<90) {
            check(cleans<3) { "CLEAN_RETRY_LIMIT" }
            val before=h.cleanliness; val started=SystemClock.elapsedRealtime()
            navigation.tap(room,PointF(638f,868f),bath,"enter-bath")
            // Page identity is the stable bath scene, never the soap button: the button repaints
            // to its pale "already clean" state once cleanliness is maxed, which used to fail
            // clean-after verification (PAGE_MISMATCH ... 0.9538353304284677) with cleanliness
            // already at 99. The soap probe stays the measured gesture/tap target only.
            verify(listOf(bath),"clean-before",latest=true)
            val points=buildList {
                add(PointF(360f,1345f));add(PointF(360f,1100f));add(PointF(360f,900f))
                repeat(40) { add(PointF(280f,800f));add(PointF(430f,950f));add(PointF(280f,950f));add(PointF(430f,800f)) }
            }
            safety(); input.drag(points,150); delay(2000)
            navigation.dismissCareUpgradeIfPresent()
            verify(listOf(bath),"clean-after")
            h=readHealth("clean-health",bath)
            check(h.cleanliness>before) { "CLEAN_NO_GAIN_OR_EMPTY" }
            navigation.tap(bath,PointF(80f,160f),room,"leave-bath")
            cleans++;recovery.put("clean_count",cleans)
            recovery.put("clean_duration_ms",recovery.getLong("clean_duration_ms")+SystemClock.elapsedRealtime()-started)
            emit("clean",result=JSONObject().put("cleanliness_before",before).put("cleanliness_after",h.cleanliness)
                .put("clean_duration_ms",SystemClock.elapsedRealtime()-started).put("inventory_before","UNKNOWN")
                .put("inventory_after","UNKNOWN").toString())
        }
        h
    }

}
