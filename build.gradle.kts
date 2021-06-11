plugins {
  kotlin("jvm") version "1.5.0" apply false
  kotlin("kapt") version "1.5.0" apply false
  `maven-publish`
}

group = "com.kobil.vertx"
version = "1.0.2"

val projectGroup = group
val projectVersion = version

val snapshotRepo: String by project
val releaseRepo: String by project
val deployUser: String by project
val deployPassword: String by project

subprojects {
  apply(plugin = "maven-publish")

  version = projectVersion
  group = projectGroup

  publishing {
    repositories {
      maven {
        name = "nexus"
        url = uri(if (version.toString().endsWith("SNAPSHOT")) snapshotRepo else releaseRepo)
        isAllowInsecureProtocol = true

        credentials {
          username = deployUser
          password = deployPassword
        }
      }
    }
  }
}
