package com.cokkles.gpos.platform.notifications

import android.net.Uri

object DeepLinkRouter {
    const val SCHEME = "gpos"
    const val HOST = "open"

    fun uriFor(target: GposDeepLinkTarget): Uri =
        Uri.Builder()
            .scheme(SCHEME)
            .authority(HOST)
            .appendPath(target.route)
            .build()

    fun resolve(uri: Uri?): GposDeepLinkTarget? {
        if (uri == null) return null
        if (!uri.scheme.equals(SCHEME, ignoreCase = true)) return null
        if (!uri.host.equals(HOST, ignoreCase = true)) return null
        val route = uri.pathSegments.singleOrNull() ?: return null
        return GposDeepLinkTarget.fromRoute(route)
    }
}
