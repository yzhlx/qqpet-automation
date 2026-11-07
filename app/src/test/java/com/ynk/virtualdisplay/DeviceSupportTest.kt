package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.DeviceSupport
import org.junit.Assert.*
import org.junit.Test

class DeviceSupportTest {

    @Test
    fun calibratedModelIsRecognisedRegardlessOfCaseAndSpaces() {
        assertTrue(DeviceSupport.isCalibrated("FOA-AL00"))
        assertTrue(DeviceSupport.isCalibrated(" foa-al00 "))
        assertFalse("其他机型不能被当成已实测", DeviceSupport.isCalibrated("Pixel 7"))
    }

    @Test
    fun otherModelsGetAnHonestWarningWithAnActionableNextStep() {
        assertNull("已实测机型不应出现提示卡", DeviceSupport.homeWarningTitle("FOA-AL00"))
        assertEquals("这台手机没有实测过", DeviceSupport.homeWarningTitle("SM-G9910"))
        val body = DeviceSupport.homeWarningBody("SM-G9910")
        assertNotNull(body)
        assertTrue("提示里要写清是哪台机器未实测", body!!.contains("SM-G9910"))
        assertTrue("必须说明认不出就停，而不是包成功", body.contains("认不出就停下"))
    }

    @Test
    fun diagnosticItemNeverBlocksButExplainsTheUnverifiedDevice() {
        assertNull(DeviceSupport.diagnosticRemedy("FOA-AL00"))
        assertNotNull("未实测机型必须给出下一步", DeviceSupport.diagnosticRemedy("Pixel 7"))
        assertTrue(DeviceSupport.diagnosticMessage("FOA-AL00").contains("已实测"))
        assertTrue(DeviceSupport.diagnosticMessage("Pixel 7").contains("未实测"))
    }

    @Test
    fun missingModelFallsBackToUnknownInsteadOfPretendingItIsCalibrated() {
        assertFalse(DeviceSupport.isCalibrated(null))
        assertEquals("未知机型", DeviceSupport.modelLabel(null))
    }
}
