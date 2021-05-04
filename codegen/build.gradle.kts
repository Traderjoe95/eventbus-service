import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  kotlin("jvm")
  `maven-publish`
}

repositories {
  mavenCentral()
}

dependencies {
  implementation(kotlin("stdlib-jdk8"))
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.5.0-RC")

  implementation("io.vertx:vertx-core:4.0.3")
  implementation("com.squareup:kotlinpoet:1.8.0")
  implementation("com.squareup:kotlinpoet-metadata:1.8.0")
  implementation(project(":core"))
}

val compileKotlin: KotlinCompile by tasks
compileKotlin.kotlinOptions.jvmTarget = "15"

val compileTestKotlin: KotlinCompile by tasks
compileTestKotlin.kotlinOptions.jvmTarget = "15"

publishing {
  publications {
    create<MavenPublication>("codegen") {
      artifactId = "ebservice-codegen"

      from(components["kotlin"])
    }
  }
}
