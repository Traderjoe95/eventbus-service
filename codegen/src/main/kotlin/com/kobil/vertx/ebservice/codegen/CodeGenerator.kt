package com.kobil.vertx.ebservice.codegen

import com.kobil.vertx.ebservice.BoundService
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.MemberName.Companion.member
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.TypeVariableName
import com.squareup.kotlinpoet.WildcardTypeName
import com.squareup.kotlinpoet.asTypeName
import com.squareup.kotlinpoet.metadata.KotlinPoetMetadataPreview
import io.vertx.core.Vertx
import io.vertx.core.eventbus.EventBus
import kotlinx.coroutines.CoroutineScope
import kotlin.reflect.KClass

internal const val PKG = "com.kobil.vertx.ebservice"

internal val STUBS = ClassName(PKG, "Stubs")
internal const val STUB_FUN_NAME = "stub"
internal val STUB_FUN = MemberName(STUBS, STUB_FUN_NAME)

internal val BINDERS = ClassName(PKG, "Binders")
internal const val BIND_FUN_NAME = "binder"
internal val BIND_FUN = MemberName(BINDERS, BIND_FUN_NAME)

internal val DELIVERY_OPTIONS = MemberName(PKG, "deliveryOptions")
internal val SERVICE_RESPONSE = BINDERS.peerClass("ServiceResponse")

internal const val VERTX_COROUTINES = "io.vertx.kotlin.coroutines"
internal const val KOTLINX_COROUTINES = "kotlinx.coroutines"
internal const val KOTLINX_COROUTINES_FLOW = "kotlinx.coroutines.flow"

@KotlinPoetMetadataPreview
internal fun generateStubs(services: List<Service>): TypeSpec {
  val any = ClassName("kotlin", "Any")
  val anyClass = ClassName(
    "kotlin.reflect", "KClass"
  ).parameterizedBy(WildcardTypeName.producerOf(any))
  val stubConstructor = LambdaTypeName.get(
    parameters = listOf(ParameterSpec.unnamed(EventBus::class)),
    returnType = any
  )

  val stubs = TypeSpec.objectBuilder(STUBS)
    .addProperty(
      "stubs", ClassName("kotlin.collections", "Map")
        .parameterizedBy(anyClass, stubConstructor)
    )

  val getStubOnEventBus = FunSpec.builder(STUB_FUN_NAME)
    .addModifiers(KModifier.INLINE)
    .addTypeVariable(TypeVariableName("T", listOf(any)).copy(reified = true))
    .returns(TypeVariableName("T"))
    .addParameter(ParameterSpec.builder("eventBus", EventBus::class).build())
    .addCode(
      """
      eventBus.initializeServiceCodec()

      return when (val ctor = stubs[T::class]) {
        null -> throw NoSuchElementException(%P)
        else -> ctor(eventBus) as T
      }
    """.trimIndent(),
      "\${T::class.qualifiedName} is not an event bus service"
    ).build()

  val getStubOnVertx = FunSpec.builder(STUB_FUN_NAME)
    .addModifiers(KModifier.INLINE)
    .addTypeVariable(TypeVariableName("T", listOf(any)).copy(reified = true))
    .returns(TypeVariableName("T"))
    .addParameter(ParameterSpec.builder("vertx", Vertx::class).build())
    .addStatement("return %M(vertx.eventBus())", STUB_FUN).build()

  stubs.addFunction(getStubOnEventBus).addFunction(getStubOnVertx)

  val initializer = CodeBlock.builder().addStatement("this.stubs = mapOf(")

  services.forEach { service ->
    val companion = TypeSpec.companionObjectBuilder()
      .addProperty(
        PropertySpec.builder(
          "TOPIC", String::class, KModifier.CONST
        )
          .initializer("%S", "${service.name.packageName}.${service.name.simpleName.toLowerCase()}")
          .build()
      )

    initializer.addStatement(
      "  %T::class to ::%T,", service.name,
      STUBS.nestedClass(service.name.simpleName + "Stub")
    )

    stubs.addType(
      generateServiceStub(service)
        .apply { generateFunctions(this, companion, service.functions) }
        .addType(companion.build())
        .build()
    )
  }

  initializer.addStatement(")")

  return stubs.addInitializerBlock(initializer.build()).build()
}

@KotlinPoetMetadataPreview
internal fun generateBinders(services: List<Service>): TypeSpec {
  val any = ClassName("kotlin", "Any")
  val anyClass = ClassName(
    "kotlin.reflect", "KClass"
  ).parameterizedBy(WildcardTypeName.producerOf(any))
  val binderConstructor = LambdaTypeName.get(
    parameters = listOf(ParameterSpec.unnamed(Vertx::class)),
    returnType = any
  )

  val binders = TypeSpec.objectBuilder(BINDERS)
    .addProperty(
      "binders", ClassName("kotlin.collections", "Map")
        .parameterizedBy(anyClass, binderConstructor)
    )

  val bindOnVertx = FunSpec.builder(BIND_FUN_NAME)
    .addModifiers(KModifier.INLINE)
    .addTypeVariable(TypeVariableName("T", listOf(any)).copy(reified = true))
    .returns(BINDERS.peerClass("ServiceBinder").parameterizedBy(TypeVariableName("T")))
    .addParameter(ParameterSpec.builder("vertx", Vertx::class).build())
    .addCode(
      """
      return when (val ctor = binders[T::class]) {
        null -> throw NoSuchElementException(%P)
        else -> ctor(vertx) as ServiceBinder<T>
      }
    """.trimIndent(),
      "\${T::class.qualifiedName} is not an event bus service"
    ).build()

  binders.addFunction(bindOnVertx)

  val initializer = CodeBlock.builder().addStatement("this.binders = mapOf(")

  services.forEach { service ->
    initializer.addStatement(
      "  %T::class to ::%T,", service.name,
      BINDERS.nestedClass(service.name.simpleName + "Binder")
    )

    binders.addType(generateServiceBinder(service))
  }

  initializer.addStatement(")")

  return binders.addInitializerBlock(initializer.build()).build()
}

fun stubAccessor(receiver: KClass<*>): FunSpec =
  FunSpec.builder("service")
    .addModifiers(KModifier.PUBLIC, KModifier.INLINE)
    .receiver(receiver)
    .returns(TypeVariableName("T"))
    .addTypeVariable(TypeVariableName("T", Any::class).copy(reified = true))
    .addStatement("return %M(this)", STUB_FUN)
    .build()

fun binderAccessor(): FunSpec =
  FunSpec.builder("serviceBinder")
    .addModifiers(KModifier.PUBLIC, KModifier.INLINE)
    .receiver(Vertx::class)
    .returns(BINDERS.peerClass("ServiceBinder").parameterizedBy(TypeVariableName("T")))
    .addTypeVariable(TypeVariableName("T", Any::class).copy(reified = true))
    .addStatement("return %M(this)", BIND_FUN)
    .build()

@KotlinPoetMetadataPreview
internal fun generateServiceStub(service: Service): TypeSpec.Builder {
  return TypeSpec.classBuilder(STUBS.nestedClass(service.name.simpleName + "Stub"))
    .addSuperinterface(service.name)
    .primaryConstructor(
      FunSpec.constructorBuilder()
        .addParameter("eventBus", EventBus::class)
        .build()
    ).addProperty(
      PropertySpec.builder("eventBus", EventBus::class, KModifier.PRIVATE)
        .initializer("eventBus")
        .build()
    )
}

@KotlinPoetMetadataPreview
internal fun generateServiceBinder(service: Service): TypeSpec {
  return TypeSpec.classBuilder(BINDERS.nestedClass(service.name.simpleName + "Binder"))
    .addSuperinterface(
      BINDERS.peerClass("ServiceBinder").parameterizedBy(service.name)
    ).primaryConstructor(
      FunSpec.constructorBuilder()
        .addParameter("vertx", Vertx::class)
        .build()
    ).addProperty(
      PropertySpec.builder("vertx", Vertx::class, KModifier.PRIVATE)
        .initializer("vertx")
        .build()
    ).addFunction(generateBindFun(service))
    .build()
}

internal fun generateBindFun(service: Service): FunSpec {
  val stubClass = STUBS.nestedClass(service.name.simpleName + "Stub")
  val stubCompanion = stubClass.nestedClass("Companion")

  val bind = FunSpec.builder("bind")
    .addModifiers(KModifier.PUBLIC, KModifier.OVERRIDE)
    .addParameter("impl", service.name)
    .returns(BoundService::class)
    .addStatement(
      "val consumers: MutableList<%T<*>> = mutableListOf()",
      ClassName("io.vertx.core.eventbus", "MessageConsumer")
    ).addStatement(
      "val scope: %T = %M(vertx.%M())",
      CoroutineScope::class,
      MemberName(KOTLINX_COROUTINES, "CoroutineScope"),
      MemberName(VERTX_COROUTINES, "dispatcher")
    )

  service.functions.forEach { function ->
    val requestType = when (function.parameters.size) {
      0 -> Unit::class.asTypeName()
      1 -> function.parameters.first().type
      else -> stubCompanion.nestedClass(function.name.capitalize() + "Parameters")
    }

    val implCaller = when (function.parameters.size) {
      0 -> "()"
      1 -> "(it.body())"
      else -> function.parameters.indices.joinToString(", ", prefix = "(", postfix = ")") {
        "it.body().component${it + 1}()"
      }
    }

    bind.addStatement(
      """var ${function.name}Consumer = vertx.eventBus().localConsumer<%T>(%M + %S)
        |consumers.add(${function.name}Consumer)
        |
        |${function.name}Consumer
        |.%M(vertx)
        |.%M()
        |.%M {
        |  try {
        |    it.reply(%T(impl.${function.name}$implCaller), %M)
        |  } catch (e: Exception) {
        |    it.reply(%T(e), %M)
        |  }
        |}.%M(scope)
      """.trimMargin(),
      requestType,
      stubCompanion.member("TOPIC"),
      ".${function.name}",
      MemberName(VERTX_COROUTINES, "toChannel"),
      MemberName(KOTLINX_COROUTINES_FLOW, "receiveAsFlow"),
      MemberName(KOTLINX_COROUTINES_FLOW, "onEach"),
      SERVICE_RESPONSE.nestedClass("ServiceResult"),
      DELIVERY_OPTIONS,
      SERVICE_RESPONSE.nestedClass("ServiceError"),
      DELIVERY_OPTIONS,
      MemberName(KOTLINX_COROUTINES_FLOW, "launchIn")
    )
  }

  val boundService = TypeSpec.anonymousClassBuilder()
    .addSuperinterface(BoundService::class)
    .addFunction(
      FunSpec.builder("unbind")
        .addModifiers(KModifier.OVERRIDE, KModifier.SUSPEND)
        .addStatement(
          """consumers.forEach {
        |it.unregister().%M()
        |}""".trimMargin(),
          MemberName(VERTX_COROUTINES, "await")
        )
        .build()
    )
    .build()

  bind.addStatement("return %L", boundService)

  return bind.build()
}

@KotlinPoetMetadataPreview
internal fun generateFunctions(
  serviceSpec: TypeSpec.Builder,
  companion: TypeSpec.Builder,
  functions: Set<Function>
) {
  functions.forEach { function ->
    val container = if (function.parameters.size > 1) {
      generateParameterContainer(function.name, function.parameters)
    } else null

    container?.let {
      companion.addType(it)
    }

    serviceSpec.addFunction(generateFunction(function, container?.name))
  }
}

@KotlinPoetMetadataPreview
private fun generateParameterContainer(functionName: String, parameters: Set<Parameter>): TypeSpec {
  return TypeSpec.classBuilder(functionName.capitalize() + "Parameters")
    .addModifiers(KModifier.DATA)
    .primaryConstructor(
      FunSpec.constructorBuilder()
        .addParameters(
          parameters.map { parameter ->
            ParameterSpec(parameter.name, parameter.type)
          }
        )
        .build()
    ).addProperties(
      parameters.map { parameter ->
        PropertySpec.builder(parameter.name, parameter.type)
          .initializer(parameter.name)
          .build()
      }
    )
    .build()
}


@KotlinPoetMetadataPreview
private fun generateFunction(function: Function, containerType: String?): FunSpec {
  val message: String = containerType?.let { type ->
    val params = function.parameters.joinToString { it.name }
    "$type($params)"
  } ?: function.parameters.map { it.name }.firstOrNull() ?: "Unit"
  return FunSpec.builder(function.name)
    .addModifiers(KModifier.OVERRIDE, KModifier.SUSPEND)
    .addParameters(
      function.parameters.map { parameter ->
        ParameterSpec(parameter.name, parameter.type)
      }
    )
    .returns(function.returnType)
    .addCode(
      """
      return eventBus.request<%T>(TOPIC + ".${function.name}", $message, %M)
        .%M()
        .body()
        .value
    """.trimIndent(),
      SERVICE_RESPONSE.parameterizedBy(function.returnType),
      DELIVERY_OPTIONS,
      MemberName(VERTX_COROUTINES, "await")
    )
    .build()
}
