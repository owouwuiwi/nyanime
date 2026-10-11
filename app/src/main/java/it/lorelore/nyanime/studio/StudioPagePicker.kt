package it.lorelore.nyanime.studio

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import eu.kanade.tachiyomi.R
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class StudioPickedElement(
    val selector: String,
    val similarSelector: String,
    val attribute: String = "text",
    val matches: Int = 1,
    val pageUrl: String = "",
    val text: String = "",
    val value: String = "",
)

/** A source-owned browsing session. The bridge can only return a bounded selector to this dialog. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun StudioPagePicker(
    source: StudioSource,
    boundary: String?,
    initialAddress: String = source.baseUrl,
    field: String? = null,
    instruction: String? = null,
    startPicking: Boolean = true,
    onDismiss: () -> Unit,
    onPick: (StudioPickedElement) -> Unit,
) {
    var address by rememberSaveable { mutableStateOf(initialAddress) }
    var web by remember { mutableStateOf<WebView?>(null) }
    var blocked by remember { mutableStateOf(false) }
    var picking by rememberSaveable { mutableStateOf(startPicking) }
    var candidate by remember { mutableStateOf<StudioPickedElement?>(null) }
    val pick by rememberUpdatedState(onPick)
    fun allowed(url: String): Boolean = runCatching {
        val target = Uri.parse(url)
        target.scheme in setOf("http", "https") &&
            target.userInfo == null &&
            (source.origins + source.baseUrl).any { raw ->
                val origin = Uri.parse(raw)
                origin.scheme == target.scheme &&
                    origin.host == target.host &&
                    origin.port == target.port
            }
    }.getOrDefault(false)
    val script = remember(boundary, field) {
        val limit = JsonPrimitive(boundary.orEmpty()).toString()
        val target = JsonPrimitive(field.orEmpty()).toString()
        """
        (function(){
          if(window.__studioPick) return;
          window.__studioPick=true;
          const boundary=$limit;
          const field=$target;
          function segment(el){
            let s=el.tagName.toLowerCase();
            let cls=Array.from(el.classList)
                .filter(c=>/^[a-zA-Z][a-zA-Z0-9_-]*${'$'}/.test(c)&&!/(active|selected|hover)/i.test(c)).slice(0,3);
            return s+cls.map(c=>'.'+CSS.escape(c)).join('');
          }
          document.addEventListener('click', function(e){
            if(window.__studioSelect===false)return;
            e.preventDefault();e.stopPropagation();
            let el=e.target;if(!(el instanceof Element))return;
            if(field==='url'||field==='entry'||field==='filter'||field==='next'||(!boundary&&el.tagName==='IMG'))el=el.closest('a[href]')||el;
            if(field==='cover')el=el.tagName==='IMG'?el:(el.querySelector('img')||el);
            if(field==='media')el=el.closest('video')||el.querySelector('video,source')||el;
            if(field==='media'&&el.tagName==='VIDEO'&&!el.getAttribute('src'))el=el.querySelector('source[src]')||el;
            if(field==='card'){
              let p=el.closest('a[href]')||el;
              el=p;
              for(let i=0;p&&p!==document.body&&i<6;i++,p=p.parentElement){
                const same=p.parentElement?Array.from(p.parentElement.children).filter(n=>n.matches(segment(p))):[];
                if(same.length>1&&(p.matches('a[href]')||p.querySelector('a[href]'))&&p.querySelector('img')){el=p;break;}
              }
            }
            let root=null;try{root=boundary?el.closest(boundary):null;}catch(_){}
            let parts=[],p=el;
            while(p&&p!==document.body&&p!==root&&parts.length<7){
              let s=segment(p);
              if(p!==el&&p.id&&document.querySelectorAll('#'+CSS.escape(p.id)).length===1){parts.unshift('#'+CSS.escape(p.id));break;}
              parts.unshift(s);p=p.parentElement;
            }
            const relative=el===root?segment(el):parts.join(' > ');
            const similar=relative||segment(el);
            let matches=[];try{matches=Array.from(document.querySelectorAll(similar)).slice(0,100);}catch(_){}
            matches.forEach(m=>{m.style.outline='2px dashed #42bce5';m.style.outlineOffset='2px';});
            el.style.outline='3px solid #42bce5';el.style.outlineOffset='3px';
            const attribute=['url','entry','filter','next'].includes(field)?'href':field==='cover'?
              (el.getAttribute('src')?'src':'data-src'):field==='media'?'src':el.tagName==='IMG'?'alt':'text';
            const value=attribute==='text'?el.textContent:(el.getAttribute(attribute)||'');
            NyaStudio.select(JSON.stringify({selector:relative||similar,similarSelector:root?relative:similar,
                attribute:attribute,matches:matches.length,text:(el.textContent||'').trim().slice(0,256),value:value.slice(0,2048)}));
          },true);
        })();
        """.trimIndent()
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding()) {
                Row(Modifier.padding(horizontal = 12.dp)) {
                    OutlinedTextField(address, {
                        address = it
                    }, Modifier.weight(1f), singleLine = true, label = {
                        Text(stringResource(R.string.studio_address))
                    })
                    TextButton({
                        if (allowed(address)) {
                            blocked = false
                            web?.loadUrl(address)
                        } else {
                            blocked = true
                        }
                    }) { Text("↗") }
                    TextButton(onDismiss) { Text(stringResource(R.string.studio_close)) }
                }
                Text(
                    if (blocked) {
                        stringResource(R.string.studio_navigation_blocked)
                    } else {
                        instruction
                            ?: stringResource(R.string.studio_pick_hint)
                    },
                    Modifier.padding(12.dp),
                    color = if (blocked) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Row(Modifier.padding(horizontal = 12.dp)) {
                    FilterChip(!picking, {
                        picking = false
                        candidate = null
                        web?.evaluateJavascript("window.__studioSelect=false", null)
                    }, { Text(stringResource(R.string.studio_navigate)) })
                    FilterChip(picking, {
                        picking = true
                        web?.evaluateJavascript("window.__studioSelect=true", null)
                    }, { Text(stringResource(R.string.studio_pick)) })
                }
                candidate?.let { selected ->
                    Row(Modifier.padding(horizontal = 12.dp)) {
                        Text(
                            "${selected.similarSelector} · ${selected.matches}",
                            Modifier.weight(1f),
                            maxLines = 2,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button({ pick(selected) }) { Text(stringResource(R.string.studio_save)) }
                    }
                }
                AndroidView(factory = { context ->
                    WebView(context).apply {
                        if (WebViewFeature.isFeatureSupported(
                                WebViewFeature.MULTI_PROFILE,
                            )
                        ) {
                            WebViewCompat.setProfile(this, "source-studio-${source.uuid}")
                        }
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.setSupportMultipleWindows(false)
                        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        addJavascriptInterface(
                            object {
                                @JavascriptInterface fun select(raw: String) {
                                    if (raw.length > 8192) return
                                    val item =
                                        runCatching {
                                            StudioDocuments.json.parseToJsonElement(raw).jsonObject
                                        }.getOrNull()
                                            ?: return
                                    val selected =
                                        item["selector"]?.studioText()?.takeIf { it.length in 1..4096 } ?: return
                                    val similar =
                                        item["similarSelector"]?.studioText()?.takeIf { it.length in 1..4096 }
                                            ?: selected
                                    val attribute =
                                        item["attribute"]?.studioText()?.takeIf {
                                            it in
                                                setOf("text", "href", "src", "data-src", "alt")
                                        }
                                            ?: "text"
                                    val matches = item["matches"]?.jsonPrimitive?.intOrNull?.coerceIn(0, 100) ?: 1
                                    val text = item["text"]?.studioText().orEmpty().take(256)
                                    val value = item["value"]?.studioText().orEmpty().take(2048)
                                    post {
                                        val current = url.orEmpty().substringBefore('#')
                                        if (allowed(current)) {
                                            candidate =
                                                StudioPickedElement(
                                                    selected,
                                                    similar,
                                                    attribute,
                                                    matches,
                                                    current,
                                                    text,
                                                    value,
                                                )
                                        }
                                    }
                                }
                            },
                            "NyaStudio",
                        )
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest,
                            ): Boolean = !allowed(request.url.toString()).also {
                                if (!it) {
                                    blocked =
                                        true
                                }
                            }
                            override fun onPageFinished(
                                view: WebView,
                                url: String,
                            ) {
                                if (allowed(url)) {
                                    address = url
                                    candidate = null
                                    view.evaluateJavascript(script, null)
                                    view.evaluateJavascript("window.__studioSelect=$picking", null)
                                }
                            }
                        }
                        if (allowed(address)) loadUrl(address)
                        web = this
                    }
                }, modifier = Modifier.weight(1f), onRelease = { view ->
                    web = null
                    view.stopLoading()
                    view.removeJavascriptInterface("NyaStudio")
                    view.destroy()
                })
            }
        }
    }
}
