package com.odysseuslarp.dyntarget

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.GeoPoint
import kotlinx.coroutines.*

private const val CHANNEL_ID = "trackSrv"

private const val LOCATION_REQUEST_INTERVAL = 3000L
private const val MIN_DATABASE_UPDATE_INTERVAL = 3000L

class TrackingService : Service() {
    inner class LocalBinder : Binder() {
        fun getService() = this@TrackingService
    }

    private val binder = LocalBinder()

    private val locationsDocument = FirebaseFirestore.getInstance().document("missiondata/locations")

    private val locationClient by lazy {
        LocationServices.getFusedLocationProviderClient(this)
    }

    private val _running = MutableLiveData<Boolean>().apply { value = false }
    private val _lastLocation = MutableLiveData<GeoPoint?>()

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult?) {
            result?.lastLocation?.apply {
                val newLocation = GeoPoint(latitude, longitude)
                val changed = newLocation != _lastLocation.value
                _lastLocation.value = newLocation
                if (changed) setHasNewLocation()
            }
        }
    }

    private var hasNewLocation = false
    private var currentUpdateJob: Job? = null

    val running: LiveData<Boolean> get() = _running
    val lastLocation: LiveData<GeoPoint?> get() = _lastLocation

    override fun onBind(intent: Intent?) = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureNotificationChannel()
        if (running.value != true) {
            doStart()
        }
        return START_STICKY
    }

    fun stop() {
        if (running.value == true) {
            doStop()
        }
    }

    private fun doStart() {
        val hasPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (hasPermission) {
            startForeground(R.id.trackingActiveNotification, buildNotification())
            locationClient.requestLocationUpdates(
                LocationRequest.create()
                    .setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY)
                    .setInterval(LOCATION_REQUEST_INTERVAL),
                locationCallback,
                Looper.getMainLooper()
            )
            _running.value = true
        } else {
            stopSelf()
        }
    }

    private fun doStop() {
        locationClient.removeLocationUpdates(locationCallback)
        stopForeground(true)
        stopSelf()
        _running.value = false
    }

    override fun onDestroy() {
        super.onDestroy()
        locationClient.removeLocationUpdates(locationCallback)
        _running.value = false
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).apply {
                if (getNotificationChannel(CHANNEL_ID) == null) {
                    createNotificationChannel(NotificationChannel(CHANNEL_ID, "Odysseus DynTarget state", NotificationManager.IMPORTANCE_LOW))
                }
            }
        }
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("DynTarget active")
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).setFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                ),
                0
            )
        )
        .build()

    private fun setHasNewLocation() {
        if (!hasNewLocation) {
            hasNewLocation = true
            updateLocationIfNeeded()
        }
    }
    private fun updateLocationIfNeeded() {
        if (currentUpdateJob == null && hasNewLocation) {
            hasNewLocation = false
            val earliestNextUpdateTime = SystemClock.elapsedRealtime() + MIN_DATABASE_UPDATE_INTERVAL
            lastLocation.value?.let {
                currentUpdateJob = MainScope().launch {
                    val success = updateLocation(it)
                    delay(earliestNextUpdateTime - SystemClock.elapsedRealtime())
                    if (!success) hasNewLocation = true
                    currentUpdateJob = null
                    updateLocationIfNeeded()
                }
            }
        }
    }
    private suspend fun updateLocation(location: GeoPoint): Boolean {
        return CompletableDeferred<Boolean>().apply {
            locationsDocument.update("target", location)
                .addOnSuccessListener { complete(true) }
                .addOnFailureListener { complete(false) }
        }.await()
    }
}