package com.appharbor.pherry.data.network

import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton
import java.net.URLEncoder

/** Adds enrollment identity only to authenticated receiver requests. Names use UTF-8 percent
 * encoding because HTTP header values cannot contain arbitrary Unicode display names. */
@Singleton
class PherryHeaderInterceptor @Inject constructor(
    private val session: PherrySession,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val builder = chain.request().newBuilder()
        val connection = session.connection.get()
        val request = chain.request()
        if (request.header("Authorization") != null && session.deviceName.isNotBlank()) {
            builder.header("X-Device-Name-Encoded", URLEncoder.encode(session.deviceName, "UTF-8").replace("+", "%20"))
        }
        // Public health/enrollment probes never carry an existing receiver's secret.
        if (connection != null && request.url.toString().startsWith(connection.baseUrl + "/v2/")) {
            if (request.header("Authorization") == null) builder.header("Authorization", "Bearer ${connection.credential}")
            builder.header("X-Pherry-Client", session.clientId)
            builder.header("X-Device-Name-Encoded", URLEncoder.encode(session.deviceName, "UTF-8").replace("+", "%20"))
        }
        return chain.proceed(builder.build())
    }
}
