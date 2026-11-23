package com.the412banner.batterylog

import com.the412banner.batterylog.update.AppVersion
import com.the412banner.batterylog.update.GhAsset
import com.the412banner.batterylog.update.GhRelease
import com.the412banner.batterylog.update.UpdateRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicTest {

    private val header = BatteryLogService.CSV_HEADER

    /** App rows (lowercase status/plugged) and boot-sampler rows (kernel status text, "boot"). */
    private fun csv(rows: List<String>) = (listOf(header) + rows).joinToString("\n") + "\n"

    @Test
    fun parsesAppAndBootRows() {
        val text = csv(
            listOf(
                "1794777618000,2026-11-15T16:20:18,28600,9,charging,boot,3893,3904536,842767,0,250,good",
                "1794777644000,2026-11-15T16:20:44,58730,10,not charging,boot,3824,2063660,863722,0,260,good",
                "1794777674000,2026-11-15T16:21:14,88730,10,discharging,unplugged,3800,-1500000,860000,0,261,good",
                "garbage,line",
                "1794777704000,2026-11-15T16:21:44,118730,10,Full,usb,4380,10000,860000,,262,good",
                "1794777734000,2026-11-15T16:22:14,1187", // half-written last line after a shut-off
            ),
        )
        val p = CsvLog.parse(text)
        val s = p.set
        assertEquals(4, s.size)
        assertEquals(28600L, p.firstUptimeMs)
        assertEquals(SampleSet.ST_CHARGING, s.status[0])
        assertEquals(SampleSet.ST_NOT_CHARGING, s.status[1])
        assertEquals(SampleSet.ST_DISCHARGING, s.status[2])
        assertEquals(SampleSet.ST_FULL, s.status[3])
        assertEquals(SampleSet.PL_BOOT, s.plug[0])
        assertEquals(SampleSet.PL_UNPLUGGED, s.plug[2])
        assertEquals(SampleSet.PL_USB, s.plug[3])
        assertEquals(-1500000, s.ua[2])
        assertEquals(26.1f, s.tempC(2), 0.01f)
        assertTrue(s.onCharger(0))
        assertTrue(!s.onCharger(2))
        // 3.8 V x -1.5 A = -5.7 W
        assertEquals(-5.7f, s.watts(2), 0.01f)
    }

    @Test
    fun flipsPositiveDischargeCurrent() {
        val rows = (0 until 10).map { i ->
            "${1_000_000L + i * 30_000},x,0,${90 - i},discharging,unplugged,3900,${800000 + i},0,0,300,good"
        }
        val s = CsvLog.parse(csv(rows)).set
        assertTrue(s.ua.all { it < 0 })
    }

    private fun discharge(from: Int, to: Int, start: Long, msPerPct: Long = 240_000, uahPerPct: Int = 84_000): SampleSet {
        val b = SampleSet.Builder()
        var q = 8_400_000
        for ((k, pct) in (from downTo to).withIndex()) {
            b.add(start + k * msPerPct, pct, 4300 - (from - pct) * 6, -1_500_000, q, 300, SampleSet.ST_DISCHARGING, SampleSet.PL_UNPLUGGED)
            q -= uahPerPct
        }
        return b.build()
    }

    @Test
    fun ratesAndSession() {
        // 1 % every 4 minutes = 15 %/h.
        val s = discharge(100, 60, 0L)
        val r = Analysis.rates(s)
        assertNotNull(r.drainPctPerH)
        assertEquals(15.0, r.drainPctPerH!!, 0.01)
        assertNull(r.chargePctPerH)
        val sess = Analysis.currentSession(s)!!
        assertTrue(!sess.charging)
        assertEquals(100, sess.fromPct)
        assertEquals(60, sess.nowPct)
    }

    @Test
    fun measuredCapacityFromChargeCounter() {
        // 84 mAh per % → 8400 mAh full.
        val m = Analysis.measuredCapacity(discharge(100, 50, 0L))
        assertNotNull(m)
        assertEquals(8400, m!!.fullMah)
    }

    @Test
    fun chargingBandsSplitOnGaps() {
        val b = SampleSet.Builder()
        for (i in 0 until 5) b.add(i * 30_000L, 50 + i, 4000, 2_000_000, 0, 300, SampleSet.ST_CHARGING, SampleSet.PL_AC)
        for (i in 0 until 5) b.add(600_000L + i * 30_000L, 55, 4000, -1_000_000, 0, 300, SampleSet.ST_DISCHARGING, SampleSet.PL_UNPLUGGED)
        for (i in 0 until 3) b.add(10_000_000L + i * 30_000L, 55 + i, 4000, 2_000_000, 0, 300, SampleSet.ST_CHARGING, SampleSet.PL_AC)
        val bands = Analysis.chargingBands(b.build())
        assertEquals(2, bands.size)
        assertEquals(0L, bands[0].first)
        assertEquals(120_000L, bands[0].last)
    }

    @Test
    fun versionsCompare() {
        val v = { s: String -> AppVersion.parse(s)!! }
        assertTrue(v("v2.0.0") > v("v2.0.0-pre3"))
        assertTrue(v("2.0.0-pre10") > v("2.0.0-pre2"))
        assertTrue(v("2.0.0-pre1") > v("1.6.0"))
        assertTrue(v("1.6.0") > v("v1.5.0"))
    }

    @Test
    fun picksNewestAllowedRelease() {
        fun rel(tag: String, pre: Boolean) = GhRelease(
            tag = tag, prerelease = pre,
            assets = listOf(GhAsset("BatteryLog-$tag.apk", "https://x/$tag.apk"), GhAsset("BatteryLog-$tag.apk.sha256", "https://x/$tag.sha")),
        )
        val list = listOf(rel("v1.6.0", false), rel("v2.0.0-pre2", true), rel("v2.0.0-pre1", true))
        assertEquals("v1.6.0", UpdateRepository.pick(list, includePrereleases = false)!!.tag)
        val pre = UpdateRepository.pick(list, includePrereleases = true)!!
        assertEquals("v2.0.0-pre2", pre.tag)
        assertEquals("BatteryLog-v2.0.0-pre2.apk.sha256", pre.checksum!!.name)
    }

    // ---- per-app monitoring parsers ----

    @Test
    fun psParsesProcessesAndCpuTime() {
        val out = """
              PID   UID    RSS     TIME+ NAME
                1     0   5756   0:14.44 init
            12457 10226 412000   1:02.50 com.banner.cleanbrowser
            12460 10226  80000   0:00.07 com.banner.cleanbrowser:sandboxed_process0
              900  1000  90000 1-02:03:04.5 system_server
        """.trimIndent()
        val ps = com.the412banner.batterylog.apps.AppParse.ps(out)
        assertEquals(4, ps.size)
        assertEquals(14_440L, ps[0].cpuMs)
        assertEquals(62_500L, ps[1].cpuMs)
        assertEquals("com.banner.cleanbrowser", ps[2].pkg)
        assertTrue(ps[1].isApp)
        assertTrue(!ps[3].isApp)
        assertEquals(((24L + 2) * 3600 + 3 * 60 + 4) * 1000 + 500, ps[3].cpuMs)
    }

    @Test
    fun grepAndStatAndBatteryStats() {
        val g = com.the412banner.batterylog.apps.AppParse.grepH(
            "/sys/class/kgsl/kgsl/proc/10360/kernel:8667136\n/sys/class/thermal/thermal_zone5/temp:48100",
        )
        assertEquals("8667136", g["10360"])
        assertEquals("48100", g["thermal_zone5"])
        val st = com.the412banner.batterylog.apps.AppParse.procStat("cpu  100 0 50 800 50 0 0 0 0 0")!!
        assertEquals(150L, st.first)
        assertEquals(1000L, st.second)
        val bs = com.the412banner.batterylog.apps.AppParse.batteryStatsUid("9,0,l,pwi,uid,4.32,1,0,0\n9,10226,l,pwi,uid,12.5,1,0,0\n9,0,l,pwi,cpu,15.9,0,0,0")
        assertEquals(12.5, bs[10226]!!, 0.001)
        assertEquals(2, bs.size)
        assertEquals("cpu", com.the412banner.batterylog.apps.AppParse.thermalGroup("cpu-2-0-0"))
        assertEquals("gpu", com.the412banner.batterylog.apps.AppParse.thermalGroup("gpuss-3"))
        assertEquals(48.1f, com.the412banner.batterylog.apps.AppParse.milliC("48100"), 0.01f)
        assertTrue(com.the412banner.batterylog.apps.AppParse.milliC("-273000").isNaN())
    }

    @Test
    fun sensorReportNamesTheSourceOfEachReading() {
        val raw = """
            == thermal zones (zone | type | temp)
            thermal_zone0 | cpu-0-0-0 | 45000
            thermal_zone1 | cpu-1-0-0 | 105000
            thermal_zone2 | battery | 31000
            thermal_zone3 | gpuss-0 | cat: /sys/class/thermal/thermal_zone3/temp: Permission denied
            == thermalservice
            Current temperatures from HAL:
            	Temperature{mValue=27.4, mType=1, mName=GPU4, mStatus=0}
            	Temperature{mValue=25.0, mType=3, mName=skin, mStatus=0}
            Current cooling devices from HAL:
            == kgsl-3d0
            gpu_busy_percentage: head: /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage: Permission denied
            gpubusy:  116299 1004827
            gpuclk: head: /sys/class/kgsl/kgsl-3d0/gpuclk: Permission denied
            == end
        """.trimIndent()
        val r = com.the412banner.batterylog.apps.SensorReport.interpret(raw)
        assertTrue(r, r.contains("cpu: 105.0 °C from thermal_zone1 (cpu-1-0-0)"))
        assertTrue(r, r.contains("gpu: 27.4 °C from Android's thermal list"))
        assertTrue(r, r.contains("skin: 25.0 °C from Android's thermal list"))
        assertTrue(r, r.contains("battery: 31.0 °C from thermal_zone2 (battery)"))
        assertTrue(r, r.contains("ddr: none"))
        assertTrue(r, r.contains("gpu busy: 11.6 % (gpubusy)"))
        assertTrue(r, r.contains("gpu clock: none"))
    }

    @Test
    fun honorReadings() {
        val g = com.the412banner.batterylog.apps.AppParse
        // Trip points report their limit, not a temperature.
        assertNull(g.thermalGroup("cpu-hw-trip-0"))
        assertEquals("cpu", g.thermalGroup("cpullc-0-0"))
        assertEquals("cpu", g.thermalGroup("cpu-1-0-0"))
        assertEquals(11.57f, g.kgslGpuBusy("  116299 1004827\n")!!, 0.01f)
        assertNull(g.kgslGpuBusy("head: Permission denied"))
        assertEquals(0f, g.kgslGpuBusy("      0       0")!!, 0.001f)   // GPU idle (Honor report 15:22)
        // Honor: CURRENT_NOW -294 (mA), CHARGE_COUNTER 5290 (mAh) at 91 %; the Elite reports µA / µAh.
        assertTrue(BatteryUnits.isMilli(5290, 91))
        assertEquals(-294_000, BatteryUnits.currentUa(-294, true))
        assertEquals(5_290_000, BatteryUnits.chargeUah(5290, true))
        assertTrue(!BatteryUnits.isMilli(2_272_681, 25))
        assertTrue(!BatteryUnits.isMilli(50_000, 1))   // 1 % of a 5000 mAh battery in µAh
        assertTrue(!BatteryUnits.isMilli(0, 50))
        assertEquals(-1_345_255, BatteryUnits.currentUa(-1_345_255, false))
        assertEquals(Int.MIN_VALUE, BatteryUnits.currentUa(Int.MIN_VALUE, true))
    }

    @Test
    fun halTempsFromThermalserviceDump() {
        val dump = """
            Thermal Status: 3
            Cached temperatures:
            	Temperature{mValue=26.0, mType=3, mName=skin, mStatus=0}
            HAL Ready: true
            Current temperatures from HAL:
            	Temperature{mValue=3.719, mType=6, mName=vbat, mStatus=0}
            	Temperature{mValue=22.0, mType=2, mName=battery, mStatus=0}
            	Temperature{mValue=33.2, mType=0, mName=CPU1, mStatus=0}
            	Temperature{mValue=35.1, mType=0, mName=CPU0, mStatus=0}
            	Temperature{mValue=27.4, mType=1, mName=GPU4, mStatus=0}
            	Temperature{mValue=25.886, mType=3, mName=skin, mStatus=0}
            	Temperature{mValue=26.2, mType=9, mName=nsp2, mStatus=0}
            Current cooling devices from HAL:
            	CoolingDevice{mValue=0, mType=2, mName=thermal-pause-2}
        """.trimIndent()
        val t = com.the412banner.batterylog.apps.AppParse.halTemps(dump)
        assertEquals(35.1f, t["cpu"]!!, 0.01f)
        assertEquals(27.4f, t["gpu"]!!, 0.01f)
        assertEquals(25.886f, t["skin"]!!, 0.01f)
        assertEquals(22.0f, t["battery"]!!, 0.01f)
        assertEquals(4, t.size)
        // Falls back to the cached list when the HAL section is missing.
        val cached = com.the412banner.batterylog.apps.AppParse.halTemps("Cached temperatures:\n\tTemperature{mValue=41.5, mType=3, mName=skin, mStatus=1}\n")
        assertEquals(41.5f, cached["skin"]!!, 0.01f)
    }

    @Test
    fun appRowsRoundTrip() {
        val t = com.the412banner.batterylog.apps.TRow(
            1000L, "com.x", -2.5, 80, false, 30f, 55f, 600f, 48f, 50f, 35f, Float.NaN, 31f, 12f, 4096, 2048,
        )
        val back = com.the412banner.batterylog.apps.TRow.parse(t.csv())!!
        assertEquals("com.x", back.fg)
        assertEquals(2.5, back.drawW, 0.001)
        assertTrue(back.ddrC.isNaN())
        val m = com.the412banner.batterylog.apps.MRow(60_000L, "com.x", 10226, 1200, 4000, 5000, 300, 30_000, 75_000.0, 6)
        assertEquals(m, com.the412banner.batterylog.apps.MRow.parse(m.csv()))
    }

    @Test
    fun surfaceFlingerLayerAndFps() {
        val list = """
            Task=1747#209296
            ActivityRecord{7c7383f u0 com.tencent.ig/com.winlator.star.XServerDisplayActivity#209298
            a503178 com.tencent.ig/com.winlator.star.XServerDisplayActivity#288890
            SurfaceView[com.tencent.ig/com.winlator.star.XServerDisplayActivity]#288901
            SurfaceView[com.tencent.ig/com.winlator.star.XServerDisplayActivity](BLAST)#288902
            3ed49a com.termux/com.termux.app.TermuxActivity#212443
        """.trimIndent()
        val P = com.the412banner.batterylog.apps.AppParse
        assertEquals("SurfaceView[com.tencent.ig/com.winlator.star.XServerDisplayActivity](BLAST)#288902", P.sfLayerFor(list, "com.tencent.ig"))
        assertEquals("3ed49a com.termux/com.termux.app.TermuxActivity#212443", P.sfLayerFor(list, "com.termux"))
        assertEquals(null, P.sfLayerFor(list, "com.example.none"))
        // Android 16 (Pocket FIT Elite, 11-21): names wrapped in RequestedLayerState{…}; frames are on the unhashed layer.
        val list16 = """
            RequestedLayerState{com.the412banner.batterylog.debug/com.the412banner.batterylog.MainActivity#10682 parentId=10458}
            RequestedLayerState{30af06e com.the412banner.batterylog.debug/com.the412banner.batterylog.MainActivity#10458 parentId=10391}
            RequestedLayerState{86df877 ActivityRecordInputSink com.the412banner.batterylog.debug/com.the412banner.batterylog.MainActivity#10395 parentId=10391 z=-2147483648}
            RequestedLayerState{ActivityRecord{194067684 u0 com.the412banner.batterylog.debug/com.the412banner.batterylog.MainActivity#10391 parentId=10390}
            RequestedLayerState{SurfaceView[com.game/com.game.Main](BLAST)#900 parentId=899}
        """.trimIndent()
        assertEquals("com.the412banner.batterylog.debug/com.the412banner.batterylog.MainActivity#10682", P.sfLayerFor(list16, "com.the412banner.batterylog.debug"))
        assertEquals("SurfaceView[com.game/com.game.Main](BLAST)#900", P.sfLayerFor(list16, "com.game"))

        // 61 frames 1/60 s apart -> 60 fps; table ends 0.1 s before "now".
        val start = 5_000_000_000L
        val step = 16_666_667L
        val rows = (0..60).joinToString("\n") { i -> val t = start + i * step; "${t - 1000}\t$t\t${t - 2000}" }
        val last = start + 60 * step
        val fps = P.sfFps("16666666\n0\t0\t0\n$rows\n", last + 100_000_000L)!!
        assertEquals(60f, fps, 0.5f)
        assertEquals(0f, P.sfFps("6944444\n0\t0\t0\n0\t0\t0\n", 1L)!!, 0f)
        assertEquals(0f, P.sfFps("16666666\n$rows\n", last + 3_000_000_000L)!!, 0f)
        assertEquals(null, P.sfFps("", 1L))
    }

    @Test
    fun hudPositionGrid() {
        val c = com.the412banner.batterylog.hud.HudConfig()
        // Older settings (corner only) map onto the grid.
        assertEquals(0, c.copy(corner = 0).cell)
        assertEquals(2, c.copy(corner = 1).cell)
        assertEquals(6, c.copy(corner = 2).cell)
        assertEquals(8, c.copy(corner = 3).cell)
        val topCenter = c.at(0, 1)
        assertEquals(1, topCenter.pos); assertEquals(0, topCenter.row); assertEquals(1, topCenter.col)
        assertEquals(0, topCenter.x); assertEquals(24, topCenter.y)
        val middle = c.at(1, 1)
        assertEquals(4, middle.cell); assertEquals(0, middle.x); assertEquals(0, middle.y)
        val bottomRight = c.copy(corner = 0).at(2, 2)
        assertEquals(8, bottomRight.cell); assertEquals(24, bottomRight.x); assertEquals(24, bottomRight.y)
    }

    @Test
    fun hudTextParts() {
        val v = com.the412banner.batterylog.hud.HudValues(
            fps = 59.6f, cpuPct = 41f, gpuPct = 77f, gpuMhz = 1050f, cpuC = 101f, gpuC = 88f, caseC = 50f,
            battC = 33f, battPct = 15, watts = -17.6, charging = false, time = 0L,
        )
        val all = com.the412banner.batterylog.hud.HudMetric.entries.toSet()
        val parts = com.the412banner.batterylog.hud.HudText.parts(v, all, com.the412banner.batterylog.TempUnit.C)
        assertEquals("FPS", parts[0].label); assertEquals("60", parts[0].value)
        assertEquals(2, parts.first { it.value == "101°C" }.level)
        assertEquals(1, parts.first { it.label == "الهيكل" }.level)
        assertEquals("17.6\u00A0W", parts.first { it.value.endsWith("\u00A0W") }.value)
        assertEquals(1, parts.first { it.value == "15%" }.level)
        val f = com.the412banner.batterylog.hud.HudText.parts(v, setOf(com.the412banner.batterylog.hud.HudMetric.CaseTemp), com.the412banner.batterylog.TempUnit.F)
        assertEquals("122°F", f.single().value)
        assertEquals("الهيكل\u00A0122°F", com.the412banner.batterylog.hud.HudText.plain(f, true))
        // A blocked GPU clock still says what it is (the label "GPU" is shared with load and temperature).
        val noClk = com.the412banner.batterylog.hud.HudText.parts(v.copy(gpuMhz = Float.NaN), setOf(com.the412banner.batterylog.hud.HudMetric.GpuMhz), com.the412banner.batterylog.TempUnit.C)
        assertEquals("GPU\u00A0—\u00A0MHz", com.the412banner.batterylog.hud.HudText.plain(noClk, true))
    }
}
