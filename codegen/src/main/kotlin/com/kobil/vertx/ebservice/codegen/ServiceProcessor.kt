package com.kobil.vertx.ebservice.codegen

import com.kobil.vertx.ebservice.annotation.EventBusService
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterizedTypeName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeVariableName
import com.squareup.kotlinpoet.metadata.ImmutableKmFunction
import com.squareup.kotlinpoet.metadata.ImmutableKmType
import com.squareup.kotlinpoet.metadata.ImmutableKmTypeParameter
import com.squareup.kotlinpoet.metadata.ImmutableKmValueParameter
import com.squareup.kotlinpoet.metadata.KotlinPoetMetadataPreview
import com.squareup.kotlinpoet.metadata.isInterface
import com.squareup.kotlinpoet.metadata.isNullable
import com.squareup.kotlinpoet.metadata.isSuspend
import com.squareup.kotlinpoet.metadata.toImmutableKmClass
import io.vertx.core.Vertx
import io.vertx.core.eventbus.EventBus
import kotlinx.metadata.Flag
import kotlinx.metadata.KmClassifier
import kotlinx.metadata.KmVariance
import javax.annotation.processing.AbstractProcessor
import javax.annotation.processing.RoundEnvironment
import javax.annotation.processing.SupportedAnnotationTypes
import javax.annotation.processing.SupportedOptions
import javax.annotation.processing.SupportedSourceVersion
import javax.lang.model.SourceVersion
import javax.lang.model.element.Element
import javax.lang.model.element.TypeElement
import javax.lang.model.util.ElementFilter
import javax.tools.Diagnostic

@KotlinPoetMetadataPreview
@SupportedSourceVersion(SourceVersion.RELEASE_16)
@SupportedOptions("kapt.kotlin.generated")
@SupportedAnnotationTypes("com.kobil.vertx.ebservice.annotation.EventBusService")
class ServiceProcessor : AbstractProcessor() {
  private val primitives: Set<String> = setOf(
    "kotlin.Boolean", "kotlin.Byte", "kotlin.Char",
    "kotlin.Double", "kotlin.Float", "kotlin.Int", "kotlin.Long", "kotlin.Short"
  )

  override fun process(annotations: Set<TypeElement>, roundEnv: RoundEnvironment): Boolean {
    val elements = roundEnv.getElementsAnnotatedWith(EventBusService::class.java)
    if (elements.isEmpty()) return false

    val typeElements = ElementFilter.typesIn(elements)

    val services = typeElements.map { typeElement ->
      val kmClass = typeElement.toImmutableKmClass()

      if (!kmClass.isInterface) {
        logError("Only interfaces are supported by ebservice", typeElement)
        error("Only interfaces are supported by ebservice")
      }

      val functions = kmClass.functions
        .asSequence()
        .extractFunctions()
        .toSet()

      Service(kmClass.name.toClassName(), functions)
    }

    FileSpec.builder(PKG, "Stubs")
      .addType(generateStubs(services))
      .addFunction(stubAccessor(Vertx::class))
      .addFunction(stubAccessor(EventBus::class))
      .build().writeTo(processingEnv.filer)

    FileSpec.builder(PKG, "Binders")
      .addType(generateBinders(services))
      .addFunction(binderAccessor())
      .build().writeTo(processingEnv.filer)

    return true
  }

  private fun Sequence<ImmutableKmFunction>.extractFunctions(): Sequence<ServiceFun> {
    val overload: MutableMap<String, Int> = mutableMapOf<String, Int>().withDefault { 0 }

    return map { kmFunction ->
      val typeParameters = kmFunction.typeParameters.let { rawParams ->
        rawParams.map { it.toTypeName() }.let { preliminaryParams ->
          preliminaryParams.indices.map { idx ->
            preliminaryParams[idx].copy(bounds = rawParams[idx].upperBounds.map {
              val tn = it.toTypeName(preliminaryParams)

              if (tn is ClassName) {
                tn.safelyParameterizedBy(it.getTypeParameters(preliminaryParams))
              } else tn
            })
          }
        }
      }

      val returnTypeStr = kmFunction.returnType.classifier.toTypeName(
        false,
        typeParameters
      ).toString()

      if (!kmFunction.isSuspend && returnTypeStr != "kotlin.Unit") {
        logError("Function ${kmFunction.name} must be suspending")
        error("Function ${kmFunction.name} must be suspending")
      }

      val overloadId = overload.getValue(kmFunction.name)
      overload[kmFunction.name] = overloadId + 1

      val retTypeParameters = kmFunction.returnType.getTypeParameters(typeParameters)
      val returnType = kmFunction.returnType.classifier
        .toTypeName(kmFunction.returnType.isNullable, typeParameters).let {
          if (it is ClassName) it.safelyParameterizedBy(retTypeParameters)
          else it
        }

      val parameters = kmFunction.valueParameters
        .asSequence()
        .toFunctionParameters(typeParameters)
        .toSet()

      ServiceFun(
        kmFunction.name, typeParameters, returnType, parameters, overloadId.toUInt(),
        isSuspend = kmFunction.isSuspend
      )
    }
  }

  private fun Sequence<ImmutableKmValueParameter>.toFunctionParameters(
    typeParameters: List<TypeName>
  ): Sequence<Parameter> {
    return map { kmValueParameter ->
      val type = if (kmValueParameter.varargElementType != null) {
        kmValueParameter.varargElementType!!
      } else {
        kmValueParameter.type!!
      }

      val classifier = type.classifier

      val isTypeVar = classifier is KmClassifier.TypeParameter

      type.resolveLambdaType(kmValueParameter.name, typeParameters) ?: if (isTypeVar.not()) {
        val isPrimitive =
          !type.isNullable && primitives.contains(classifier.toClassName(false).toString())

        val typeParametersOfParameter = type.getTypeParameters(typeParameters)
        val typeOfParameter =
          classifier.toClassName(type.isNullable).safelyParameterizedBy(typeParametersOfParameter)
        BasicParameter(
          kmValueParameter.name, typeOfParameter,
          kmValueParameter.varargElementType != null,
          isPrimitive
        )
      } else {
        BasicParameter(
          kmValueParameter.name, typeParameters[(classifier as KmClassifier.TypeParameter).id].copy(
            nullable = type.isNullable
          ),
          kmValueParameter.varargElementType != null,
          false
        )
      }
    }
  }

  private fun logError(msg: String, element: Element? = null) {
    processingEnv.messager.printMessage(Diagnostic.Kind.ERROR, msg, element)
  }

  private fun ImmutableKmType.getTypeParameters(typeParameters: List<TypeName>): List<TypeName> {
    return arguments.mapNotNull { typeProjection ->
      val params = typeProjection.type?.getTypeParameters(typeParameters)

      when (val classifier = typeProjection.type?.classifier) {
        is KmClassifier.Class, is KmClassifier.TypeAlias -> classifier.toClassName(
          typeProjection.type!!.isNullable
        ).safelyParameterizedBy(params)
        is KmClassifier.TypeParameter -> classifier.toTypeName(
          typeProjection.type?.isNullable ?: false, typeParameters
        )
        null -> null
      }
    }
  }

  private fun ClassName.safelyParameterizedBy(typeNames: List<TypeName>?): TypeName {
    return if (typeNames.isNullOrEmpty()) {
      this
    } else {
      this.parameterizedBy(typeNames)
    }
  }

  private fun KmClassifier.toClassName(nullable: Boolean): ClassName {
    return when (this) {
      is KmClassifier.Class -> name.toClassName().copy(nullable = nullable) as ClassName
      is KmClassifier.TypeAlias -> name.toClassName().copy(nullable = nullable) as ClassName
      is KmClassifier.TypeParameter -> error("Type parameters are not supported")
    }
  }

  private fun KmClassifier.toTypeName(nullable: Boolean, typeParameters: List<TypeName>): TypeName {
    return when (this) {
      is KmClassifier.Class -> name.toClassName().copy(nullable = nullable) as ClassName
      is KmClassifier.TypeAlias -> name.toClassName().copy(nullable = nullable) as ClassName
      is KmClassifier.TypeParameter -> typeParameters[id].copy(nullable = nullable)
    }
  }

  private fun ImmutableKmTypeParameter.toTypeName(): TypeVariableName =
    TypeVariableName(name = name, variance = variance.toModifier())

  private fun ImmutableKmType.toTypeName(typeParameters: List<TypeName>): TypeName =
    classifier.toTypeName(isNullable, typeParameters)

  private fun KmVariance.toModifier(): KModifier? =
    when (this) {
      KmVariance.INVARIANT -> null
      KmVariance.IN -> KModifier.IN
      KmVariance.OUT -> KModifier.OUT
    }

  private fun ImmutableKmType.resolveLambdaType(
    name: String,
    typeParameters: List<TypeName>
  ): LambdaParameter? =
    if (classifier is KmClassifier.Class) {
      val clazz = classifier as KmClassifier.Class

      if (clazz.name.startsWith("kotlin/Function")) {

        val lambdaArgs = this.getTypeParameters(typeParameters)

        val (receiver, paramsStart) = if (isExtensionType) lambdaArgs.first() to 1 else null to 0
        val (sus, paramsEnd) = if (flags and 2 != 0) true to lambdaArgs.size - 2 else false to lambdaArgs.size - 1

        val returnType = if (sus) {
          (lambdaArgs[lambdaArgs.size - 2] as ParameterizedTypeName).typeArguments[0]
        } else lambdaArgs.last()

        val params = lambdaArgs.subList(paramsStart, paramsEnd)

        LambdaParameter(name, sus, returnType = returnType, receiver = receiver,
          parameters = params)
      } else null
    } else null

  private fun String.toClassName(): ClassName {
    return ClassName(substringBeforeLast('/').replace('/', '.'), substringAfterLast('/'))
  }

  override fun getSupportedAnnotationTypes(): Set<String> {
    return setOf(EventBusService::class.java.name)
  }
}
