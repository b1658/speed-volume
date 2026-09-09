plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "co.screenmate.can.autovolume"
    compileSdk = 34
    defaultConfig {
        applicationId = "co.screenmate.can.autovolume"
        minSdk = 34
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
    // dadb (pulled in transitively via :tx-client) bundles META-INF entries that collide on merge.
    packaging { resources.excludes += "META-INF/*" }
}

dependencies {
    implementation(project(":privileged-client"))
    implementation(project(":tx-client")) // CanTx: left-scroll volume over the root TX transport
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("junit:junit:4.13.2")
}
