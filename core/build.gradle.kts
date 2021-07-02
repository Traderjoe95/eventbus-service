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
  implementation("io.vertx:vertx-core:4.1.1")
  api(project(":annotation"))
}

val compileKotlin: KotlinCompile by tasks
compileKotlin.kotlinOptions.jvmTarget = "16"

val compileTestKotlin: KotlinCompile by tasks
compileTestKotlin.kotlinOptions.jvmTarget = "16"

publishing {
  publications {
    create<MavenPublication>("core") {
      artifactId = "ebservice"

      from(components["kotlin"])
    }
  }
}
