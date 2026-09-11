package com.cokkles.gpos.platform.security

import android.content.Context
import java.util.UUID

/** Stable, non-secret installation identifier used to name and revoke one AEGIS device session. */
class DeviceIdentityStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "gpos_device_identity",
        Context.MODE_PRIVATE,
    )

    fun id(): String {
        val existing = preferences.getString(KEY_DEVICE_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val created = UUID.randomUUID().toString()
        preferences.edit().putString(KEY_DEVICE_ID, created).commit()
        return created
    }

    private companion object {
        const val KEY_DEVICE_ID = "device_id"
    }
}
