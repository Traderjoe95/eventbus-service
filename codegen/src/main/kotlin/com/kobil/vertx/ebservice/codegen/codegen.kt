@file:JvmName("CodeGenerator")

package com.kobil.vertx.ebservice.codegen

import com.kobil.vertx.ebservice.BoundService
import com.squareup.kotlinpoet.AnnotationSpec
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
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.TypeVariableName
import com.squareup.kotlinpoet.WildcardTypeName
import com.squareup.kotlinpoet.asTypeName
import com.squareup.kotlinpoet.metadata.KotlinPoetMetadataPreview
import io.vertx.core.Vertx
import io.vertx.core.eventbus.EventBus
import java.util.Locale
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

internal const val KOTLIN_REFLECT = "kotlin.reflect"
internal const val VERTX_COROUTINES = "io.vertx.kotlin.coroutines"
internal const val KOTLINX_COROUTINES = "kotlinx.coroutines"
internal const val KOTLINX_COROUTINES_FLOW = "kotlinx.coroutines.flow"

internal val ANY = ClassName("kotlin", "Any")
internal val ANY_OUT = WildcardTypeName.producerOf(ANY)
internal val ANY_CLASS = ClassName(KOTLIN_REFLECT, "KClass").parameterizedBy(ANY_OUT)
internal val T = TypeVariableName("T")
internal val T_ANY = TypeVariableName("T", listOf(ANY))
internal val SERVICE_CLS =
  ClassName(KOTLIN_REFLECT, "KClass").parameterizedBy(T)
internal val NULLABLE_STR = ClassName("kotlin", "String").copy(nullable = true)

@KotlinPoetMetadataPreview
internal fun generateStubs(services: List<Service>): TypeSpec {
  val stubConstructor = LambdaTypeName.get(
    parameters = listOf(
      ParameterSpec.unnamed(EventBus::class),
      ParameterSpec.Companion.unnamed(NULLABLE_STR)
    ),
    returnType = ANY
  )

  val stubs = TypeSpec.objectBuilder(STUBS)
    .addProperty(
      "stubs", ClassName("kotlin.collections", "Map")
        .parameterizedBy(ANY_CLASS, stubConstructor), KModifier.PRIVATE
    )

  val getStubOnEventBus = FunSpec.builder(STUB_FUN_NAME)
    .addTypeVariable(T_ANY)
    .returns(T)
    .addParameter(ParameterSpec.builder("serviceClass", SERVICE_CLS).build())
    .addParameter(ParameterSpec.builder("eventBus", EventBus::class).build())
    .addParameter(ParameterSpec.builder("address", NULLABLE_STR).build())
    .addAnnotation(
      AnnotationSpec.builder(Suppress::class)
        .addMember("%S", "UNCHECKED_CAST")
        .build()
    )
    .addCode(
      """
      eventBus.initializeServiceCodec()

      return when (val ctor = stubs[serviceClass]) {
        null -> throw NoSuchElementException(%P)
        else -> ctor(eventBus, address) as T
      }
    """.trimIndent(),
      "\${serviceClass.qualifiedName} is not an event bus service"
    ).build()

  val getStubOnVertx = FunSpec.builder(STUB_FUN_NAME)
    .addTypeVariable(T_ANY)
    .returns(T)
    .addParameter(ParameterSpec.builder("serviceClass", SERVICE_CLS).build())
    .addParameter(ParameterSpec.builder("vertx", Vertx::class).build())
    .addParameter(ParameterSpec.builder("address", NULLABLE_STR).build())
    .addStatement("return %M(serviceClass, vertx.eventBus(), address)", STUB_FUN).build()

  stubs.addFunction(getStubOnEventBus).addFunction(getStubOnVertx)

  val initializer = CodeBlock.builder().addStatement("this.stubs = mapOf(")

  services.forEach { service ->
    val companion = TypeSpec.companionObjectBuilder()
      .addProperty(
        PropertySpec.builder(
          "DEFAULT_ADDRESS", String::class, KModifier.CONST
        )
          .initializer(
            "%S",
            "${service.name.packageName}.${service.name.simpleName.lowercase(Locale.getDefault())}"
          )
          .build()
      )

    initializer.addStatement(
      "  %T::class to ::%T,", service.name,
      STUBS.nestedClass(service.name.simpleName + "Stub")
    )

    stubs.addType(
      generateServiceStub(service)
        .apply { generateStubFuns(this, companion, service.functions) }
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
    KOTLIN_REFLECT, "KClass"
  ).parameterizedBy(WildcardTypeName.producerOf(any))
  val binderConstructor = LambdaTypeName.get(
    parameters = listOf(ParameterSpec.unnamed(Vertx::class), ParameterSpec.unnamed(NULLABLE_STR)),
    returnType = BINDERS.peerClass("ServiceBinder").parameterizedBy(ANY_OUT)
  )

  val binders = TypeSpec.objectBuilder(BINDERS)
    .addProperty(
      "binders", ClassName("kotlin.collections", "Map")
        .parameterizedBy(anyClass, binderConstructor), KModifier.PRIVATE
    )

  val bindOnVertx = FunSpec.builder(BIND_FUN_NAME)
    .addTypeVariable(T_ANY)
    .returns(BINDERS.peerClass("ServiceBinder").parameterizedBy(T))
    .addParameter(ParameterSpec.builder("serviceClass", SERVICE_CLS).build())
    .addParameter(ParameterSpec.builder("vertx", Vertx::class).build())
    .addParameter(ParameterSpec.builder("address", NULLABLE_STR).build())
    .addAnnotation(
      AnnotationSpec.builder(Suppress::class)
        .addMember("%S", "UNCHECKED_CAST")
        .build()
    )
    .addCode(
      """
      return when (val ctor = binders[serviceClass]) {
        null -> throw NoSuchElementException(%P)
        else -> ctor(vertx, address) as ServiceBinder<T>
      }
    """.trimIndent(),
      "\${serviceClass.qualifiedName} is not an event bus service"
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

internal fun stubAccessor(receiver: KClass<*>): FunSpec =
  FunSpec.builder("service")
    .addModifiers(KModifier.PUBLIC, KModifier.INLINE)
    .receiver(receiver)
    .returns(T)
    .addTypeVariable(TypeVariableName("T", Any::class).copy(reified = true))
    .addParameter(ParameterSpec.builder("baseAddress", NULLABLE_STR).defaultValue("null").build())
    .addStatement("return %M(T::class, this, baseAddress)", STUB_FUN)
    .build()

internal fun binderAccessor(): FunSpec =
  FunSpec.builder("serviceBinder")
    .addModifiers(KModifier.PUBLIC, KModifier.INLINE)
    .receiver(Vertx::class)
    .returns(BINDERS.peerClass("ServiceBinder").parameterizedBy(T))
    .addTypeVariable(TypeVariableName("T", Any::class).copy(reified = true))
    .addParameter(ParameterSpec.builder("baseAddress", NULLABLE_STR).defaultValue("null").build())
    .addStatement("return %M(T::class, this, baseAddress)", BIND_FUN)
    .build()

@KotlinPoetMetadataPreview
private fun generateServiceStub(service: Service): TypeSpec.Builder {
  val stubClass = STUBS.nestedClass(service.name.simpleName + "Stub")
  return TypeSpec.classBuilder(stubClass)
    .addSuperinterface(service.name)
    .primaryConstructor(
      FunSpec.constructorBuilder()
        .addParameter("eventBus", EventBus::class)
        .addParameter(
          ParameterSpec.builder("baseAddress", NULLABLE_STR).defaultValue("null").build()
        )
        .build()
    ).addProperty(
      PropertySpec.builder("eventBus", EventBus::class, KModifier.PRIVATE)
        .initializer("eventBus")
        .build()
    ).addProperty(
      PropertySpec.builder("baseAddress", String::class, KModifier.PRIVATE)
        .initializer("baseAddress ?: DEFAULT_ADDRESS")
        .build()
    )
}

@KotlinPoetMetadataPreview
private fun generateStubFuns(
  serviceSpec: TypeSpec.Builder,
  companion: TypeSpec.Builder,
  functions: Set<ServiceFun>
) {
  functions.forEach { function ->
    val container = if (function.parameters.size > 1) {
      generateParameterContainer(function)
    } else null

    container?.let {
      companion.addType(it)
    }

    serviceSpec.addFunction(generateStubFun(function, container?.name))
  }
}

@KotlinPoetMetadataPreview
private fun generateParameterContainer(
  function: ServiceFun
): TypeSpec {
  return TypeSpec.classBuilder(function.parameterContainer)
    .addModifiers(KModifier.DATA)
    .primaryConstructor(
      FunSpec.constructorBuilder()
        .addParameters(
          function.parameters.map { parameter ->
            if (parameter.isVararg) ParameterSpec(
              parameter.name,
              arrayType(parameter.type, parameter.isPrimitive)
            )
            else ParameterSpec(parameter.name, parameter.type)
          }
        )
        .build()
    ).addProperties(
      function.parameters.map { parameter ->
        PropertySpec.builder(
          parameter.name,
          if (parameter.isVararg) arrayType(
            parameter.type,
            parameter.isPrimitive
          ) else parameter.type
        )
          .initializer(parameter.name)
          .build()
      }
    )
    .build()
}

private fun arrayType(elements: TypeName, primitive: Boolean): TypeName =
  if (primitive) ClassName(
    "kotlin",
    "${(elements as ClassName).simpleName}Array"
  )
  else ClassName("kotlin", "Array")
    .parameterizedBy(WildcardTypeName.producerOf(elements))

@KotlinPoetMetadataPreview
private fun generateStubFun(function: ServiceFun, containerType: String?): FunSpec {
  val message: String = containerType?.let { type ->
    val params = function.parameters.joinToString { it.name }
    "$type($params)"
  } ?: function.parameters.map { it.name }.firstOrNull() ?: "Unit"
  val spec = FunSpec.builder(function.name)
    .addModifiers(KModifier.OVERRIDE)
    .addParameters(
      function.parameters.map { parameter ->
        val pBuilder = ParameterSpec.builder(parameter.name, parameter.type)
        if (parameter.isVararg) pBuilder.addModifiers(KModifier.VARARG)

        pBuilder.build()
      }
    )
    .returns(function.returnType)

  if (function.isSuspend) spec.addModifiers(KModifier.SUSPEND)

  if (function.isEventStyle) {
    spec.addCode(
      "eventBus.send(baseAddress + %S, $message, %M)",
      ".${function.fullName}",
      DELIVERY_OPTIONS
    )
  } else {
    spec.addCode(
      """
      return eventBus.request<%T>(baseAddress + ".${function.fullName}", $message, %M)
        .%M()
        .body()
        .value
    """.trimIndent(),
      SERVICE_RESPONSE.parameterizedBy(function.returnType),
      DELIVERY_OPTIONS,
      MemberName(VERTX_COROUTINES, "await")
    )
  }


  return spec.build()
}

@KotlinPoetMetadataPreview
private fun generateServiceBinder(service: Service): TypeSpec {
  val stubClass = STUBS.nestedClass(service.name.simpleName + "Stub")
  val stubCompanion = stubClass.nestedClass("Companion")

  return TypeSpec.classBuilder(BINDERS.nestedClass(service.name.simpleName + "Binder"))
    .addSuperinterface(
      BINDERS.peerClass("ServiceBinder").parameterizedBy(service.name)
    ).primaryConstructor(
      FunSpec.constructorBuilder()
        .addParameter("vertx", Vertx::class)
        .addParameter(
          ParameterSpec.builder("baseAddress", NULLABLE_STR)
            .defaultValue("null")
            .build()
        )
        .build()
    ).addProperty(
      PropertySpec.builder("vertx", Vertx::class, KModifier.PRIVATE)
        .initializer("vertx")
        .build()
    ).addProperty(
      PropertySpec.builder("baseAddress", String::class, KModifier.PRIVATE)
        .initializer("baseAddress ?: %M", stubCompanion.member("DEFAULT_ADDRESS"))
        .build()
    ).addFunction(generateBindFun(service))
    .build()
}

private fun generateBindFun(service: Service): FunSpec {
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
      ClassName(KOTLINX_COROUTINES, "CoroutineScope"),
      MemberName(KOTLINX_COROUTINES, "CoroutineScope"),
      MemberName(VERTX_COROUTINES, "dispatcher")
    )

  service.functions.forEach { function ->
    val requestType = requestType(function, stubCompanion)

    bind.addStatement(
      """var ${function.fullName}Consumer = vertx.eventBus().localConsumer<%T>(baseAddress + %S)
        |consumers.add(${function.fullName}Consumer)
      """.trimMargin(),
      requestType,
      ".${function.fullName}",
    )

    if (function.isEventStyle) {
      addEventStyleBind(bind, function)
    } else {
      addRequestStyleBind(bind, function)
    }
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

private fun addRequestStyleBind(bind: FunSpec.Builder, function: ServiceFun) {
  bind.addStatement(
    """${function.fullName}Consumer
        |.%M(vertx)
        |.%M()
        |.%M {
        |  try {
        |    it.reply(%T(impl.${function.name}${implCaller(function)}), %M)
        |  } catch (e: Exception) {
        |    it.reply(%T(e), %M)
        |  }
        |}.%M(scope)
      """.trimMargin(),
    MemberName(VERTX_COROUTINES, "toReceiveChannel"),
    MemberName(KOTLINX_COROUTINES_FLOW, "receiveAsFlow"),
    MemberName(KOTLINX_COROUTINES_FLOW, "onEach"),
    SERVICE_RESPONSE.nestedClass("ServiceResult"),
    DELIVERY_OPTIONS,
    SERVICE_RESPONSE.nestedClass("ServiceError"),
    DELIVERY_OPTIONS,
    MemberName(KOTLINX_COROUTINES_FLOW, "launchIn")
  )
}

private fun addEventStyleBind(bind: FunSpec.Builder, function: ServiceFun) {
  bind.addStatement(
    """${function.fullName}Consumer
        |.%M(vertx)
        |.%M()
        |.%M {
        |  try {
        |    impl.${function.name}${implCaller(function)}
        |  } catch (e: Exception) {
        |    println("WARNING: Exception thrown in event handler: " + e)
        |  }
        |}.%M(scope)
      """.trimMargin(),
    MemberName(VERTX_COROUTINES, "toReceiveChannel"),
    MemberName(KOTLINX_COROUTINES_FLOW, "receiveAsFlow"),
    MemberName(KOTLINX_COROUTINES_FLOW, "onEach"),
    MemberName(KOTLINX_COROUTINES_FLOW, "launchIn")
  )
}

private fun implCaller(function: ServiceFun) =
  when (function.parameters.size) {
    0 -> "()"
    1 -> if (function.parameters.first().isVararg) "(*it.body())" else "(it.body())"
    else -> function.parameters.zip(function.parameters.indices)
      .joinToString(", ", prefix = "(", postfix = ")") {
        "${if (it.first.isVararg) "*" else ""}it.body().component${it.second + 1}()"
      }
  }

private fun requestType(function: ServiceFun, stubCompanion: ClassName) =
  when (function.parameters.size) {
    0 -> Unit::class.asTypeName()
    1 -> {
      val first = function.parameters.first()

      if (first.isVararg) arrayType(first.type, first.isPrimitive)
      else first.type
    }
    else -> stubCompanion.nestedClass(function.parameterContainer)
  }
