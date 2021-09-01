package com.kobil.vertx.ebservice.codegen

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.Dynamic
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeVariableName
import com.squareup.kotlinpoet.WildcardTypeName
import java.util.Locale

data class Service(val name: ClassName, val functions: Set<ServiceFun>)

data class ServiceFun(
  val name: String,
  val typeParameters: List<TypeVariableName>,
  val returnType: TypeName,
  val parameters: Set<Parameter>,
  val overloadId: UInt = 0.toUInt(),
  val isSuspend: Boolean = true
) {
  val fullName: String
    get() = if (overloadId == 0.toUInt()) name else "${name}_$overloadId"

  val parameterContainer: String
    get() = if (overloadId == 0.toUInt()) "${name.replaceFirstChar { it.titlecase(Locale.getDefault()) }}Parameters"
    else "${name.replaceFirstChar { it.titlecase(Locale.getDefault()) }}Parameters${overloadId}"

  val isEventStyle: Boolean = returnType.toString() == "kotlin.Unit"
}

abstract class Parameter(val isVararg: Boolean, val isPrimitive: Boolean) {
  abstract val name: String
  abstract val type: TypeName

  fun typeParameters(): Set<TypeVariableName> = type.typeParameters()
}

class BasicParameter(
  override val name: String, override val type: TypeName, isVararg: Boolean,
  isPrimitive: Boolean = false
) : Parameter(isVararg, isPrimitive)

class LambdaParameter(
  override val name: String, val isSuspend: Boolean,
  parameters: List<TypeName>, returnType: TypeName, receiver: TypeName? = null
) : Parameter(false, false) {
  override val type: TypeName = LambdaTypeName.get(
    receiver, parameters.map { ParameterSpec.Companion.unnamed(it) }, returnType
  ).copy(suspending = isSuspend)
}

private fun TypeName.typeParameters(): Set<TypeVariableName> =
  when (this) {
    is TypeVariableName -> setOf(this)
    is LambdaTypeName ->
      (receiver?.typeParameters() ?: setOf()) +
        returnType.typeParameters() +
        parameters.flatMap { it.type.typeParameters() }
    is ParameterizedTypeName -> typeArguments.flatMap { it.typeParameters() }.toSet()
    is WildcardTypeName ->
      outTypes.flatMap { it.typeParameters() }.toSet() +
        inTypes.flatMap { it.typeParameters() }
    else -> setOf()
  }
