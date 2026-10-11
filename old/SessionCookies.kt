package jp.muya.xsaver

object SessionCookies {
    // CookieManager returns a Cookie header, not a Netscape cookie export.
    fun fromHeader(header: String, service: String = "x", domain: String = "x.com", checkAuth: Boolean = true): String {
        require(header.length <= 256 * 1024)
        val cookies = header.split(';').mapNotNull { part ->
            val pair = part.trim().split('=', limit = 2)
            if (pair.size != 2 || !pair[0].matches(Regex("[A-Za-z0-9_-]+")) ||
                pair[1].any { it == '\t' || it == '\r' || it == '\n' }) null
            else pair[0] to pair[1]
        }.toMap()
        if (checkAuth) {
            if (service == "x") require(!cookies["auth_token"].isNullOrBlank() && !cookies["ct0"].isNullOrBlank())
            else require(MediaLink.site(service).authCookies.any { !cookies[it].isNullOrBlank() })
        }
        require(MediaLink.site(service).owns(domain))
        return "# Netscape HTTP Cookie File\n" + cookies.entries.joinToString("\n") {
            ".$domain\tTRUE\t/\tTRUE\t0\t${it.key}\t${it.value}"
        } + "\n"
    }

    fun filter(text: String, service: String = "x"): String {
        require(text.startsWith("# Netscape HTTP Cookie File") || text.startsWith("# HTTP Cookie File"))
        val rows = text.lineSequence().filter { line ->
            val plain = line.removePrefix("#HttpOnly_")
            if (plain.startsWith("#") || plain.isBlank()) false
            else {
                val columns = plain.split('\t')
                columns.size == 7 && MediaLink.site(service).owns(columns[0].trimStart('.').lowercase())
            }
        }.toList()
        require(rows.isNotEmpty())
        return "# Netscape HTTP Cookie File\n" + rows.joinToString("\n") + "\n"
    }
}
