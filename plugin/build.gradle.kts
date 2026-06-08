plugins {
    kotlin("jvm") version "2.3.20"
    `java-gradle-plugin`
}

group = property("sdkGroup") as String
version = property("sdkVersion") as String

repositories {
    mavenCentral()
}

dependencies {
    implementation(gradleApi())
    implementation("com.google.devtools.ksp:symbol-processing-api:2.3.6")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
}

gradlePlugin {
    plugins {
        create("lightSdk") {
            id = "com.thelightphone.light-sdk"
            implementationClass = "com.thelightphone.plugin.LightSdkPlugin"
        }
    }
}
