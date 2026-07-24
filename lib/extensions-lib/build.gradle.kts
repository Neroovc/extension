plugins {
    id("com.android.library")
}

android {
    namespace = "eu.kanade.tachiyomi.extensions"
    compileSdk = 36

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api("com.squareup.okhttp3:okhttp:5.3.2")
    api("org.jsoup:jsoup:1.22.1")
    implementation("io.reactivex:rxjava:1.3.8")
    implementation("io.reactivex:rxandroid:1.2.1")
    compileOnly("androidx.preference:preference-ktx:1.2.1")
}
