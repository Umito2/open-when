package com.openwhen.app.presentation

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.openwhen.app.R

internal suspend fun googleIdToken(context: Context): String {
    val clientIdResource = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
    check(clientIdResource != 0) { context.getString(R.string.google_configuration) }
    val option = GetGoogleIdOption.Builder()
        .setServerClientId(context.getString(clientIdResource))
        .setFilterByAuthorizedAccounts(false)
        .setAutoSelectEnabled(false)
        .build()
    val response = try {
        CredentialManager.create(context).getCredential(
            context = context,
            request = GetCredentialRequest.Builder().addCredentialOption(option).build(),
        )
    } catch (failure: NoCredentialException) {
        throw IllegalStateException(context.getString(R.string.google_no_account), failure)
    }
    val credential = response.credential
    check(credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
        "Google couldn't finish signing you in. Please try again."
    }
    return GoogleIdTokenCredential.createFrom(credential.data).idToken
}
