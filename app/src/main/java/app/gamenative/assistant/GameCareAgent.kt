package app.gamenative.assistant

import org.json.JSONArray
import org.json.JSONObject

internal object GameCareAgent {
    val instructions = """
        Additional native capabilities (supersede older profile-remapping limitations):
        For startup errors call read_preflight: local launch-file check, known config contradictions, missing-DLL evidence in
        available logs, possible missing numbered installation parts, and tracked mod overlaps when mod access is enabled.
        Distinguish error/warning/unknown; a passing check is not proof of compatibility. Do not download random DLLs.
        A local game's bundled vc_redist/vcredist/DXSETUP EXE can be inspected and proposed with existing offline tools;
        the user completes its Wine wizard. No automatic runtime downloads. ZIP/7z import is available in the offline screen.
        For stutter read_stutter_context combines optimization context and sensor-backed hypotheses. It satisfies the config
        read requirement. Report missing sensors honestly, choose one experiment, preserve working input, compare repeatable runs.
        read_game_profiles lists this game's named game/controls/mods snapshots and (with mod access) tracked file conflicts.
        propose_game_profile action=save creates a named snapshot after review; restore selects a returned profile_id. Use the
        returned kind; name and profile_id are empty when unused. mod_ids is empty for game/controls and all restores.
        For saving a mods profile only, mod_ids is the enabled list in increasing priority (last wins); [] means Original.
        Select only actual mod IDs with reviewed placements; imported READY packages need a reviewed install first.
        A game snapshot stores graphics/runtime CHOICES, control bindings and tracked mod selection/order, not installed runtime
        packages, the game itself, registry, savegames or arbitrary INI edits. controls snapshots preserve only controller-related
        settings/mappings. Do not call a snapshot verified working unless the user actually tested it. Profiles are local/per game.
        Restoring shows the affected fields and uses a separate Ångra profil; modified files or missing backups block destructive
        replacement. Bethesda plugin/load-order and unsupported deployment targets use Modbibliotek och Nexus.
        read_control_profiles exposes the actual control library and current anonymous controller binding IDs. To change a whole
        profile use propose_control_profile; it COPIES to this game's private profile. To remap a button call
        propose_controller_binding with a listed controller_id, allowedButtons name and allowedOutputs value. It changes one
        button only, with local review and undo; it preserves sticks and the MODE/Home menu shortcut. No global profiles or other
        games are rewritten. No player-slot writes; diagnose player assignment with read_input_route and existing Controller UI.
        Profile saves/restores/remaps require a stopped game. Live bridge trials and the read-only input test still work as before.
        One action per response across settings/files/mods/offline/profiles/undo. A proposal is NOT executed. Use the app's review
        card rather than pretending to apply changes in text. Spelverktyg provides local profile and preflight controls without AI.
    """.trimIndent()

    fun functions(array: JSONArray) {
        fun add(name: String, description: String, schema: String = "{}") {
            val properties = JSONObject(schema)
            array.put(JSONObject().put("type", "function").put("name", name).put("description", description).put("strict", true)
                .put("parameters", JSONObject().put("type", "object").put("properties", properties)
                    .put("required", JSONArray(properties.keys().asSequence().toList())).put("additionalProperties", false)))
        }
        add("read_game_profiles", "List this game's saved restoration/control/mod profiles and tracked mod file conflicts if permitted. No private snapshot content uploaded.")
        add("read_control_profiles", "Read actual control profile choices and anonymous current bindings, allowed source buttons and output bindings. Required before remapping or applying a control library profile.")
        add("read_preflight", "Check launch EXE, known configuration contradictions, missing DLL evidence, numbered setup parts and tracked mod conflicts. Does not launch or prove compatibility.")
        add("read_stutter_context", "Combined configuration, optimization goal/measurements/live sensors and cautious stutter hypotheses. Qualifies as configuration read; no causal claims without controlled measurements.")
        add("propose_game_profile", "Stage save/restore of a named local game/controls/mods profile for review. mods saves select ordered enabled mod_ids; empty = Original. Other unused fields empty. No automatic execution.", """{"action":{"type":"string","enum":["save","restore"]},"kind":{"type":"string","enum":["game","controls","mods"]},"name":{"type":"string"},"profile_id":{"type":"string"},"mod_ids":{"type":"array","items":{"type":"string"}},"reason":{"type":"string"}}""")
        add("propose_control_profile", "Stage a listed library control profile copied to ONLY this game. Read controls first. Local Apply and undo; stopped game required.", """{"profile_id":{"type":"string"},"reason":{"type":"string"}}""")
        add("propose_controller_binding", "Stage one existing controller's button mapping in ONLY this game. Use listed ID/button/output; sticks and Home are preserved. Local review/undo.", """{"controller_id":{"type":"string"},"button":{"type":"string"},"output":{"type":"string"},"reason":{"type":"string"}}""")
    }
}
