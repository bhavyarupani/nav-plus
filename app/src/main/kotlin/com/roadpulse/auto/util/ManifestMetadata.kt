package com.roadpulse.auto.util

import android.content.Context
import android.content.pm.PackageManager

/** Reads a manifest `<meta-data>` string value, e.g. a secrets-gradle-plugin-injected API key. */
@Suppress("DEPRECATION")
fun manifestMetadataString(
    context: Context,
    key: String,
): String =
    context.packageManager
        .getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
        .metaData
        ?.getString(key)
        .orEmpty()
