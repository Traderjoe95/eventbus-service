package com.kobil.vertx.ebservice.codegen

import com.kobil.vertx.ebservice.annotation.EventBusService
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.metadata.ImmutableKmFunction
import com.squareup.kotlinpoet.metadata.ImmutableKmType
import com.squareup.kotlinpoet.metadata.ImmutableKmValueParameter
import com.squareup.kotlinpoet.metadata.KotlinPoetMetadataPreview
import com.squareup.kotlinpoet.metadata.isInterface
import com.squareup.kotlinpoet.metadata.isNullable
import com.squareup.kotlinpoet.metadata.isSuspend
import com.squareup.kotlinpoet.metadata.toImmutableKmClass
import io.vertx.core.Vertx
import io.vertx.core.eventbus.EventBus
import kotlinx.metadata.KmClassifier
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
        .filter { kmFunction ->
          val classifier = kmFunction.returnType.classifier
          classifier is KmClassifier.Class || classifier is KmClassifier.TypeAlias
        }
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
      val returnTypeStr = kmFunction.returnType.classifier.toClassName(false).toString()
      if (!kmFunction.isSuspend && returnTypeStr != "kotlin.Unit") {
        logError("Function ${kmFunction.name} must be suspending")
        error("Function ${kmFunction.name} must be suspending")
      }

      val overloadId = overload.getValue(kmFunction.name)
      overload[kmFunction.name] = overloadId + 1

      val typeParameters = kmFunction.returnType.getTypeParameters()
      val returnType = kmFunction.returnType.classifier
        .toClassName(kmFunction.returnType.isNullable)
        .safelyParameterizedBy(typeParameters)

      val parameters = kmFunction.valueParameters
        .asSequence()
        .filter { parameter ->
          val classifier = parameter.type!!.classifier
          classifier is KmClassifier.Class || classifier is KmClassifier.TypeAlias
        }
        .toFunctionParameters()
        .toSet()

      ServiceFun(
        kmFunction.name, returnType, parameters, overloadId.toUInt(),
        isSuspend = kmFunction.isSuspend
      )
    }
  }

  private fun Sequence<ImmutableKmValueParameter>.toFunctionParameters(): Sequence<Parameter> {
    return map { kmValueParameter ->
      val type = if (kmValueParameter.varargElementType != null) {
        kmValueParameter.varargElementType!!
      } else {
        kmValueParameter.type!!
      }

      val classifier = type.classifier

      val isPrimitive =
        !type.isNullable && primitives.contains(classifier.toClassName(false).toString())

      val typeParametersOfParameter = type.getTypeParameters()
      val typeOfParameter =
        classifier.toClassName(type.isNullable).safelyParameterizedBy(typeParametersOfParameter)
      Parameter(
        kmValueParameter.name, typeOfParameter,
        kmValueParameter.varargElementType != null,
        isPrimitive
      )
    }
  }

  private fun logError(msg: String, element: Element? = null) {
    processingEnv.messager.printMessage(Diagnostic.Kind.ERROR, msg, element)
  }

  private fun ImmutableKmType.getTypeParameters(): List<TypeName> {
    return arguments.mapNotNull { typeProjection ->
      val params = typeProjection.type?.getTypeParameters()

      when (val classifier = typeProjection.type?.classifier) {
        is KmClassifier.Class -> classifier.toClassName(typeProjection.type!!.isNullable)
          .safelyParameterizedBy(params)
        is KmClassifier.TypeParameter,
        is KmClassifier.TypeAlias,
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

  private fun String.toClassName(): ClassName {
    return ClassName(substringBeforeLast('/').replace('/', '.'), substringAfterLast('/'))
  }

  override fun getSupportedAnnotationTypes(): Set<String> {
    return setOf(EventBusService::class.java.name)
  }
}
