plugins {
    id("com.android.application")
}

android {
    namespace = "eu.kanade.tachiyomi.extension.all.yandere"
    compileSdk = 36

    defaultConfig {
        applicationId = "eu.kanade.tachiyomi.extension.all.yandere"
        minSdk = 21
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    compileOnly(project(":lib:extensions-lib"))
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("org.jsoup:jsoup:1.22.1")
}
