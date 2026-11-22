package com.guna.nova

import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.widget.Toast
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * First hybrid-agent tool: turns an inferred food intent into a location-aware
 * Maps search. Delivery-app searches are opened through their HTTPS app links.
 */
object FoodDiscovery {
    fun findNearby(context: Context, query: String = "restaurants and food"): Boolean {
        val location = lastKnownLocation(context)
        val locationText = location?.let { "${it.latitude},${it.longitude}" }
        val search = if (locationText != null) {
            Uri.encode("$query near me")
        } else {
            Uri.encode("$query near my current location")
        }
        val uri = if (locationText != null) {
            Uri.parse("geo:$locationText?q=$search")
        } else {
            Uri.parse("geo:0,0?q=$search")
        }
        return open(context, uri)
    }

    fun searchDeliveryApp(context: Context, app: String, query: String): Boolean {
        val term = query.ifBlank { "food near me" }
        val url = when (app.lowercase()) {
            "swiggy" -> "https://www.swiggy.com/search?query=${Uri.encode(term)}"
            "zomato" -> "https://www.zomato.com/search?query=${Uri.encode(term)}"
            else -> "https://www.google.com/search?q=${Uri.encode("$term food delivery")}"
        }
        return open(context, Uri.parse(url))
    }

    private fun lastKnownLocation(context: Context): Location? {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return null
        return try {
            val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val providers = manager.getProviders(true)
            providers.mapNotNull { provider ->
                try { manager.getLastKnownLocation(provider) } catch (_: SecurityException) { null }
            }.maxByOrNull { it.time }
        } catch (_: Exception) { null }
    }

    private fun open(context: Context, uri: Uri): Boolean = try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: Exception) {
        Toast.makeText(context, "No app is available to open this search.", Toast.LENGTH_LONG).show()
        false
    }
}
