plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// One code base, two audiences. The public build is the one published on F-Droid; the private
// one adds what only this workstation can serve (cleaning and transcription by the computer
// behind the WebDAV folder). Its version code stays 500 ahead, so a public release can never
// land on the phone as an "update" and quietly take those features away.
val baseVersionCode = 15
val baseVersionName = "1.7.1"

android {
    namespace = "com.freedomfighter.readersrecorder"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.freedomfighter.readersrecorder"
        minSdk = 26
        targetSdk = 34
        versionCode = baseVersionCode
        versionName = baseVersionName
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild { cmake { arguments += listOf("-DGGML_NATIVE=OFF", "-DANDROID_STL=c++_static") } }
    }

    flavorDimensions += "audience"
    productFlavors {
        create("publique") {
            dimension = "audience"
            // Published on F-Droid. A cloud folder is an export: the phone sends what it made and
            // waits for nothing in return.
            buildConfigField("boolean", "PRIVATE", "false")
        }
        create("prive") {
            dimension = "audience"
            // Never published: the computer behind the WebDAV folder cleans, transcribes and
            // summarises. Same application id, so it updates the app already on the phone.
            versionCode = baseVersionCode + 500
            versionNameSuffix = "-prive"
            buildConfigField("boolean", "PRIVATE", "true")
        }
    }

    buildTypes { release { isMinifyEnabled = false } }
    ndkVersion = "27.1.12297006"
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
