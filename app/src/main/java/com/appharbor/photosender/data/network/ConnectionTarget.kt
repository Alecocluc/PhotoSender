package com.appharbor.photosender.data.network

private const val DEFAULT_SERVER_PORT = 3210
private const val MIN_PORT = 1
private const val MAX_PORT = 65535

data class ConnectionTarget(
    val host: String,
    val port: Int = DEFAULT_SERVER_PORT,
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
    val hostPort = withoutScheme.substringBefore('/').substringBefore('?').trim()
    if (hostPort.isEmpty()) return null

    val host = hostPort.substringBefore(':').trim()
    val port = hostPort.substringAfter(':', DEFAULT_SERVER_PORT.toString()).toIntOrNull()
        ?: return null

    if (!isValidIpv4(host) || port !in MIN_PORT..MAX_PORT) return null
    return ConnectionTarget(host = host, port = port)
}

fun baseUrlForConnectionTarget(input: String): String? = parseConnectionTarget(input)?.baseUrl

private fun isValidIpv4(host: String): Boolean {
    val parts = host.split(".")
    if (parts.size != 4) return false
    return parts.all { part ->
        if (part.isEmpty() || part.length > 3) return@all false
        val value = part.toIntOrNull() ?: return@all false
        value in 0..255
    }
}
