package eu.kanade.tachiyomi.data.addon

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import eu.kanade.tachiyomi.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.function.Consumer

/** The only Android UI component for all RIN addons; their own view owns the presentation. */
class RinAddonActivity : ComponentActivity() {
    private var permissionReply: Consumer<Boolean>? = null
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionReply?.accept(it)
        permissionReply = null
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val packageName = intent.getStringExtra("addon") ?: return finish()
        lifecycleScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) { RinAddonRuntime.load(this@RinAddonActivity, packageName) }
                (loaded.host as RinAddonRuntime.Handle).attach(this@RinAddonActivity)
                setContentView(
                    eu.kanade.tachiyomi.data.rin.RinLoading.guard {
                        loaded.entry.createView(this@RinAddonActivity, loaded.context, loaded.host)
                    },
                )
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (_: Exception) {
                setContentView(
                    android.widget.LinearLayout(this@RinAddonActivity).apply {
                        orientation = android.widget.LinearLayout.VERTICAL
                        setPadding(32, 80, 32, 32)
                        addView(
                            android.widget.TextView(context).apply {
                                text = getString(R.string.addon_incompatible)
                                textSize =
                                    18f
                            },
                        )
                        addView(
                            android.widget.Button(context).apply {
                                text = getString(android.R.string.ok)
                                setOnClickListener { finish() }
                            },
                        )
                    },
                )
            }
        }
    }
    internal fun notifications(result: Consumer<Boolean>) {
        if (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            result.accept(true)
        } else if (permissionReply ==
            null
        ) {
            permissionReply = result
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            result.accept(false)
        }
    }
    override fun onDestroy() {
        permissionReply?.accept(false)
        permissionReply = null
        super.onDestroy()
    }
}
