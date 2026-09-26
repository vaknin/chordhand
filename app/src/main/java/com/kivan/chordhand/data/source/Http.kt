package com.kivan.chordhand.data.source

import okhttp3.Headers
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class SourceException(message: String, cause: Throwable? = null) : Exception(message, cause)

object Http {
    const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

    val desktopBrowserHeaders: Headers = Headers.Builder()
        .add("User-Agent", DESKTOP_USER_AGENT)
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .add("Accept-Language", "en-US,en;q=0.9")
        .build()

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
