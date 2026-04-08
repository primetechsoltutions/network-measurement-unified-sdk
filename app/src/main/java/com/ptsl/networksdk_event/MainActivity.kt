package com.ptsl.networksdk_event

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.ptsl.network_sdk.NetworkDataUploader
import com.ptsl.networksdk_event.ui.HomeFragment

class MainActivity : AppCompatActivity() {

    // Store the uploader here so fragments can access it
    val networkDataUploader = NetworkDataUploader()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize the SDK once at the Activity/Application level
        networkDataUploader.init(this, "MyBL")

        // Load the HomeFragment as the starting screen
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, HomeFragment())
                .commit()
        }
    }
}
