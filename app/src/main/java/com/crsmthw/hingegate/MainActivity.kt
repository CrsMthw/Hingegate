package com.crsmthw.hingegate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.crsmthw.hingegate.ui.home.HomeScreen
import com.crsmthw.hingegate.ui.home.HomeViewModel
import com.crsmthw.hingegate.ui.home.HomeViewModelFactory
import com.crsmthw.hingegate.ui.theme.HingegateTheme

/**
 * Edge-to-edge Compose host for the one screen. While started it is also a Window Manager source for the posture
 * monitor: an Activity is the context Window Manager always serves, so with the app open the hinge position is
 * the phone's own even where the extension ignores the overlay and wallpaper contexts.
 */
class MainActivity : ComponentActivity() {

    private val container by lazy { (application as HingegateApplication).container }
    private val viewModel: HomeViewModel by viewModels { HomeViewModelFactory(container) }
    private var attachment: AutoCloseable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            HingegateTheme {
                HomeScreen(viewModel = viewModel, palette = container.crackPalette)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        attachment = container.postureMonitor.attach(this)
    }

    override fun onStop() {
        attachment?.close()
        attachment = null
        super.onStop()
    }
}
