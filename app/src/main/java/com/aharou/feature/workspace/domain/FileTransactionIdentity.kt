package com.aharou.feature.workspace.domain

/** Display aliases are not unique across workspaces or SSH endpoints. */
internal fun FileAccessProvider.transactionKey(path: String): String = when (this) {
    is LocalFileAccess -> transactionIdentity(path)
    is RemoteSftpFileAccess -> transactionIdentity(path)
    is DelegatingFileAccess, is SafeFileAccessProvider -> checked().transactionKey(path)
    else -> toDisplayPath(path)
}
