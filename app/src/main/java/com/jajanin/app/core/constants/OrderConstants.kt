package com.jajanin.app.core.constants

internal const val STATUS_PENDING = "pending"
internal const val STATUS_CONFIRMED = "confirmed"
internal const val STATUS_PREPARING = "preparing"
internal const val STATUS_READY = "ready"
internal const val STATUS_COMPLETED = "completed"
internal const val STATUS_CANCELLED = "cancelled"

// Legacy status kept readable for old orders written before the confirmed step existed.
internal const val STATUS_PROCESSING_LEGACY = "processing"

internal const val SERVICE_FEE = 1_000

internal val LOCATION_OPTIONS = listOf(
    "X PPLG 1", "X PPLG 2", "X PPLG 3",
    "XI PPLG 1", "XI PPLG 2", "XI PPLG 3",
    "XII PPLG 1", "XII PPLG 2", "XII PPLG 3",
)
