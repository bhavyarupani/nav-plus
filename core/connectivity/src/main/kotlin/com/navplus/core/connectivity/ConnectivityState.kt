package com.navplus.core.connectivity

enum class ConnectivityState {
    /** Fast, reliable network — all online features available. */
    FULL,

    /** Weak or metered network — prioritise navigation-critical requests only. */
    LIMITED,

    /** No network — local/cached sources only, navigation continues. */
    OFFLINE;

    val isOnline get() = this != OFFLINE
    val isFull   get() = this == FULL
}
