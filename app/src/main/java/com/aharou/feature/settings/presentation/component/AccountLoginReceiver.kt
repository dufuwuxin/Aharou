package com.aharou.feature.settings.presentation.component

import android.os.Parcel
import android.os.ResultReceiver

internal fun ResultReceiver.forAccountLoginIpc(): ResultReceiver {
    // Binder-delivered Bundles use the boot loader; never send the app's anonymous subclass name.
    val parcel = Parcel.obtain()
    return try {
        writeToParcel(parcel, 0)
        parcel.setDataPosition(0)
        ResultReceiver.CREATOR.createFromParcel(parcel)
    } finally { parcel.recycle() }
}
