package app.gamenative.assistant

import org.json.JSONArray
import org.json.JSONObject

/** Evidence and one-variable experiments, never a made-up bottleneck or a measured speed-up. */
object StutterDiagnosis {
    fun analyze(live: JSONObject, goal: Int): JSONObject {
        val all = live.optJSONArray("recentSamples") ?: JSONArray()
        val samples = (0 until all.length()).map { all.getJSONObject(it) }.filter {
            it.optString("quality") == "live" && !it.optBoolean("assistantPanelVisible") && it.optInt("frameSampleStride", 1) == 1
        }
        val evidence = JSONArray(); val hypotheses = JSONArray()
        fun values(key: String) = samples.mapNotNull { (it.opt(key) as? Number)?.toDouble()?.takeIf(Double::isFinite) }
        val fps = values("fps"); val gpu = values("gpuUsagePercent"); val cpu = values("cpuUsagePercent")
        val p95 = values("frameTimeP95Ms")
        val fresh = live.optString("status") == "live" && live.optLong("sampleAgeMs", Long.MAX_VALUE) <= 2500
        fun mean(v: List<Double>): Any = if (v.isEmpty()) JSONObject.NULL else v.average()
        evidence.put(JSONObject().put("usableWindows", samples.size).put("meanFps", mean(fps)).put("meanWindowP95Ms", mean(p95))
            .put("gpuMeanPercent", mean(gpu)).put("cpuMeanPercent", mean(cpu)).put("freshSession", fresh))
        if (fresh && fps.size >= 6 && fps.average() < goal * 0.9 && gpu.size >= 6 && gpu.average() >= 90)
            hypotheses.put(JSONObject().put("hypothesis", "Hög GPU-belastning kan bidra till att målet missas.")
                .put("experiment", "Jämför samma scen vid en lägre upplösning. Behåll övriga inställningar och kontrollprofilen."))
        if (fresh && p95.size >= 6 && p95.average() > 1000.0 / goal * 1.5)
            hypotheses.put(JSONObject().put("hypothesis", "Ojämna bildtider observeras i spelvyn.")
                .put("experiment", "Mät samma scen i 60 sekunder. Skilj laddning eller shaderkompilering från återkommande hack innan en inställning ändras."))
        for (key in listOf("cpuTempC", "gpuTempC")) {
            val paired = samples.mapNotNull { sample ->
                val temp = (sample.opt(key) as? Number)?.toDouble()?.takeIf(Double::isFinite)
                val frames = (sample.opt("fps") as? Number)?.toDouble()?.takeIf(Double::isFinite)
                if (temp != null && frames != null) temp to frames else null
            }
            if (samples.size >= 20 && paired.size >= 10) {
                val delta = paired.takeLast(5).map { it.first }.average() - paired.take(5).map { it.first }.average()
                val fpsDelta = paired.takeLast(5).map { it.second }.average() - paired.take(5).map { it.second }.average()
                evidence.put(JSONObject().put("sensor", key).put("changeC", delta).put("fpsChange", fpsDelta))
                if (fresh && delta >= 3 && fpsDelta < -5) hypotheses.put(JSONObject().put("hypothesis", "Temperaturen steg samtidigt som FPS sjönk; orsak är inte fastställd.")
                    .put("experiment", "Upprepa samma scen med samma starttemperatur och strömläge. Frekvens- eller throttlingdata saknas för att bekräfta värmestrypning."))
            }
        }
        return JSONObject().put("goalFps", goal).put("observations", evidence).put("hypotheses", hypotheses)
            .put("limits", "Overlapping two-second windows from up to 30 seconds; no 1% low, whole-run p95 or causal proof. CPU may be device-wide; low total CPU cannot exclude a saturated core. Missing GPU/temperature sensors stay unknown. Chat-visible samples excluded; historical samples do not establish current behavior. Use matching 60-second experiments and repeat before claiming a gain.")
    }
}
