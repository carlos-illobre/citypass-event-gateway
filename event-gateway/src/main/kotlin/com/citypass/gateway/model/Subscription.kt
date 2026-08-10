package com.citypass.gateway.model

import java.time.Instant
import java.util.UUID

data class Subscription(
    val id: String = UUID.randomUUID().toString(),
    val topic: String,
    val callbackUrl: String,
    val createdAt: String = Instant.now().toString()
)
