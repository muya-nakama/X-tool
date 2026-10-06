package jp.muya.xsaver

object SessionCookies {
    fun filter(text: String): String {
        require(text.startsWith("# Netscape HTTP Cookie File") || text.startsWith("# HTTP Cookie File"))
        val rows = text.lineSequence().filter { line ->
            val plain = line.removePrefix("#HttpOnly_")
            if (plain.startsWith("#") || plain.isBlank()) false
            else {
                val columns = plain.split('\t')
                columns.size == 7 && columns[0].trimStart('.').lowercase() in
                    setOf("x.com", "twitter.com", "www.x.com", "www.twitter.com")
            }
        }.toList()
        require(rows.isNotEmpty())
        return "# Netscape HTTP Cookie File\n" + rows.joinToString("\n") + "\n"
    }
}
