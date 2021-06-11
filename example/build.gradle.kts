import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  kotlin("jvm")
  kotlin("kapt")
}

repositories {
  mavenCentral()
}

dependencies {
  implementation(kotlin("stdlib-jdk8"))
  implementation(project(":core"))
  kapt(project(":codegen"))

  implementation("io.vertx:vertx-core:4.0.3")
  implementation("io.vertx:vertx-lang-kotlin:4.0.3")
  implementation("io.vertx:vertx-lang-kotlin-coroutines:4.0.3")
}

val compileKotlin: KotlinCompile by tasks
compileKotlin.kotlinOptions.jvmTarget = "16"

val compileTestKotlin: KotlinCompile by tasks
compileTestKotlin.kotlinOptions.jvmTarget = "16"
