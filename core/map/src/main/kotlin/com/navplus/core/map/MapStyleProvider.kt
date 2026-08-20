package com.navplus.core.map

import com.navplus.core.connectivity.ConnectivityState
import java.io.File

object MapStyleProvider {
    /** Free online style — no API key required (OpenFreeMap). */
    private const val ONLINE_STYLE = "https://tiles.openfreemap.org/styles/liberty"

    /** Navigation-optimised style for active guidance. */
    private const val ONLINE_NAV_STYLE = "https://tiles.openfreemap.org/styles/liberty"

    fun styleUrl(
        connectivity: ConnectivityState,
        isNavigating: Boolean,
        offlineMbtilesFile: File? = null,
    ): String {
        if (offlineMbtilesFile != null && offlineMbtilesFile.exists() && !connectivity.isOnline) {
            return "asset://offline_style.json"
        }
        return if (isNavigating) ONLINE_NAV_STYLE else ONLINE_STYLE
    }
}
