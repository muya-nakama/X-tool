package jp.muya.xsaver

object SessionCookies {
    // CookieManager returns a Cookie header, not a Netscape cookie export.
    fun fromHeader(header: String): String {
        require(header.length <= 256 * 1024)
        val cookies = header.split(';').mapNotNull { part ->
            val pair = part.trim().split('=', limit = 2)
            if (pair.size != 2 || !pair[0].matches(Regex("[A-Za-z0-9_]+")) ||
                pair[1].any { it == '\t' || it == '\r' || it == '\n' }) null
            else pair[0] to pair[1]
        }.toMap()
        require(!cookies["auth_token"].isNullOrBlank() && !cookies["ct0"].isNullOrBlank())
        return "# Netscape HTTP Cookie File\n" + cookies.entries.joinToString("\n") {
            ".x.com\tTRUE\t/\tTRUE\t0\t${it.key}\t${it.value}"
        } + "\n"
    }

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
