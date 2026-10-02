package com.appharbor.pherry.data.network

private const val DEFAULT_SERVER_PORT = 3210
private const val MIN_PORT = 1
private const val MAX_PORT = 65535

data class ConnectionTarget(
    val host: String,
    val port: Int = DEFAULT_SERVER_PORT,
    /** Pairing code from the desktop QR. Empty for manual/discovered. */
    val token: String = "",
) {
    val endpoint: String
        get() = "$host:$port"

    val baseUrl: String
        get() = "http://$host:$port"
}

fun parseConnectionTarget(input: String): ConnectionTarget? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return null

    val withoutScheme = trimmed
        .removePrefix("http://")
        .removePrefix("https://")
    val hostPortAndQuery = withoutScheme.substringBefore('/')
    val query = hostPortAndQuery.substringAfter('?', "")
    val hostPort = hostPortAndQuery.substringBefore('?').trim()
    if (hostPort.isEmpty()) return null

    val host = hostPort.substringBefore(':').trim()
    val port = hostPort.substringAfter(':', DEFAULT_SERVER_PORT.toString()).toIntOrNull()
        ?: return null

    if (!isValidIpv4(host) || port !in MIN_PORT..MAX_PORT) return null
    return ConnectionTarget(host = host, port = port, token = parseTokenParam(query))
}

/** Pull the `t` query param out of a "k=v&k=v" string; sanitized to the token alphabet. */
private fun parseTokenParam(query: String): String {
    if (query.isEmpty()) return ""
    val raw = query.split('&')
        .firstOrNull { it.startsWith("t=") }
        ?.removePrefix("t=")
        ?: return ""
    return raw.filter { it.isLetterOrDigit() }.take(16)
}

private fun isValidIpv4(host: String): Boolean {
    val parts = host.split(".")
    if (parts.size != 4) return false
    return parts.all { part ->
        if (part.isEmpty() || part.length > 3) return@all false
        val value = part.toIntOrNull() ?: return@all false
        value in 0..255
    }
}
