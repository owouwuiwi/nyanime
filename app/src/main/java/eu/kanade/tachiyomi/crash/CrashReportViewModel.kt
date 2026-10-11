package eu.kanade.tachiyomi.crash

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import eu.kanade.tachiyomi.util.CrashLogUtil

internal class CrashReportViewModel(application: Application) : AndroidViewModel(application) {
    val report = CrashReportPreparation(viewModelScope) { exception ->
        CrashLogUtil(application).prepareReport(exception, includeExtensionInfo = false)
    }
}
