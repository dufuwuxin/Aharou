package com.aharou.core.content;

import android.os.ParcelFileDescriptor;

interface IContentParser {
    // Payloads travel through pipes; Binder carries only bounded metadata and descriptors.
    oneway void parse(String requestId, in ParcelFileDescriptor input,
        in ParcelFileDescriptor output, @nullable String charset, String baseUri, boolean html, boolean xml);
    oneway void cancel(String requestId);
    int processId();
    int processUid();
}
