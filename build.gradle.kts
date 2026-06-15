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
    implementation("com.discord4j:discord4j-core:3.3.2") // reactive, на Reactor
    implementation("io.projectreactor.kotlin:reactor-kotlin-extensions:1.2.2")

    implementation("dev.arbjerg:lavaplayer:2.2.4")        // основной плеер
    implementation("dev.lavalink.youtube:v2:1.18.1")      // YouTube source
    runtimeOnly("moe.kyokobot.libdave:natives-linux-x86-64:0.1.3")

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
    archiveClassifier.set("all")
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
