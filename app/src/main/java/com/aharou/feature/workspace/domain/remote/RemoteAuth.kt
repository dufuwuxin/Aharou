package com.aharou.feature.workspace.domain.remote

sealed class RemoteAuth {
    data class Password(val password: String) : RemoteAuth()
    data class PrivateKey(val privateKeyPath: String, val passphrase: String? = null) : RemoteAuth()
}
