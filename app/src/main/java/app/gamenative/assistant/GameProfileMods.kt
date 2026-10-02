package app.gamenative.assistant

import android.content.Context
import app.gamenative.data.ModInstall
import app.gamenative.data.ModTargetRoot
import app.gamenative.data.ModInstallStatus
import app.gamenative.data.ModProfileInstallState
import app.gamenative.mods.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files

/** Uses the existing ownership, backup and deployment journals; never copies arbitrary model paths. */
internal class GameProfileMods(private val context: Context, private val game: String, private val root: () -> File?) {
    private val dao get() = NexusModManager.dao(context)
    private val cache get() = NexusModManager.cacheRoot(context, game)
    private fun ownership(id: String) = ModOwnershipStore.read(cache, id)?.also { check(it.appId == game) }
    suspend fun capture(): JSONObject {
        val profile = dao.getActiveProfileForApp(game)
        val priorities = profile?.let { dao.getProfileInstallStates(game, it.profileId).associate { s -> s.installId to s.priority } }.orEmpty()
        val all = dao.getInstallsForApp(game)
        require(all.size <= 150) { "Profilverktyget stöder högst 150 modpaket per spel." }
        return JSONObject().apply { all.sortedBy { it.installId }.forEach { mod ->
            val owned = ownership(mod.installId)
            val sources = JSONObject()
            owned?.files.orEmpty().forEach { sources.put(it.sourceRelativePath, it.installedHash) }
            put(mod.installId, JSONObject().put("name", DiagnosticRedactor.text(mod.modName).take(160))
                .put("packageHash", mod.archiveSha256).put("sources", sources)
                .put("enabled", mod.status == ModInstallStatus.APPLIED.name)
                .put("priority", priorities[mod.installId] ?: owned?.files?.maxOfOrNull { it.priority } ?: 0)
                .put("placement", placement(owned)))
        } }
    }
    private fun placement(owned: ModOwnershipManifest?): JSONArray = JSONArray(owned?.files.orEmpty().sortedBy { it.normalizedTargetKey }.map {
        JSONObject().put("source", it.sourceRelativePath).put("root", it.targetRoot).put("path", it.targetRelativePath).put("mode", it.mode)
    })
    suspend fun fingerprints(): JSONObject {
        val files = dao.getInstallsForApp(game).flatMap { ownership(it.installId)?.files.orEmpty() }.distinctBy { it.normalizedTargetKey }
        require(files.size <= 20_000) { "Profilväxling stöder högst 20 000 berörda modfiler per spel." }
        val base = requireNotNull(root()).canonicalFile
        return JSONObject().apply { files.forEach { file ->
            val target = File(file.targetPath)
            safe(target, base)
            check(!target.exists() || target.isFile)
            put(file.normalizedTargetKey, if (target.isFile) ModOwnershipStore.sha256(target).also { check(it.isNotBlank()) } else "missing")
        } }
    }
    suspend fun inspect(): JSONObject {
        val mods = capture()
        val claims = linkedMapOf<String, MutableList<Pair<String, String>>>()
        mods.keys().forEach { id -> ownership(id)?.files.orEmpty().forEach { f -> claims.getOrPut(f.normalizedTargetKey) { mutableListOf() }.add(id to f.targetRelativePath) } }
        return JSONObject().put("mods", JSONArray(mods.keys().asSequence().map { id -> val m = mods.getJSONObject(id)
            JSONObject().put("mod_id", id).put("name", m.getString("name")).put("enabled", m.getBoolean("enabled"))
                .put("priority", m.getInt("priority")).put("trackedFiles", m.getJSONArray("placement").length()) }.toList()))
            .put("conflicts", JSONArray(claims.values.filter { it.map { p -> p.first }.distinct().size > 1 }.take(100).map { contributions ->
                val enabled = contributions.map { it.first }.distinct().filter { mods.getJSONObject(it).getBoolean("enabled") }
                JSONObject().put("path", DiagnosticRedactor.text(contributions.first().second))
                    .put("mods", JSONArray(contributions.map { mods.getJSONObject(it.first).getString("name") }.distinct()))
                    .put("activeWinner", enabled.maxWithOrNull(compareBy<String> { mods.getJSONObject(it).getInt("priority") }.thenBy { it })?.let { mods.getJSONObject(it).getString("name") } ?: JSONObject.NULL)
            })).put("limits", "Tracked file overlaps, including disabled packages; highest enabled priority wins. Not a semantic mod compatibility test. FOMOD/plugin order not inferred. Newly imported packages must first receive a reviewed deployment in Modbibliotek.")
    }
    suspend fun validate(target: JSONObject) {
        val current = capture()
        check(current.keys().asSequence().toSet() == target.keys().asSequence().toSet()) { "Modbiblioteket har ändrats sedan profilen sparades. Granska paketen och spara en ny profil." }
        val manifests = current.keys().asSequence().mapNotNull(::ownership).toList()
        val overlay = ModProfileOverlayPlanner.build(manifests, manifests.filter { it.state == ModOwnershipState.ACTIVE }
            .associate { it.installId to (it.files.maxOfOrNull { f -> f.priority } ?: 0) })
        check(ModDeploymentVerifier.verify(overlay, ModVerificationDepth.CHANGED_CONTENT).successful) {
            "En installerad modfil saknas eller har ändrats. Modbibliotekets återställning behöver granskas först."
        }
        val all = dao.getInstallsForApp(game).associateBy { it.installId }
        target.keys().forEach { id ->
            val want = target.getJSONObject(id); val now = current.getJSONObject(id)
            val mod = all.getValue(id)
            require(want.getInt("priority") in 0..10000)
            val affected = want.getBoolean("enabled") || now.getBoolean("enabled")
            if (!affected) return@forEach
            check(want.getString("packageHash") == now.getString("packageHash") && GameProfiles.same(want.getJSONArray("placement"), now.getJSONArray("placement"))) { "Modpaketet eller filplaceringen har ändrats: ${mod.modName}" }
            val owned = requireNotNull(ownership(id)) { "${mod.modName} saknar granskad filplacering. Installera den i modbiblioteket först." }
            check(owned.state != ModOwnershipState.RECOVERY_REQUIRED && owned.reviewedPlanOrNull() != null)
            val rootDir = requireNotNull(root()).canonicalFile
            check(owned.files.size <= 20_000)
            owned.files.forEach { file ->
                check(file.targetRoot == ModTargetRoot.GAME_DIR.name && file.mode == "OVERWRITE_COPY") { "Denna profil innehåller särskilda mål/länkningar. Använd Modbibliotek och Nexus för att växla den." }
                val destination = File(rootDir, file.targetRelativePath)
                safe(destination, rootDir)
                check(destination.canonicalPath == File(file.targetPath).canonicalPath)
                if (file.targetRelativePath.substringAfterLast('.').lowercase() in setOf("esp", "esm", "esl"))
                    error("Profilen innehåller plugin-laddordning. Växla den i Modbibliotek och Nexus så att beroenden och pluginfiler hanteras tillsammans.")
                val sourceRoot = File(mod.extractedPath).canonicalFile
                check(sourceRoot.toPath().startsWith(cache.canonicalFile.toPath()))
                val source = File(sourceRoot, file.sourceRelativePath)
                safe(source, sourceRoot)
                if (want.getBoolean("enabled")) check(source.isFile && ModOwnershipStore.sha256(source) == want.getJSONObject("sources").optString(file.sourceRelativePath)) { "Modkällan saknas eller har ändrats: ${mod.modName}" }
            }
            dao.getOverwriteManifests(id).filter { it.backupPath.isNotBlank() }.forEach { backup ->
                val file = File(backup.backupPath)
                check(file.isFile && ModOwnershipStore.sha256(file) == backup.originalHash) { "Originalbackupen saknas eller har ändrats: ${mod.modName}" }
            }
            ModDeploymentJournalStore.read(cache, id)?.let { journal ->
                check(journal.checkpoint in setOf(ModDeploymentCheckpoint.COMMITTED, ModDeploymentCheckpoint.ROLLED_BACK)) { "Modbiblioteket har en ofullständig installation att återställa först." }
            }
        }
    }
    suspend fun write(target: JSONObject) = ModDeploymentCoordinator.withGameLock(game) {
        if (GameProfiles.same(capture(), target)) return@withGameLock
        validate(target)
        val profile = ModProfileManager.ensureActiveProfile(dao, game)
        val all = dao.getInstallsForApp(game)
        val plans = all.associate { it.installId to ownership(it.installId)?.reviewedPlanOrNull() }
        // Unwind from top to bottom, so every overwritten lower layer can be restored and verified.
        val active = all.filter { it.status == ModInstallStatus.APPLIED.name }.sortedWith(compareByDescending<ModInstall> {
            ownership(it.installId)?.files?.maxOfOrNull { f -> f.priority } ?: 0
        }.thenByDescending { it.installId })
        for (mod in active) check(NexusModManager.disableInstall(context, mod, true, root(), "").isEmpty()) { "En ändrad modfil behölls. Återställningspunkten finns kvar." }
        for (mod in all.filter { target.getJSONObject(it.installId).getBoolean("enabled") }.sortedWith(compareBy<ModInstall> { target.getJSONObject(it.installId).getInt("priority") }.thenBy { it.installId })) {
            val plan = requireNotNull(plans[mod.installId])
            // Native backups are sticky per package. After an order change an old backup can
            // be another mod's former layer, so it must not be reused as the new lower layer.
            // All active packages are now unwound. Preserve old bytes before resetting this
            // disabled package's backup references; native apply then captures the real layer.
            preservePreviousBackups(mod.installId)
            val result = NexusModManager.applyInstall(context, mod.copy(status = ModInstallStatus.DISABLED.name), emptyList(), root(), "", true,
                saveLastPlacement = false, preserveStatusOnError = true, profileId = profile.profileId,
                priority = target.getJSONObject(mod.installId).getInt("priority"), reviewedPlan = plan)
            check(result.errors.isEmpty() && result.warnings.isEmpty()) { "Modväxlingen blev ofullständig: ${(result.errors.values + result.warnings).joinToString().take(500)}. Återställningspunkten behålls." }
        }
        all.forEach { mod -> val value = target.getJSONObject(mod.installId)
            dao.upsertProfileInstallState(ModProfileInstallState(profile.profileId, mod.installId, game, value.getBoolean("enabled"), value.getInt("priority")))
        }
    }
    private suspend fun preservePreviousBackups(id: String) {
        check(dao.getInstall(id)?.status != ModInstallStatus.APPLIED.name)
        val backupRoot = NexusModManager.backupRoot(context, game).canonicalFile
        val native = File(backupRoot, id)
        safe(native, backupRoot)
        val history = File(cache, "assistant-profile-backups").apply { check(isDirectory || mkdirs()) }
        val files = if (native.isDirectory) native.walkTopDown().onEnter { check(!Files.isSymbolicLink(it.toPath())); true }
            .filter { it.isFile }.take(20_001).toList() else emptyList()
        require(files.size <= 20_000)
        val preserved = files.associate { source ->
            safe(source, backupRoot)
            val hash = ModOwnershipStore.sha256(source)
            check(hash.isNotBlank())
            val archive = File(history, "$hash.bin")
            if (archive.exists()) check(ModOwnershipStore.sha256(archive) == hash) else {
                check(history.usableSpace > source.length() + 32_000_000) { "För lite utrymme för modprofilens återställningsfiler." }
                val temporary = File(history, "${java.util.UUID.randomUUID()}.tmp")
                try {
                    java.io.FileOutputStream(temporary).use { output -> source.inputStream().use { it.copyTo(output) }; output.fd.sync() }
                    check(ModOwnershipStore.sha256(temporary) == hash && temporary.renameTo(archive))
                } finally { temporary.delete() }
            }
            source.canonicalPath to archive.canonicalPath
        }
        val old = dao.getOverwriteManifests(id)
        // A process death at any step leaves valid backup references. A retry also handles
        // the remaining native files if the database already points to the preserved copies.
        val updated = old.map { backup -> backup.copy(backupPath = preserved[File(backup.backupPath).canonicalPath] ?: backup.backupPath) }
        updated.filter { it.backupPath.isNotBlank() }.forEach { check(ModOwnershipStore.sha256(File(it.backupPath)) == it.originalHash) }
        dao.replaceOverwriteManifestsForTargets(id, updated)
        files.forEach { check(it.delete()) }
        dao.deleteOverwriteManifests(id)
    }
    private fun safe(file: File, base: File) {
        check(file.canonicalFile.toPath().startsWith(base.toPath()) && file.canonicalFile != base)
        var current: File? = file
        while (current != null && current != base) { check(!Files.isSymbolicLink(current.toPath())); current = current.parentFile }
    }
}
