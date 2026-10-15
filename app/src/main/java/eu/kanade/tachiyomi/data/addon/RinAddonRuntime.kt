package eu.kanade.tachiyomi.data.addon

import android.app.Activity
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Bundle
import android.view.LayoutInflater
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.rin.RinArchive
import eu.kanade.tachiyomi.data.rin.RinLoading
import eu.kanade.tachiyomi.data.rin.RinRecord
import eu.kanade.tachiyomi.data.rin.RinRepository
import eu.kanade.tachiyomi.data.rin.RinStorage
import eu.kanade.tachiyomi.util.system.ChildFirstPathClassLoader
import kotlinx.coroutines.flow.MutableStateFlow
import nyanime.addon.api.RinAddon
import nyanime.addon.api.RinAddonHost
import uy.kohesive.injekt.api.get
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

/** Generic trusted-code runtime. The bundle is authenticated before any resource or code is loaded. */
internal object RinAddonRuntime {
    data class Loaded(val record: RinRecord, val entry: RinAddon, val context: Context, val host: RinAddonHost)
    private val loaded = mutableMapOf<String, Loaded>()
    val busy = MutableStateFlow(false)
    fun namespace(record: RinRecord) =
        "rin-addon-" +
            RinArchive.sha256((record.manifest.packageName + ":" + record.storagePublisher).toByteArray()).take(32)
    fun active(
        context: Context,
        packageName: String,
    ): RinRecord = requireNotNull(RinStorage(context).record(packageName)).also {
        require(
            it.enabled &&
                eu.kanade.tachiyomi.extension.ExtensionPackageConsent.trusted(it) &&
                it.manifest.medium == "addon" &&
                RinRepository.compatible(it.manifest),
        )
    }

    @Synchronized fun load(context: Context, packageName: String): Loaded {
        val record = active(context, packageName)
        loaded[record.sha256]?.let { return it }
        val file = RinStorage(context).file(record)
        require(eu.kanade.tachiyomi.extension.ExtensionPackageArchive.verify(context, file, record) == record.manifest)
        val loader = ChildFirstPathClassLoader(file.absolutePath, null, context.classLoader, addon = true)
        val entry = RinLoading.guard {
            Class.forName(
                record.manifest.entryPoints.single(),
                false,
                loader,
            ).getDeclaredConstructor().newInstance() as RinAddon
        }
        val host = Handle(context.applicationContext, record)
        val isolated = RinAddonContext(context.applicationContext, record, loader, host)
        return Loaded(record, entry, isolated, host).also { loaded[record.sha256] = it }
    }
    fun open(context: Context, packageName: String): Boolean = runCatching {
        active(context, packageName)
        context.startActivity(
            Intent(context, RinAddonActivity::class.java).putExtra("addon", packageName).apply {
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
        true
    }.getOrDefault(false)
    fun stop(context: Context, packageName: String) {
        val record = RinStorage(context).record(packageName) ?: return
        WorkManager.getInstance(context).cancelAllWorkByTag(namespace(record))
    }
    class Handle(private val context: Context, private val record: RinRecord) : RinAddonHost {
        private var activity = WeakReference<RinAddonActivity>(null)
        fun attach(activity: RinAddonActivity) {
            this.activity = WeakReference(activity)
        }
        private fun authorize() {
            require(active(context, record.manifest.packageName).sha256 == record.sha256)
        }
        override fun enabled() = runCatching {
            authorize()
            true
        }.getOrDefault(false)
        override fun storageNamespace() = namespace(record)
        override fun appearance(): Bundle {
            authorize()
            return Bundle().apply {
                putString(
                    "mode",
                    uy.kohesive.injekt.Injekt.get<eu.kanade.domain.ui.ThemeSettingsRepository>().current().mode.name,
                )
                putString("locale", context.resources.configuration.locales.toLanguageTags())
            }
        }
        override fun reopen(): PendingIntent = PendingIntent.getActivity(
            context,
            storageNamespace().hashCode(),
            Intent(context, RinAddonActivity::class.java).putExtra("addon", record.manifest.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        override fun stopTask(name: String): PendingIntent {
            authorize()
            require(name.matches(Regex("[A-Za-z0-9_.-]{1,64}")))
            return PendingIntent.getService(
                context,
                (storageNamespace() + name).hashCode(),
                Intent(
                    context,
                    RinAddonForegroundService::class.java,
                ).setAction("cancel").putExtra("addon", record.manifest.packageName).putExtra("task", name),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        override fun close() {
            activity.get()?.finish()
        }
        override fun requestNotifications(result: Consumer<Boolean>) {
            activity.get()?.notifications(result)
                ?: result.accept(false)
        }
        override fun startTask(name: String, arguments: Bundle, initial: Notification) {
            authorize()
            require(name.matches(Regex("[A-Za-z0-9_.-]{1,64}")))
            check(busy.compareAndSet(false, true))
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, RinAddonForegroundService::class.java)
                        .putExtra(
                            "addon",
                            record.manifest.packageName,
                        ).putExtra("task", name).putExtra("args", arguments).putExtra("initial", initial),
                )
            } catch (
                error: Exception,
            ) {
                busy.value =
                    false
                throw error
            }
        }
        override fun scheduleTask(name: String, hours: Int, enabled: Boolean) {
            require(name.matches(Regex("[A-Za-z0-9_.-]{1,64}")))
            val manager = WorkManager.getInstance(context)
            val key = storageNamespace() + "." + name
            if (!enabled) {
                manager.cancelUniqueWork(key)
                return
            }
            authorize()
            require(hours in 1..168)
            manager.enqueueUniquePeriodicWork(
                key,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<RinAddonWorker>(hours.toLong(), TimeUnit.HOURS)
                    .setInputData(
                        workDataOf("addon" to record.manifest.packageName, "task" to name),
                    ).addTag(storageNamespace())
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES).build(),
            )
        }
    }
}

internal class RinAddonContext(
    base: Context,
    private val record: RinRecord,
    private val loader: ClassLoader,
    private val host: RinAddonHost,
) : ContextWrapper(base), nyanime.addon.api.RinAddonContext {
    private val ownRoot = File(base.noBackupFilesDir, host.storageNamespace()).apply { mkdirs() }
    private val ownCache = File(base.cacheDir, host.storageNamespace()).apply { mkdirs() }
    private val merged by lazy {
        if (record.format == "apk") {
            val info = eu.kanade.tachiyomi.extension.ExtensionPackageArchive.inspect(
                base,
                RinStorage(base).file(record),
            ).info
            // Separate resource tables prevent the usual APK package ID from shadowing host resources.
            return@lazy base.packageManager.getResourcesForApplication(requireNotNull(info.applicationInfo))
        }
        // Distinct addon package IDs retain both host framework resources and addon-owned IDs.
        val app = ApplicationInfo(base.applicationInfo).apply {
            val path = RinStorage(base).file(record).absolutePath
            splitSourceDirs = (base.applicationInfo.splitSourceDirs.orEmpty().toList() + path).toTypedArray()
            splitPublicSourceDirs =
                (base.applicationInfo.splitPublicSourceDirs.orEmpty().toList() + path).toTypedArray()
        }
        val resources = base.packageManager.getResourcesForApplication(app)
        @Suppress("DEPRECATION")
        Resources(resources.assets, base.resources.displayMetrics, base.resources.configuration)
    }
    private val apkTheme by lazy {
        resources.newTheme().apply {
            val mode = host.appearance().getString("mode")
            val light = mode == "LIGHT" ||
                mode != "DARK" &&
                resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_YES
            applyStyle(
                if (light) {
                    android.R.style.Theme_Material_Light_NoActionBar
                } else {
                    android.R.style.Theme_Material_NoActionBar
                },
                true,
            )
        }
    }
    override fun getTheme(): Resources.Theme = if (record.format == "apk") apkTheme else super.getTheme()
    override fun getApplicationContext(): Context = this
    override fun getClassLoader() = loader
    override fun getResources(): Resources = merged
    override fun getAssets() = resources.assets
    override fun getFilesDir() = ownRoot
    override fun getNoBackupFilesDir() = ownRoot
    override fun getCacheDir() = ownCache
    private fun ownFile(name: String): File {
        require(name.matches(Regex("[A-Za-z0-9_.-]{1,120}")) && name !in setOf(".", ".."))
        return File(ownRoot, name)
    }
    override fun openFileInput(name: String) = java.io.FileInputStream(ownFile(name))
    override fun openFileOutput(name: String, mode: Int): java.io.FileOutputStream {
        require(mode == Context.MODE_PRIVATE || mode == Context.MODE_APPEND)
        return java.io.FileOutputStream(ownFile(name), mode == Context.MODE_APPEND)
    }
    override fun deleteFile(name: String) = ownFile(name).delete()
    override fun fileList() = ownRoot.list().orEmpty()
    override fun getDir(
        name: String,
        mode: Int,
    ): File {
        require(mode == Context.MODE_PRIVATE)
        return ownFile("dir-$name").apply { mkdirs() }
    }
    override fun getDatabasePath(name: String): File {
        val safe = ownFile(name).name
        return File(File(ownRoot, "databases").apply { mkdirs() }, safe)
    }
    override fun openOrCreateDatabase(
        name: String,
        mode: Int,
        factory: android.database.sqlite.SQLiteDatabase.CursorFactory?,
    ): android.database.sqlite.SQLiteDatabase =
        openOrCreateDatabase(name, mode, factory, null)
    override fun openOrCreateDatabase(
        name: String,
        mode: Int,
        factory: android.database.sqlite.SQLiteDatabase.CursorFactory?,
        handler: android.database.DatabaseErrorHandler?,
    ): android.database.sqlite.SQLiteDatabase {
        require(mode == Context.MODE_PRIVATE)
        return android.database.sqlite.SQLiteDatabase.openDatabase(
            getDatabasePath(name).absolutePath,
            factory,
            android.database.sqlite.SQLiteDatabase.CREATE_IF_NECESSARY,
            handler,
        )
    }
    override fun deleteDatabase(
        name: String,
    ) = android.database.sqlite.SQLiteDatabase.deleteDatabase(getDatabasePath(name))
    override fun databaseList() = File(ownRoot, "databases").list().orEmpty()
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        require(name.matches(Regex("[A-Za-z0-9_.-]{1,80}")))
        return RinAddonPreferences.get(File(ownRoot, "$name.json"))
    }
    override fun createConfigurationContext(overrideConfiguration: Configuration): Context =
        RinAddonContext(baseContext.createConfigurationContext(overrideConfiguration), record, loader, host)
    override fun addonHost(): RinAddonHost = host
    override fun getSystemService(name: String): Any? = when (name) {
        Context.LAYOUT_INFLATER_SERVICE -> LayoutInflater.from(baseContext).cloneInContext(this)
        else -> super.getSystemService(name)
    }
}
