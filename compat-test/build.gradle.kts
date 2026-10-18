plugins {
    kotlin("jvm") version "2.0.21"
    application
}

dependencies {
    implementation("dev.apartejs:secrets:1.1.1")
}

application {
    mainClass.set("MainKt")
}
