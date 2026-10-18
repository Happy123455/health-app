package com.happypipaliya.mealtracker

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.happypipaliya.mealtracker.ui.AppRoot
import com.happypipaliya.mealtracker.ui.MealTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    /** Meal to open, e.g. after tapping a notification. */
    private val openMeal = mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handle(intent)
        setContent { MealTheme { AppRoot(openMeal) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        UiRefresh.onResume(this)
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        intent.getLongExtra(EXTRA_MEAL, -1L).takeIf { it > 0 }?.let { openMeal.value = it }
        if (intent.action == Intent.ACTION_SEND) {
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(::import)
        }
        // Debug builds only: lets a test photo in app storage be analysed from adb.
        if (BuildConfig.DEBUG) intent.getStringExtra("debug_import")?.let { import(Uri.fromFile(File(filesDir, it))) }
    }

    private fun import(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            val id = Importer.import(applicationContext, uri, null, null)
            Work.scanNow(applicationContext)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, if (id != null) "Meal added, analysing…" else "Couldn't read that photo", Toast.LENGTH_SHORT).show()
                if (id != null) openMeal.value = id
            }
        }
    }

    companion object {
        const val EXTRA_MEAL = "meal_id"
    }
}

/** The "Log meal" launcher icon: opens the camera, saves into the meals album, then analyses. */
class CaptureActivity : ComponentActivity() {
    private var pending: Uri? = null

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        pending?.let { uri ->
            if (saved) {
                MealCapture.complete(applicationContext, uri)
                Toast.makeText(this, "Got it, analysing your meal…", Toast.LENGTH_SHORT).show()
            } else {
                MealCapture.discard(applicationContext, uri)
            }
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pending = savedInstanceState?.getString("uri")?.toUri()
        if (savedInstanceState == null) {
            val uri = MealCapture.newPhotoUri(this)
            if (uri == null) {
                Toast.makeText(this, "Couldn't open the camera", Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            pending = uri
            takePicture.launch(uri)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("uri", pending?.toString())
    }
}

/** Shown by Health Connect when the user asks how this app uses their health data. */
class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MealTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text("How Meal Tracker uses your health data", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "Meal Tracker reads your steps, workouts, calories burned, heart rate, sleep, weight and height " +
                                "from Health Connect. It shows them next to your meals and uses them to tailor food suggestions.",
                        )
                        Text(
                            "Everything is stored only on this phone. When a meal photo is analysed, the photo and a short " +
                                "summary of your day (for example today's steps and workouts, and your profile) are sent to " +
                                "Google's Gemini API.",
                        )
                        Text(
                            "Each analysed meal is also written to Health Connect as a nutrition entry, so it appears in " +
                                "Samsung Health's food log. You can turn this off in Settings.",
                        )
                        Button(onClick = { finish() }) { Text("Close") }
                    }
                }
            }
        }
    }
}
