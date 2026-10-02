package app.gamenative.assistant

import java.io.File
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GameCareDiagnosticsTest {
    @get:Rule val dir = TemporaryFolder()
    @Test fun missingExecutableNumberedSidecarAndRuntimeLogProduceSpecificEvidence() {
        val root = dir.newFolder("game")
        File(root, "setup-1.bin").writeText("1"); File(root, "setup-3.bin").writeText("3")
        testWindowsExe(File(root, "vc_redist.x86.exe"))
        val report = GamePreflight.inspect(JSONObject().put("executablePath", "missing.exe"), root, null,
            "err:module:import_dll Library VCRUNTIME140.dll (which is needed by game) not found\npassword=do-not-send")
        assertTrue(report.findings.any { it.severity == "error" && it.title.contains("Startfilen") })
        assertTrue(report.findings.any { it.detail.contains("vcruntime140.dll") && it.next.contains("Visual C++") })
        assertTrue(report.findings.any { it.detail.contains("del 2") })
        assertFalse(report.json().toString().contains("do-not-send"))
    }
    @Test fun validExeAndNoLogDoNotClaimAllDependenciesPresent() {
        val root = dir.newFolder("game"); testWindowsExe(File(root, "game.exe"))
        val report = GamePreflight.inspect(JSONObject().put("executablePath", "game.exe"), root, null, "loaded d3dx9_43.dll")
        assertTrue(report.findings.any { it.severity == "ok" })
        assertFalse(report.findings.any { it.title.contains("saknad DLL") })
        assertTrue(report.findings.any { it.severity == "unknown" })
        assertTrue(GamePreflight.missingParts(listOf("setup-1.bin")).isEmpty())
    }
    @Test fun launchInspectionRejectsEscapesAndLinksButCanInspectPrivateCDrive() {
        val root = dir.newFolder("game"); val prefix = dir.newFolder("prefix")
        val exe = File(prefix, "drive_c/Games/game.exe"); testWindowsExe(exe)
        assertEquals(exe, GamePreflight.resolveExecutable("C:\\Games\\game.exe", root, prefix))
        assertNull(GamePreflight.resolveExecutable("../prefix/drive_c/Games/game.exe", root, prefix))
        Files.createSymbolicLink(File(root, "linked.exe").toPath(), exe.toPath())
        assertNull(GamePreflight.resolveExecutable("linked.exe", root, prefix))
    }
    private fun live(gpu: Boolean = true, status: String = "live", panel: Boolean = false) = JSONObject().put("status", status).put("sampleAgeMs", 100)
        .put("recentSamples", JSONArray((0 until 30).map { i -> JSONObject().put("quality", "live").put("assistantPanelVisible", panel)
            .put("frameSampleStride", 1).put("fps", 50 - i / 2.0).put("frameTimeP95Ms", 42)
            .put("gpuUsagePercent", if (gpu) 97 else JSONObject.NULL).put("cpuTempC", 60 + i / 3.0) }))
    @Test fun stutterHypothesesRequireEvidenceAndExcludeChatAndStaleSamples() {
        val valid = StutterDiagnosis.analyze(live(), 120)
        assertEquals(3, valid.getJSONArray("hypotheses").length())
        assertTrue(valid.toString().contains("orsak är inte fastställd"))
        assertEquals(0, StutterDiagnosis.analyze(live(status = "stale"), 120).getJSONArray("hypotheses").length())
        assertEquals(0, StutterDiagnosis.analyze(live(panel = true), 120).getJSONArray("hypotheses").length())
        val missing = StutterDiagnosis.analyze(live(gpu = false), 120)
        assertTrue(missing.getJSONArray("observations").getJSONObject(0).isNull("gpuMeanPercent"))
        assertFalse(missing.getJSONArray("hypotheses").toString().contains("GPU-belastning"))
    }
}
