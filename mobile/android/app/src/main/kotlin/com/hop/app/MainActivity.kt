package com.hop.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.hop.app.theme.HopTheme

/**
 * [ComponentActivity], not [androidx.appcompat.app.AppCompatActivity] -- this
 * is a pure-Compose activity (Stage 1's whole UI, and every later slice's),
 * so there's no reason to carry AppCompat's View-system machinery into it.
 *
 * Hosts a single [HopNavHost] composable; every screen below it obtains its
 * dependencies via [HopApplication.container], never by constructing them
 * itself.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as HopApplication).container

        setContent {
            HopTheme {
                HopNavHost(container = container)
            }
        }
    }
}
