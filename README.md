# EventBus Service (KOBIL Edition)

EbService generates kotlin code that enables
a type-safe way of using the Vert.x EventBus.
The generated code eliminates the need of guessing
which types are required by EventBus consumers and
which types are produced by them.

## Getting started
Imagine we have a service that can divide a
double by another double.

We might model this service as follows:
```kotlin
interface DivisionService {
  suspend fun divide(dividend: Double, divisor: Double): Double
}
```

Next, we need to annotate this interface as follows:
```kotlin
import com.kobil.vertx.ebservice.annotation.EventBusService

@EventBusService
interface DivisionService {
  suspend fun divide(dividend: Double, divisor: Double): Double

  // Overloading is fine
  suspend fun divide(dividend: Int, divisor: Int): Double

  // varargs are also supported:
  suspend fun joinVarargs(vararg strs: String): String

  suspend fun joinVarargsWithPrefix(prefix: String, vararg strs: String): String

  // Event style methods are "fire-and-forget", i.e. no exceptions will be propagated back
  suspend fun event(payload: String)

  // Event style function are also possible without suspend
  fun noSuspendEvent(payload: String)
}
```

This will generate two things
* A stub implementation, translating method calls to messages on the event bus
* A binder implementation, suitable for deploying a service implementation inside a
  `CoroutineVerticle`

What we will need to deploy and use this service is an implementation of the service,
which could look like this:

```kotlin
import kotlinx.coroutines.delay

object DivisionServiceImpl : DivisionService {
  override suspend fun divide(dividend: Double, divisor: Double): Double {
    // expensive computation
    delay(1000L)

    return when (divisor) {
      0.0 -> throw ArithmeticException("Division by zero")
      else -> dividend / divisor
    }
  }

  override suspend fun divide(dividend: Int, divisor: Int): Double = dividend / divisor

  override suspend fun joinVarargs(vararg strs: String): String = strs.joinToString()

  override suspend fun joinVarargsWithPrefix(prefix: String, vararg strs: String): String =
      prefix + strs.joinToString()

  // This must not throw! Exceptions are to be handled inside the method
  override suspend fun event(payload: String) = println("Event received: $payload")

  // This must not throw! Exceptions are to be handled inside the method
  override fun noSuspendEvent(payload: String) = println("Event received: $payload")
}
```

Now, to use the service, a verticle needs to provide an instance of it:

```kotlin
import com.kobil.vertx.ebservice.serviceBinder
import com.kobil.vertx.ebservice.ServiceBinder
import io.vertx.kotlin.coroutines.CoroutineVerticle

class DivisionVerticle : CoroutineVerticle() {
  override suspend fun start() {
    // Get a binder for the service type
    val binder = vertx.serviceBinder<DivisionService>()
    // or
    val binder: ServiceBinder<DivisionService> = vertx.serviceBinder()

    // bind the implementation
    val bound = binder.bind(DivisionServiceImpl)

    // ... later ... unbind the implementation
    bound.unbind()
  }
}
```

After this verticle is deployed, another verticle can use the service:
```kotlin
import com.kobil.vertx.ebservice.service
import io.vertx.kotlin.coroutines.CoroutineVerticle

class ConsumerVerticle : CoroutineVerticle() {
  override suspend fun start() {
    // Get a service stub
    val divisionService = vertx.service<DivisionService>()
    // or
    val divisionService: DivisionService = vertx.service()

    // use the service - this will use the event bus
    val oneHalf: Double = divisionService.divide(2.0, 4.0)

    // this will throw ArithmeticException
    divisionService.divide(2.0, 0.0)
  }
}
```

This service is fully implemented in the `example` module.

### Customizing service addresses

If you have to deploy multiple implementations of the same service interface in you application,
you will need to distinguish them by using different addresses. Those need to be specified when
getting the service stub and the service binder:

```kotlin
import com.kobil.vertx.ebservice.service
import com.kobil.vertx.ebservice.serviceBinder

val divisionService = vertx.service<DivisionService>("my.custom.address")
val divisionBinder = vertx.serviceBinder<DivisionService>("my.custom.address")
```

## Using this in your Microservice
Add the KOBIL Nexus repository to your build script:

```kotlin
repositories {
  maven(url = "http://nexus.jenkins.dev.kobil.com:8080/nexus/content/groups/public")

  // ... other repositories ...
}
```

Apply the `kapt` plugin:

```kotlin
plugins {
  kotlin("jvm") version "<kotlin version>"
  kotlin("kapt") version "<kotlin version>"

  // ... other plugins ...
}
```

Then, add the following dependencies:

```kotlin
implementation("com.kobil.vertx:ebservice:1.0.0")
kapt("com.kobil.vertx:ebservice-codegen:1.0.0")
```
