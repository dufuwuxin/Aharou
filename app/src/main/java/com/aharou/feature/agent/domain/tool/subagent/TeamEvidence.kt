package com.aharou.feature.agent.domain.tool.subagent

import kotlinx.serialization.json.*

/** Validates claimed delivery shape and run identity; does not execute reported commands or certify facts. */
internal object TeamEvidence {
    fun instruction(run: String, acceptance: String) =
        "\nTEAM_EVIDENCE must be one JSON line. baseline.run_id must equal $run; acceptance_id must equal $acceptance. " +
            "status=done requires nonempty evidence_refs with actual SHA256, no failures or unverified checks. " +
            "Preserve failed commands and unknown root causes; never change acceptance or invent proof.\n"

    fun error(text: String, run: String, acceptance: String): String? {
        val lines = text.lineSequence().map(String::trim).filter { it.startsWith("TEAM_EVIDENCE:") }.toList()
        if (lines.size != 1) return "TEAM_EVIDENCE_MISSING_OR_DUPLICATE"
        val body = lines.single().removePrefix("TEAM_EVIDENCE:").trim()
        if (body.length > 64_000) return "TEAM_EVIDENCE_TOO_LARGE"
        val value = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return "TEAM_EVIDENCE_INVALID_JSON"
        fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
        if ((value["schema_version"] as? JsonPrimitive)?.intOrNull != 1 || value.text("status") != "done") return "TEAM_EVIDENCE_NOT_DONE"
        if (value.text("acceptance_id") != acceptance) return "TEAM_EVIDENCE_ACCEPTANCE_CHANGED"
        val baseline = value["baseline"] as? JsonObject ?: return "TEAM_EVIDENCE_BASELINE_MISSING"
        if (baseline.text("run_id") != run || baseline.text("source_revision").isNullOrBlank()) return "TEAM_EVIDENCE_RUN_MISMATCH"
        val failures = value["failures"] as? JsonArray ?: return "TEAM_EVIDENCE_FAILURES_MISSING"
        val unverified = value["unverified"] as? JsonArray ?: return "TEAM_EVIDENCE_UNVERIFIED_MISSING"
        if (failures.isNotEmpty() || unverified.isNotEmpty()) return "TEAM_EVIDENCE_BLOCKED_OR_UNVERIFIED"
        val refs = value["evidence_refs"] as? JsonArray ?: return "TEAM_EVIDENCE_REFS_MISSING"
        if (refs.isEmpty()) return "TEAM_EVIDENCE_REFS_MISSING"
        val ids = hashSetOf<String>()
        for (raw in refs) {
            val ref = raw as? JsonObject ?: return "TEAM_EVIDENCE_REF_INVALID"
            val id = ref.text("id") ?: return "TEAM_EVIDENCE_REF_INVALID"
            if (id.isBlank() || !ids.add(id) || ref.text("ref").isNullOrBlank() || ref.text("observation").isNullOrBlank() ||
                ref.text("sha256")?.matches(Regex("[a-f0-9]{64}")) != true) return "TEAM_EVIDENCE_REF_INVALID"
            when (ref.text("kind")) {
                "source" -> Unit
                "command" -> if (ref.text("command").isNullOrBlank() || (ref["exit_code"] as? JsonPrimitive)?.intOrNull != 0) return "TEAM_EVIDENCE_FAILED_COMMAND"
                else -> return "TEAM_EVIDENCE_REF_INVALID"
            }
        }
        return null
    }
}
