package com.ybs.assistant.agent

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import com.ybs.assistant.R
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.util.Locale
import kotlin.coroutines.resume

private val LOCATION_PROVIDERS = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)

/**
 * 현재 위치를 가져오는 도구. ACCESS_FINE_LOCATION은 온보딩에서 미리 받지 않고, 이 도구를 처음
 * 쓰려는 순간 [onRequestPermission]으로 요청한다.
 */
object LocationToolExecutor {

    suspend fun execute(
        context: Context,
        name: String,
        input: JSONObject,
        onRequestPermission: suspend (String) -> Boolean,
    ): String {
        if (name != "get_current_location") return context.getString(R.string.fs_unknown_tool, name)
        if (!ensurePermission(context, onRequestPermission)) {
            return context.getString(R.string.location_permission_denied)
        }
        return getCurrentLocation(context)
    }

    private suspend fun ensurePermission(context: Context, onRequestPermission: suspend (String) -> Boolean): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return true
        }
        return onRequestPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private suspend fun getCurrentLocation(context: Context): String {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val provider = when {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> return context.getString(R.string.location_provider_disabled)
        }

        // getCurrentLocation()은 실내 등 GPS 신호를 못 잡으면 상당한 시간 대기 후 null로 끝날 수 있다 —
        // 그 경우 정확도는 떨어져도 즉시 쓸 수 있는 마지막으로 알려진 위치로 폴백해서 완전히 실패하는
        // 경우를 줄인다("최신 위치를 못 구했지만 그래도 대략적인 위치는 알려줄 수 있다"가 더 유용함).
        val fresh = runCatching {
            suspendCancellableCoroutine { continuation ->
                val cancellationSignal = CancellationSignal()
                continuation.invokeOnCancellation { cancellationSignal.cancel() }
                locationManager.getCurrentLocation(
                    provider,
                    cancellationSignal,
                    context.mainExecutor,
                ) { location -> continuation.resume(location) }
            }
        }.getOrNull()
        val location = fresh ?: lastKnownLocation(locationManager) ?: return context.getString(R.string.location_unavailable)

        val address = runCatching {
            @Suppress("DEPRECATION")
            Geocoder(context, Locale.getDefault())
                .getFromLocation(location.latitude, location.longitude, 1)
                ?.firstOrNull()
                ?.getAddressLine(0)
        }.getOrNull()

        return if (address.isNullOrBlank()) {
            context.getString(R.string.location_result_coords_only, location.latitude, location.longitude)
        } else {
            context.getString(R.string.location_result_with_address, address, location.latitude, location.longitude)
        }
    }

    private fun lastKnownLocation(locationManager: LocationManager): Location? =
        LOCATION_PROVIDERS.mapNotNull { runCatching { locationManager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
}
