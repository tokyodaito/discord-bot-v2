import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    kotlin("jvm") version "2.1.10"
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "org.bogsnebes.discordbot"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://maven.lavalink.dev/releases")
}

dependencies {
    implementation("com.discord4j:discord4j-core:3.3.0") // reactive, на Reactor

    implementation("dev.arbjerg:lavaplayer:2.2.4")        // основной плеер
    implementation("dev.lavalink.youtube:v2:1.16.0")      // YouTube source

    implementation("ch.qos.logback:logback-classic:1.5.21")
}

application {
    mainClass.set("org.bogsnebes.discordbot.MainKt")
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<ShadowJar> {
    archiveClassifier.set("")
    mergeServiceFiles()
    manifest {
        attributes["Main-Class"] = application.mainClass.get()
    }
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = application.mainClass.get()
    }
}

tasks.build {
    dependsOn(tasks.named("shadowJar"))
}
