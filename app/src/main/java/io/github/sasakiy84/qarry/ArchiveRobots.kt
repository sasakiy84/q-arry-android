package io.github.sasakiy84.qarry

internal class ArchiveRobots private constructor(private val rules: List<Rule>) {
    fun allows(path: String): Boolean {
        val match = rules.asSequence().filter { it.matches(path) }
            .maxWithOrNull(compareBy<Rule>({ it.specificity }, { it.allow }))
        return match?.allow ?: true
    }

    private class Rule(pattern: String, val allow: Boolean) {
        private val anchored = pattern.endsWith('$')
        private val sections = pattern.removeSuffix("$").split('*')
        val specificity = sections.sumOf { it.length }

        fun matches(path: String): Boolean {
            val first = sections.first()
            if (!path.startsWith(first)) return false
            if (sections.size == 1) return !anchored || path.length == first.length
            var cursor = first.length
            for (index in 1 until sections.size) {
                val segment = sections[index]
                val position = if (anchored && index == sections.lastIndex) {
                    if (!path.endsWith(segment)) return false
                    path.length - segment.length
                } else {
                    path.indexOf(segment, cursor)
                }
                if (position < cursor) return false
                cursor = position + segment.length
            }
            return true
        }
    }

    companion object {
        fun parse(text: String): ArchiveRobots {
            val specific = mutableListOf<Rule>()
            val generic = mutableListOf<Rule>()
            val agents = mutableListOf<String>()
            var hadRule = false
            var hasSpecificGroup = false
            for (line in text.removePrefix("\uFEFF").lineSequence()) {
                val active = line.substringBefore('#')
                val name = active.substringBefore(':').trim().lowercase()
                val value = active.substringAfter(':', "").trim()
                if (name == "user-agent") {
                    if (hadRule) agents.clear()
                    hadRule = false
                    agents.add(value.lowercase())
                    if (value.equals("Q-arry", true)) hasSpecificGroup = true
                } else if (name == "allow" || name == "disallow") {
                    hadRule = true
                    if (!value.startsWith('/')) continue
                    val rule = Rule(value, allow = name == "allow")
                    if ("q-arry" in agents) specific.add(rule)
                    else if ("*" in agents) generic.add(rule)
                }
            }
            return ArchiveRobots(if (hasSpecificGroup) specific else generic)
        }
    }
}
