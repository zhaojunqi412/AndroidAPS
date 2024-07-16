object KtsBuildVersions {

    const val gradle = "8.2.0"
    const val kotlin = "1.9.10"
}

plugins {
    `kotlin-dsl`
    `kotlin-dsl-precompiled-script-plugins`
}

repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation("com.android.tools.build:gradle:${KtsBuildVersions.gradle}")
    implementation(kotlin("gradle-plugin", version = KtsBuildVersions.kotlin))
    implementation(kotlin("allopen", version = KtsBuildVersions.kotlin))
}