package com.kobil.vertx.ebservice.example

import com.kobil.vertx.ebservice.annotation.EventBusService
import com.kobil.vertx.ebservice.service
import com.kobil.vertx.ebservice.serviceBinder
import io.vertx.core.Vertx
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class Generic<A, B>(val a: A, val b: B)

@EventBusService
interface MathService {
  suspend fun add(addendA: Double, addendB: Double, vararg test: Double): Double
  suspend fun divide(dividend: Double, divisor: Double): Double

  suspend fun joinVararg(vararg test: String): String
  suspend fun sumVararg(vararg test: Double): String

  suspend fun <A : CharSequence, B : A> generic1(a: A, b: B): Generic<A, B>
  suspend fun <A, C : Iterable<A>> generic2(a: A, aList: C): List<A>
  suspend fun <A, B> generic3(a: Generic<A, B>): B
  suspend fun <A : CharSequence> generic4(a: Generic<A, String>): String
  suspend fun <A : CharSequence?> genericVararg(vararg a: A): String

  suspend fun <A> lambdaParam(a: A, block: (String, A) -> String): String
  suspend fun <A> suspendLambdaParam(a: A, blockSus: suspend (String, A) -> String): String
  suspend fun <A> extLambdaParam(a: A, blockExt: String.(A) -> String): String
  suspend fun <A> extSuspendLambdaParam(a: A, blockExtSus: suspend String.(A) -> String): String

  suspend fun <A> suspendLambdaGeneric(
    a: A, blockExtSus: suspend String.(A) -> Generic<A, String>
  ): Generic<A, String>

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

        override suspend fun divide(dividend: Double, divisor: Double): Double =
          when (divisor) {
            0.0 -> throw ArithmeticException("Division by zero")
            else -> dividend / divisor
          }

        override suspend fun joinVararg(vararg test: String): String = test.joinToString(", ")

        override suspend fun sumVararg(vararg test: Double): String = test.sum().toString()

        override suspend fun <A : CharSequence, B : A> generic1(a: A, b: B): Generic<A, B> =
          Generic(a, b)

        override suspend fun <A, C : Iterable<A>> generic2(a: A, aList: C): List<A> =
          aList + a

        override suspend fun <A, B> generic3(a: Generic<A, B>): B = a.b
        override suspend fun <A : CharSequence> generic4(a: Generic<A, String>): String = a.b + a.a

        override suspend fun <A : CharSequence?> genericVararg(vararg a: A): String =
          a.joinToString { it ?: "" }

        override suspend fun <A> lambdaParam(a: A, block: (String, A) -> String): String =
          block("abc", a)

        override suspend fun <A> suspendLambdaParam(
          a: A,
          blockSus: suspend (String, A) -> String
        ): String =
          blockSus("ghi", a)

        override suspend fun <A> extLambdaParam(a: A, blockExt: String.(A) -> String): String =
          "abc".blockExt(a)

        override suspend fun <A> extSuspendLambdaParam(
          a: A,
          blockExtSus: suspend String.(A) -> String
        ): String =
          "ghi".blockExtSus(a)

        override suspend fun <A> suspendLambdaGeneric(
          a: A,
          blockExtSus: suspend String.(A) -> Generic<A, String>
        ): Generic<A, String> = "abc".blockExtSus(a)

        override suspend fun suspendEvent(payload: String) = println("received event: $payload")

        override fun event(payload: String) = println("received event: $payload")
      }
    )

    val bound = vertx.serviceBinder<MultiplicationService>().bind(
      object : MultiplicationService {
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

    try {
      val gen = mathService.generic1("abc", "def")
      println(gen)

      val gen2 = mathService.generic2("abc", listOf("def", "ghi"))
      println(gen2)

      val gen3 = mathService.generic3(Generic(1, 2))
      println(gen3)

      val gen4 = mathService.generic4(Generic("abc", "def"))
      println(gen4)

      val genVar = mathService.genericVararg("1", "2", "3", null, "4")
      println(genVar)
    } catch (e: Exception) {
      println("OH NOES, $e")
    }

    try {
      val l1 = mathService.lambdaParam(2) { s, a -> s + a }
      println(l1)

      val l2 = mathService.extLambdaParam("foo") { this + it }
      println(l2)

      val l3 = mathService.suspendLambdaParam(true) { s, a -> s + a }
      println(l3)

      val l4 = mathService.extSuspendLambdaParam(1.234) { this + it }
      println(l4)

      val l5 = mathService.suspendLambdaGeneric(123456) { Generic(it, this) }
      println(l5)
    } catch (e: Exception) {
      println("OH NOES, $e")
    }

    val arr = doubleArrayOf(1.0, 2.0, 3.0)
    try {
      val sum = mathService.add(3.3, 4.5, *arr)
      println(sum)
    } catch (e: Exception) {
      println("OH NOES, $e")
    }

    try {
      val quotient = mathService.divide(3.3, 4.5)
      println(quotient)
    } catch (e: Exception) {
      println("OH NOES, $e")
    }

    try {
      val quotient = mathService.divide(3.3, 0.0)
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
      val quotient = mathService.divide(3.3, 4.5)
      println(quotient)
    } catch (e: Exception) {
      println("OH NOES, $e")
    }

    try {
      val quotient = mathService.divide(3.3, 0.0)
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
