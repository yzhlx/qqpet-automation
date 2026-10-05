package com.ynk.virtualdisplay

import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ynk.virtualdisplay.automation.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PetHealthReadingTest {
    @Test fun heldoutRealHealthDigits() {
        val inst=InstrumentationRegistry.getInstrumentation()
        val reader=HealthDigitReader(HealthDigitReader.parse(inst.targetContext.assets.open("pet_health/digits.txt")
            .bufferedReader().use {it.readText()}))
        val rows=inst.context.assets.open("health_reading/manifest.tsv").bufferedReader().use {it.readLines()}
        var count=0;var minimum=1.0
        for(row in rows) {
            val parts=row.split('\t')
            val frame=inst.context.assets.open("health_reading/${parts[0]}").use {BitmapFactory.decodeStream(it)}!!
            try {
                val value=reader.read(frame.width,frame.height,{x,y -> val p=frame.getPixel(x,y)
                    maxOf(Color.red(p),Color.green(p),Color.blue(p))<100},System.currentTimeMillis(),parts[0])
                assertTrue("${parts[0]} $value",value is ObservedValue.Known)
                value as ObservedValue.Known
                assertEquals(parts[2].toInt(),value.value);minimum=minOf(minimum,value.confidence);count++
            } finally {frame.recycle()}
        }
        assertEquals(57,count)
        println("HEALTH_HELDOUT_PASS values=$count minimumConfidence=$minimum trainingFrames=4 heldoutFrames=19")
    }

    @Test fun realPanelObservationsSeparateExactAndConservativeAndExpire() {
        val inst=InstrumentationRegistry.getInstrumentation()
        for((name,expected) in listOf("high" to listOf(94,98,81),"low" to listOf(67,66,74))) {
            val frame=inst.context.assets.open("health_reading/$name.png").use {BitmapFactory.decodeStream(it)}!!
            try {
                val at=System.currentTimeMillis()
                val values=PetHealthCheck.observe(inst.targetContext,frame,at,name)
                listOf(values.energy,values.cleanliness,values.mood).zip(expected).forEach {(value,e) ->
                    assertEquals(e,(value as ObservedValue.Known).value)
                    assertEquals(ObservationSource.DIGIT_RECOGNITION,value.source)
                }
                assertEquals(ObservationSource.CONSERVATIVE_BAR_ESTIMATE,(values.barEnergy as ObservedValue.Known).source)
                assertFalse(values.current(at).ready) // Both recorded pages have mood below 90.
                assertTrue(runCatching {values.current(at+60001)}.isFailure)
            } finally {frame.recycle()}
        }
    }
}
