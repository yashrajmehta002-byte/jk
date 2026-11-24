plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "in.bachatgroup.agent"
    compileSdk = 35
    defaultConfig {
        applicationId = "in.bachatgroup.agent"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0-compose"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":shared:finance-core"))
    implementation(project(":shared:data"))
    implementation(project(":shared:sync"))
    implementation(project(":shared:printing"))
    implementation(project(":shared:backup"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
}
