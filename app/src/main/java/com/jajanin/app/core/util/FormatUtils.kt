package com.jajanin.app.core.util

import com.jajanin.app.core.constants.STATUS_CANCELLED
import com.jajanin.app.core.constants.STATUS_COMPLETED
import com.jajanin.app.core.constants.STATUS_CONFIRMED
import com.jajanin.app.core.constants.STATUS_PENDING
import com.jajanin.app.core.constants.STATUS_PREPARING
import com.jajanin.app.core.constants.STATUS_PROCESSING_LEGACY
import com.jajanin.app.core.constants.STATUS_READY

/** Label Indonesia yang rapi untuk semua status order baru maupun lama. */
internal fun statusLabelShort(status: String): String = when (status.lowercase()) {
    STATUS_PENDING -> "Pending"
    STATUS_CONFIRMED -> "Confirmed"
    STATUS_PROCESSING_LEGACY -> "Preparing"
    STATUS_PREPARING -> "Preparing"
    STATUS_READY -> "Ready"
    STATUS_COMPLETED -> "Completed"
    STATUS_CANCELLED -> "Cancelled"
    else -> status
}

/** Urutan tampilan kanban order: terbaru, masih aktif, lalu selesai. */
internal fun statusSortOrder(status: String): Int = when (status.lowercase()) {
    STATUS_PENDING -> 0
    STATUS_CONFIRMED -> 1
    STATUS_PREPARING, STATUS_PROCESSING_LEGACY -> 2
    STATUS_READY -> 3
    STATUS_COMPLETED -> 4
    STATUS_CANCELLED -> 5
    else -> 6
}

internal fun formatRupiah(amount: Int): String =
    "Rp " + amount.toString().reversed().chunked(3).joinToString(".").reversed()
