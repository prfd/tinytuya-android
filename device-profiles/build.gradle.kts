import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktfmt.gradle)
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

ktfmt {
    googleStyle()
    maxWidth.set(100)
    removeUnusedImports.set(false)
}

dependencies {
    implementation(project(":device-core"))
    testImplementation(libs.junit)
}
