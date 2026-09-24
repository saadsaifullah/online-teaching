plugins { id("com.android.library"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }
android {
    namespace = "com.onlineteachers.shared"
    compileSdk = 36
    defaultConfig { minSdk = 30 }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    api(platform("androidx.compose:compose-bom:2025.10.01"))
    api("androidx.compose.material3:material3")
    api("androidx.compose.ui:ui")
    api("androidx.activity:activity-compose:1.11.0")
    api("androidx.core:core-ktx:1.17.0")
    api("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    api("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
}
