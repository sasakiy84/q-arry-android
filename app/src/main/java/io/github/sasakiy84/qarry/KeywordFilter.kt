package io.github.sasakiy84.qarry

enum class KeywordFilterMode(val label: String) {
    Include("包含"),
    Exclude("除外"),
}

data class KeywordFilter(val mode: KeywordFilterMode, val keywords: List<String>) {
    init {
        require(keywords.isNotEmpty() && keywords.none(String::isBlank))
    }

    internal val summary: String get() = "${mode.label} ${keywords.size}件"
}
