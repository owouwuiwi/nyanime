package it.lorelore.nyanime.backup.remote

import android.accounts.Account
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** No refresh token, client secret, upstream client ID or application backend. */
internal class DriveBackupAuthorization(private val context: Context) {
    suspend fun request(account: String = ""): AuthorizationResult {
        val request = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE), Scope(EMAIL_SCOPE)))
            .setOptOutIncludingGrantedScopes(true)
        if (account.isNotBlank()) {
            request.setAccount(Account(account, "com.google"))
        } else {
            request.setPrompt(AuthorizationRequest.Prompt.SELECT_ACCOUNT)
        }
        return Identity.getAuthorizationClient(context).authorize(request.build()).awaitRemoteAuthorization()
    }
    fun fromIntent(
        intent: Intent?,
    ): AuthorizationResult = Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(intent)
    suspend fun account(result: AuthorizationResult): String = withContext(Dispatchers.IO) {
        require(result.grantedScopes.containsAll(listOf(SCOPE, EMAIL_SCOPE)))
        val token = requireNotNull(result.accessToken)
        remoteHttpClient().remoteRequest(
            Request.Builder().url("https://www.googleapis.com/oauth2/v2/userinfo?fields=email,verified_email")
                .header("Authorization", "Bearer $token").build(),
        ).use { response ->
            if (response.code == 401) invalidate(token)
            response.requireSuccess()
            val data = boundedRemoteJson(response.readRemoteBytes(16 * 1024).toString(Charsets.UTF_8)).jsonObject
            require(data["verified_email"]?.jsonPrimitive?.booleanOrNull == true)
            requireNotNull(data["email"]?.jsonPrimitive?.contentOrNull).also {
                require(it.length in 3..320 && '@' in it && it.none(Char::isISOControl))
            }
        }
    }
    suspend fun token(account: String): String {
        require(account.isNotBlank())
        val result = request(account)
        if (result.hasResolution() || result.accessToken.isNullOrBlank()) throw RemoteStorageException("authorization")
        require(result.grantedScopes.contains(SCOPE))
        return result.accessToken!!
    }
    suspend fun revoke(account: String) {
        val request = RevokeAccessRequest.builder().setScopes(listOf(Scope(SCOPE), Scope(EMAIL_SCOPE)))
        if (account.isNotBlank()) request.setAccount(Account(account, "com.google"))
        Identity.getAuthorizationClient(context).revokeAccess(request.build()).awaitRemoteAuthorization()
    }
    suspend fun invalidate(token: String) {
        Identity.getAuthorizationClient(context).clearToken(ClearTokenRequest.builder().setToken(token).build())
            .awaitRemoteAuthorization()
    }
    companion object {
        const val SCOPE = "https://www.googleapis.com/auth/drive.appdata"
        private const val EMAIL_SCOPE = "https://www.googleapis.com/auth/userinfo.email"
    }
}

private suspend fun <T> Task<T>.awaitRemoteAuthorization(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
    addOnFailureListener {
        if (continuation.isActive) continuation.resumeWithException(RemoteStorageException("authorization"))
    }
    addOnCanceledListener { continuation.cancel() }
}
