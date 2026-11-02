plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.moneytrack.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.moneytrack.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "0.2.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures { viewBinding = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core"))
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    implementation("com.google.android.material:material:1.13.0")
}
