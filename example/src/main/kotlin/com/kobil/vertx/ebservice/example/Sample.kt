package com.kobil.vertx.ebservice.example

import com.kobil.vertx.ebservice.annotation.EventBusService
import com.kobil.vertx.ebservice.service
import com.kobil.vertx.ebservice.serviceBinder
import io.vertx.core.Vertx
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@EventBusService
interface MathService {
  suspend fun add(addendA: Double, addendB: Double, vararg test: Double): Double
  suspend fun Divide(dividend: Double, divisor: Double): Double

  suspend fun joinVararg(vararg test: String): String
  suspend fun sumVararg(vararg test: Double): String

  suspend fun suspendEvent(payload: String)
  fun event(payload: String)
}

@EventBusService
interface MultiplicationService {
  suspend fun multiply(factorA: Double, factorB: Double): Double
  suspend fun multiply(factorA: Int, factorB: Int, vararg test: String): Int
}

class DivisionVerticle : CoroutineVerticle() {
  override suspend fun start() {
    vertx.serviceBinder<MathService>().bind(
      object : MathService {
        override suspend fun add(addendA: Double, addendB: Double, vararg test: Double): Double =
          addendA + addendB + test.sum()

        override suspend fun Divide(dividend: Double, divisor: Double): Double =
          when (divisor) {
            0.0 -> throw ArithmeticException("Division by zero")
            else -> dividend / divisor
          }

        override suspend fun joinVararg(vararg test: String): String = test.joinToString(", ")

        override suspend fun sumVararg(vararg test: Double): String = test.sum().toString()

        override suspend fun suspendEvent(payload: String) = println("received event: $payload")

        override fun event(payload: String) = println("received event: $payload")
      }
    )

    val bound = vertx.serviceBinder<MultiplicationService>().bind(
      object: MultiplicationService {
        override suspend fun multiply(factorA: Double, factorB: Double): Double = factorA * factorB
        override suspend fun multiply(factorA: Int, factorB: Int, vararg test: String): Int =
          factorA * factorB
      }
    )

    launch {
      delay(8000)
      println("Unbinding")
      bound.unbind()
    }
  }
}

class SampleVerticle : CoroutineVerticle() {
  override suspend fun start() {
    println("Before getting services")
    val mathService = vertx.service<MathService>()
    val multiplicationService = vertx.service<MultiplicationService>()
    println("after getting services")

    val arr = doubleArrayOf(1.0, 2.0, 3.0)
    try {
      val sum = mathService.add(3.3, 4.5, *arr)
      println(sum)
    } catch (e: Exception) {
      println("OH NOES, $e")
    }

    try {
      val quotient = mathService.Divide(3.3, 4.5)
      println(quotient)
    } catch (e: Exception) {
      println("OH NOES, $e")
    }

    try {
      val quotient = mathService.Divide(3.3, 0.0)
      println(quotient)
    } catch (e: Exception) {
      println("Caught $e")
    }

    try {
     val product = multiplicationService.multiply(1.2, 5.0)
     println(product)
    } catch (e: Exception) {
      println("OH NOES, $e")
    }

    try {
      val joined = mathService.joinVararg("a", "b", "c")
      println(joined)
    } catch (e: Exception) {
      println("OH NOES, $e")
    }

    try {
      val sum = mathService.sumVararg(1.0, 2.0, 3.0)
      println(sum)
    } catch (e: Exception) {
      println("OH NOES, $e")
    }

    mathService.event("some payload")
    mathService.suspendEvent("sent with suspend")

    println("Waiting for 10 seconds")
    delay(10000)

    // Now multiplicationService should have unregistered
    try {
      val sum = mathService.add(3.3, 4.5)
      println(sum)
    } catch (e: Exception) {
      println("OH NOES, $e")
    }

    try {
      val quotient = mathService.Divide(3.3, 4.5)
      println(quotient)
    } catch (e: Exception) {
      println("OH NOES, $e")
    }

    try {
      val quotient = mathService.Divide(3.3, 0.0)
      println(quotient)
    } catch (e: Exception) {
      println("Caught $e")
    }

    try {
      val product = multiplicationService.multiply(1.2, 5.0)
      println(product)
    } catch (e: Exception) {
      println("Service was unregistered: $e")
    }
  }
}

suspend fun main() {
  val vertx = Vertx.vertx()

  println("Before deploying DV")

  vertx.deployVerticle(DivisionVerticle::class.java.name).await()

  println("After deploying DV")
  vertx.deployVerticle(SampleVerticle::class.java.name).await()

  println("after deploying SV")
  vertx.close().await()
}
