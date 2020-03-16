package com.nickthegroot.wcwp10a

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.Location
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.androidnetworking.AndroidNetworking
import com.androidnetworking.common.Priority
import com.androidnetworking.error.ANError
import com.androidnetworking.interfaces.JSONObjectRequestListener
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.ar.core.Anchor
import com.google.ar.sceneform.AnchorNode
import com.google.ar.sceneform.FrameTime
import com.google.ar.sceneform.math.Quaternion
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.ModelRenderable
import com.google.ar.sceneform.rendering.ViewRenderable
import com.google.ar.sceneform.ux.ArFragment
import com.google.ar.sceneform.ux.TransformableNode
import org.json.JSONObject

// https://www.eia.gov/tools/faqs/faq.php?id=74&t=11
private const val POUNDS_COAL_PER_KWH = 2.21

class MainActivity : AppCompatActivity() {
    private lateinit var fragment: ArFragment
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        AndroidNetworking.initialize(this)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), 10)
        }

        // Get GPS
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        fragment = supportFragmentManager.findFragmentById(R.id.sceneform_fragment) as ArFragment
        fragment.setOnTapArPlaneListener { hitResult, _, _ ->
            val anchor = hitResult.createAnchor()

            ModelRenderable
                .builder()
                .setSource(this, R.raw.solar_battery)
                .build()
                .handle { renderable, throwable ->
                    if (throwable != null) {
                        AlertDialog.Builder(this)
                            .setTitle("Model Render Error")
                            .setMessage(throwable.message)
                            .create()
                            .show()

                        return@handle
                    }

                    getAndAddStats(anchor)
                    addSolarPanelToScene(anchor, renderable)
                }
        }
    }

    private fun addSolarPanelToScene(
        anchor: Anchor?,
        renderable: ModelRenderable?
    ) {
        val node = TransformableNode(fragment.transformationSystem)
        val anchorNode = AnchorNode(anchor)
        node.renderable = renderable
        node.setParent(anchorNode)
        fragment.arSceneView.scene.addChild(anchorNode)
        node.select()
    }

    private fun getAndAddStats(anchor: Anchor) {
        fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
            if (location == null) {
                return@addOnSuccessListener
            }

            AndroidNetworking.get("https://developer.nrel.gov/api/pvwatts/v6.json")
                .addQueryParameter("api_key", BuildConfig.NREL_KEY)
                .addQueryParameter("system_capacity", "4")
                .addQueryParameter("module_type", "0")
                .addQueryParameter("losses", "14")
                .addQueryParameter("array_type", "1")
                .addQueryParameter("tilt", "20") // todo calculate tilt
                .addQueryParameter("azimuth", "180") // todo calc azimuth
                .addQueryParameter("lat", location.latitude.toString())
                .addQueryParameter("lon", location.longitude.toString())
                .setPriority(Priority.IMMEDIATE)
                .build()
                .getAsJSONObject(object: JSONObjectRequestListener {
                    override fun onResponse(response: JSONObject) {
                        val annualPower = response.getJSONObject("outputs").getLong("ac_annual")
                        val annualCoalPounds = annualPower * POUNDS_COAL_PER_KWH

                        addSolarStatsToScene(anchor, annualPower, annualCoalPounds)
                    }

                    override fun onError(anError: ANError?) {
                        TODO("Not yet implemented")
                    }
                })
        }
    }

    @SuppressLint("SetTextI18n")
    private fun addSolarStatsToScene(anchor: Anchor, annualPower: Long, annualCoalPounds: Double) {
        ViewRenderable.builder()
            .setView(this, R.layout.solar_stats)
            .build()
            .thenAccept { renderable ->
                val view = renderable.view
                view.findViewById<TextView>(R.id.ac_power).text = "$annualPower kWH"
                view.findViewById<TextView>(R.id.coal).text = "$annualCoalPounds lbs"

                val node = TransformableNode(fragment.transformationSystem)
                node.renderable = renderable
                node.localPosition = Vector3(0.5f, 0.5f, 0.5f)

                val anchorNode = object: AnchorNode(anchor) {
                    override fun onUpdate(p0: FrameTime?) {
                        // Adapted from: https://creativetech.blog/home/ui-elements-for-arcore-renderable
                        scene?.let {
                            val statsPosition: Vector3 = node.worldPosition
                            val direction = Vector3.subtract(it.camera.worldPosition, statsPosition)
                            direction.y = 0.0f

                            val lookRotation = Quaternion.lookRotation(direction, Vector3.up())
                            node.worldRotation = lookRotation
                        }
                    }
                }

                node.setParent(anchorNode)
                fragment.arSceneView.scene.addChild(anchorNode)
                node.select()
            }
    }
}
