package jp.muya.xsaver

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

object NetworkPolicy {
    const val DEFAULT_LIMIT_MB = 100L
    fun limit(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getLong("warningMB", DEFAULT_LIMIT_MB) * 1024 * 1024
    fun trusted(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return !caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
            (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
    }
    fun needsWarning(context: Context, bytes: Long?) = !trusted(context) && (bytes == null || bytes >= limit(context))
}
object EngineGate { val lock = Any() }
