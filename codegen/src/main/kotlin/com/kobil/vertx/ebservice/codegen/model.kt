package com.kobil.vertx.ebservice.codegen

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.TypeName
import java.util.Locale

data class Service(val name: ClassName, val functions: Set<ServiceFun>)

data class ServiceFun(
  val name: String, val returnType: TypeName, val parameters: Set<Parameter>,
  val overloadId: UInt = 0.toUInt(), val isSuspend: Boolean = true
) {
  val fullName: String
    get() = if (overloadId == 0.toUInt()) name else "${name}_$overloadId"

  val parameterContainer: String
    get() = if (overloadId == 0.toUInt()) "${name.replaceFirstChar { it.titlecase(Locale.getDefault()) }}Parameters"
    else "${name.replaceFirstChar { it.titlecase(Locale.getDefault()) }}Parameters${overloadId}"

  val isEventStyle: Boolean = returnType.toString() == "kotlin.Unit"
}

data class Parameter(val name: String, val type: TypeName, val isVararg: Boolean,
                     val isPrimitive: Boolean = false)
