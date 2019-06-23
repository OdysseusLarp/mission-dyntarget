package com.odysseuslarp.dyntarget

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.Transformations
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability

private const val REQUEST_LOCATION_PERMISSION = 1
private const val REQUEST_GOOGLE_SERVICES = 2

class MainActivity : AppCompatActivity() {

    private val trackingService = MutableLiveData<TrackingService?>()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            trackingService.value = (service as TrackingService.LocalBinder).getService()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            trackingService.value = null
        }
    }

    private val active = Transformations.switchMap(trackingService) {
        it?.running
    }

    private val lastLocation = Transformations.switchMap(trackingService) {
        it?.lastLocation
    }

    private val shouldTrackLocation = MutableLiveData<Boolean?>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        checkGoogleServices()
        bindService(Intent(this, TrackingService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQUEST_LOCATION_PERMISSION)
        }

        trackingService.observe(this, Observer {
            wrangleService()
        })

        shouldTrackLocation.observe(this, Observer {
            wrangleService()
        })

        val statusLabel = findViewById<TextView>(R.id.status)
        active.observe(this, Observer {
            statusLabel.text = when (it) {
                true -> "Tracking"
                false -> "Idle"
                null -> ""
            }
            if (it != null && shouldTrackLocation.value == null) {
                shouldTrackLocation.value = it
            }
            wrangleService()
        })

        val coords = findViewById<TextView>(R.id.coords)
        lastLocation.observe(this, Observer {
            coords.text = it?.run { "lat $latitude; lon $longitude" }
        })


    }

    fun onButtonPressed(view: View?) {
        shouldTrackLocation.apply {
            value?.let { value = !it }
        }
    }

    private fun checkGoogleServices() {
        GoogleApiAvailability.getInstance().apply {
            when (val result = isGooglePlayServicesAvailable(this@MainActivity)) {
                ConnectionResult.SUCCESS -> wrangleService()
                else -> getErrorDialog(this@MainActivity, result, REQUEST_GOOGLE_SERVICES)?.show()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == REQUEST_GOOGLE_SERVICES) {
            checkGoogleServices()
        }
    }

    private fun wrangleService() {
        if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this) == ConnectionResult.SUCCESS) {
            trackingService.value?.apply {
                val shouldRun = shouldTrackLocation.value ?: return
                val running = running.value == true
                when {
                    shouldRun && !running -> startService(Intent(this, TrackingService::class.java))
                    !shouldRun && running -> stop()
                }
            }
        }
    }
}
