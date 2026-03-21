package stravahooks.strava

object ScopeChecker {
    fun hasFullScope(scope: String): Boolean {
        val normalized = scope.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        return normalized.containsAll(REQUIRED_STRAVA_SCOPES.toSet())
    }

    fun scopeSummary(scope: String): String {
        val normalized = scope.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        return if (normalized.isEmpty()) {
            "unknown"
        } else {
            normalized.joinToString(", ")
        }
    }

    fun scopeWarnings(scope: String): String {
        val normalized = scope.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (normalized.isEmpty()) {
            return "Scope warning: missing scope info."
        }
        val warnings = mutableListOf<String>()
        if (!normalized.contains("activity:write")) {
            warnings.add("missing activity:write (can't edit activities)")
        }
        if (!normalized.contains("activity:read_all")) {
            warnings.add("missing activity:read_all (can't access private activities)")
        }
        if (warnings.isEmpty()) {
            return "Scope: full access for edits."
        }
        return "Scope warning: " + warnings.joinToString("; ")
    }
}
