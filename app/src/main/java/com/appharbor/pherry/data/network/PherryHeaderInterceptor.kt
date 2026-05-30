package com.appharbor.pherry.data.network

import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Attaches Pherry's identity headers to every request:
 *  - `X-Device-Name`: a friendly phone name for the desktop's history/sources view.
 *  - `X-Pherry-Token`: the pairing token (when known), required by the desktop for destructive ops.
 *
 * Both are safe to send on every request; the server only enforces the token where it matters.
 */
@Singleton
class PherryHeaderInterceptor @Inject constructor(
    private val session: PherrySession,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val builder = chain.request().newBuilder()
        if (session.deviceName.isNotBlank()) {
            builder.header("X-Device-Name", session.deviceName)
        }
        val token = session.token
        if (token.isNotBlank()) {
            builder.header("X-Pherry-Token", token)
        }
        return chain.proceed(builder.build())
    }
}
