plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }
android {
    namespace = "com.onlineteachers.admin"
    compileSdk = 36
    defaultConfig { applicationId = "com.onlineteachers.admin"; minSdk = 30; targetSdk = 36; versionCode = 1; versionName = "1.0.0" }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildTypes { release { isMinifyEnabled = false } }
}
dependencies { implementation(project(":shared")) }
