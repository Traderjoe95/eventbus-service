package com.kobil.vertx.ebservice

import io.vertx.core.buffer.Buffer
import io.vertx.core.eventbus.DeliveryOptions
import io.vertx.core.eventbus.EventBus
import io.vertx.core.eventbus.MessageCodec
import java.lang.IllegalStateException

fun EventBus.initializeServiceCodec() {
  try {
    registerCodec(ServiceCodec)
  } catch (_: IllegalStateException) {
    // Codec was already registered
  }
}

val deliveryOptions = DeliveryOptions()
  .apply {
    isLocalOnly = true
    codecName = ServiceCodec.name()
  }

private object ServiceCodec : MessageCodec<Any, Any> {
  override fun decodeFromWire(pos: Int, buffer: Buffer?) = throw UnsupportedOperationException()
  override fun encodeToWire(buffer: Buffer?, s: Any?) = throw UnsupportedOperationException()
  override fun transform(s: Any?) = s
  override fun name() = "ebservice"
  override fun systemCodecID(): Byte = -1
}

sealed class ServiceResponse<out T> {
  data class ServiceResult<out T>(val result: T) : ServiceResponse<T>()
  data class ServiceError(val cause: Exception) : ServiceResponse<Nothing>()

  val value: T
    get() = when (this) {
      is ServiceResult<T> -> this.result
      is ServiceError -> throw this.cause
    }
}

interface ServiceBinder<T> {
  fun bind(impl: T): BoundService
}

interface BoundService {
  suspend fun unbind()
}
