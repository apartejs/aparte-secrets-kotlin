plugins {
    kotlin("jvm") version "2.0.21"
    application
}

dependencies {
    implementation("dev.apartejs:secrets:1.1.0")
}

application {
    mainClass.set("MainKt")
}
