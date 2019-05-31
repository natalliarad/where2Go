package com.natallia.radaman.where2go

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.location.Location
import android.os.Bundle
import android.support.design.widget.FloatingActionButton
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.widget.Toast
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.LocationSettingsStatusCodes
import com.google.android.gms.location.SettingsClient
import com.mapbox.android.core.location.LocationEngine
import com.mapbox.android.core.location.LocationEngineListener
import com.mapbox.android.core.location.LocationEnginePriority
import com.mapbox.android.core.location.LocationEngineProvider
import com.mapbox.android.core.permissions.PermissionsListener
import com.mapbox.android.core.permissions.PermissionsManager
import com.mapbox.api.directions.v5.models.DirectionsResponse
import com.mapbox.api.directions.v5.models.DirectionsRoute
import com.mapbox.geojson.Point
import com.mapbox.mapboxsdk.Mapbox
import com.mapbox.mapboxsdk.annotations.MarkerOptions
import com.mapbox.mapboxsdk.camera.CameraUpdateFactory
import com.mapbox.mapboxsdk.geometry.LatLng
import com.mapbox.mapboxsdk.location.LocationComponent
import com.mapbox.mapboxsdk.location.modes.CameraMode
import com.mapbox.mapboxsdk.maps.MapboxMap
import com.mapbox.mapboxsdk.maps.OnMapReadyCallback
import com.mapbox.services.android.navigation.ui.v5.NavigationLauncher
import com.mapbox.services.android.navigation.ui.v5.NavigationLauncherOptions
import com.mapbox.services.android.navigation.ui.v5.route.NavigationMapRoute
import com.mapbox.services.android.navigation.v5.navigation.NavigationRoute
import kotlinx.android.synthetic.main.activity_main.*
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class MainActivity : AppCompatActivity(),
    PermissionsListener, LocationEngineListener, OnMapReadyCallback, MapboxMap.OnMapClickListener {

    //SettingsClient API to show an AlertDialog box where ask the user to turn on GPS to locate
    // the user’s location.
    val REQUEST_CHECK_SETTINGS = 1
    var settingsClient: SettingsClient? = null

    //variables are used for Mapbox, and are all related to getting the user’s location.
    lateinit var map: MapboxMap
    lateinit var permissionManager: PermissionsManager
    var originLocation: Location? = null

    var locationEngine: LocationEngine? = null
    var locationComponent: LocationComponent? = null

    var navigationMapRoute: NavigationMapRoute? = null
    var currentRoute: DirectionsRoute? = null

    lateinit var navigate: FloatingActionButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Mapbox.getInstance(
            this,
            "YOUR _API_KEY"
        )
        setContentView(R.layout.activity_main)

        mapbox.onCreate(savedInstanceState)
        mapbox.getMapAsync(this)
        settingsClient = LocationServices.getSettingsClient(this)
        btnNavigate.isEnabled = false

        btnNavigate.setOnClickListener {
            val navigationLauncherOptions = NavigationLauncherOptions.builder() //1
                .directionsRoute(currentRoute) //2
                .shouldSimulateRoute(true) //3
                .build()

            NavigationLauncher.startNavigation(this, navigationLauncherOptions) //4
        }
    }

    override fun onExplanationNeeded(permissionsToExplain: MutableList<String>?) {
        Toast.makeText(
            this,
            getString(R.string.explanation_permission),
            Toast.LENGTH_LONG
        ).show()
    }

    override fun onPermissionResult(granted: Boolean) {
        if (granted) {
            enableLocation()
        } else {
            Toast.makeText(this, getString(R.string.not_granted_location), Toast.LENGTH_LONG).show()
            finish()
        }
    }

    /**
     * Pass the user’s latest location to the setCameraPosition method so the map will show
     * the current user’s location all the time.
     */
    override fun onLocationChanged(location: Location?) {
        location?.run {
            originLocation = this
            setCameraPosition(this)
        }
    }

    /**
     * Call locationEngine to track the user’s location
     */
    @SuppressLint("MissingPermission")
    override fun onConnected() {
        locationEngine?.requestLocationUpdates()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        permissionManager.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    override fun onMapReady(mapboxMap: MapboxMap?) {
        // Initialize the map variable
        map = mapboxMap ?: return
        // Initialize locationRequestBuilder and pass the location request that the app will use
        val locationRequestBuilder = LocationSettingsRequest.Builder().addLocationRequest(
            LocationRequest().setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY)
        )
        // Use locationRequest to build location request. Then pass it to settingsClient and
        // attach two types of listeners
        val locationRequest = locationRequestBuilder?.build()

        settingsClient?.checkLocationSettings(locationRequest)?.run {
            // When the request is successful, you’ll call enableLocation to initiate location tracking
            addOnSuccessListener {
                enableLocation()
            }

            // When the request fails, check to see the reason why by looking at the exception
            // status code. If the exception is LocationSettingsStatusCodes.RESOLUTION_REQUIRED,
            // then the app should handle that exception by calling startResolutionForResult
            addOnFailureListener {
                val statusCode = (it as ApiException).statusCode

                if (statusCode == LocationSettingsStatusCodes.RESOLUTION_REQUIRED) {
                    val resolvableException = it as? ResolvableApiException
                    resolvableException?.startResolutionForResult(
                        this@MainActivity,
                        REQUEST_CHECK_SETTINGS
                    )
                }
            }
        }
    }

    override fun onMapClick(point: LatLng) {
        if (!map.markers.isEmpty()) {
            map.clear()
        }

        map.addMarker(
            MarkerOptions().setTitle("I'm a marker :]").setSnippet("This is a snippet about this marker that will show up here").position(
                point
            )
        )

        checkLocation()
        originLocation?.run {
            val startPoint = Point.fromLngLat(longitude, latitude)
            val endPoint = Point.fromLngLat(point.longitude, point.latitude)

            getRoute(startPoint, endPoint)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_CHECK_SETTINGS) {
            if (resultCode == Activity.RESULT_OK) {
                enableLocation()
            } else if (resultCode == Activity.RESULT_CANCELED) {
                finish()
            }
        }
    }

    @SuppressWarnings("MissingPermission")
    override fun onStart() {
        super.onStart()

        if (PermissionsManager.areLocationPermissionsGranted(this)) {
            locationEngine?.requestLocationUpdates()
            locationComponent?.onStart()
        }

        mapbox.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapbox.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapbox.onPause()
    }

    /**
     * The app will stop retrieving the user’s location updates when the onStop method is called.
     */
    override fun onStop() {
        super.onStop()
        locationEngine?.removeLocationUpdates()
        locationComponent?.onStop()
        mapbox.onStop()
    }

    /**
     * This means that the app will disconnect from locationEngine and will no longer receive any
     * location updates after the onDestroy() method is called.
     */
    override fun onDestroy() {
        super.onDestroy()
        locationEngine?.deactivate()
        mapbox.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapbox.onLowMemory()
    }

    override fun onSaveInstanceState(outState: Bundle?) {
        super.onSaveInstanceState(outState)
        if (outState != null) {
            mapbox.onSaveInstanceState(outState)
        }
    }

    /**
     * Enable location tracking to locate the user’s current location
     */
    private fun enableLocation() {
        if (PermissionsManager.areLocationPermissionsGranted(this)) {
            initializeLocationComponent()
            initializeLocationEngine()
            map.addOnMapClickListener(this)
        } else {
            permissionManager = PermissionsManager(this)
            permissionManager.requestLocationPermissions(this)
        }
    }

    /**
     * Responsible for doing the actual work of locating the user’s location
     */
    @SuppressWarnings("MissingPermission")
    fun initializeLocationEngine() {
        locationEngine = LocationEngineProvider(this).obtainBestLocationEngineAvailable()
        locationEngine?.priority = LocationEnginePriority.HIGH_ACCURACY
        locationEngine?.activate()
        locationEngine?.addLocationEngineListener(this)

        val lastLocation = locationEngine?.lastLocation
        if (lastLocation != null) {
            originLocation = lastLocation
            setCameraPosition(lastLocation)
        } else {
            locationEngine?.addLocationEngineListener(this)
        }
    }

    /**
     * Responsible for doing the actual work of locating the user’s location
     */
    @SuppressWarnings("MissingPermission")
    fun initializeLocationComponent() {
        locationComponent = map.locationComponent
        locationComponent?.activateLocationComponent(this)
        locationComponent?.isLocationComponentEnabled = true
        locationComponent?.cameraMode = CameraMode.TRACKING
    }

    /**
     * Handles zooming in on the user’s location in the map
     */
    fun setCameraPosition(location: Location) {
        map.animateCamera(
            CameraUpdateFactory.newLatLngZoom(
                LatLng(
                    location.latitude,
                    location.longitude
                ), 30.0
            )
        )
    }

    private fun getRoute(originPoint: Point, endPoint: Point) {
        NavigationRoute.builder(this) //1
            .accessToken(Mapbox.getAccessToken()!!) //2
            .origin(originPoint) //3
            .destination(endPoint) //4
            .build() //5
            .getRoute(object : Callback<DirectionsResponse> { //6
                override fun onFailure(call: Call<DirectionsResponse>, t: Throwable) {
                    Log.d("MainActivity", t.localizedMessage)
                }

                override fun onResponse(
                    call: Call<DirectionsResponse>,
                    response: Response<DirectionsResponse>
                ) {
                    if (navigationMapRoute != null) {
                        navigationMapRoute?.updateRouteVisibilityTo(false)
                    } else {
                        navigationMapRoute = NavigationMapRoute(null, mapbox, map)
                    }

                    currentRoute = response.body()?.routes()?.first()
                    if (currentRoute != null) {
                        navigationMapRoute?.addRoute(currentRoute)
                    }

                    btnNavigate.isEnabled = true
                }
            })
    }

    /**
     * Try to set the originLocation field to the last known location, if there isn’t any
     * location present
     */
    @SuppressLint("MissingPermission")
    private fun checkLocation() {
        if (originLocation == null) {
            map.locationComponent.lastKnownLocation?.run {
                originLocation = this
            }
        }
    }
}
