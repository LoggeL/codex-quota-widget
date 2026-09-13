plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "top.logge.codexquota"
    compileSdk = 35

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    defaultConfig {
        applicationId = "top.logge.codexquota"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "1.0.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("distribution") {
            storeFile = System.getenv("CODEX_WIDGET_KEYSTORE")?.let { file(it) }
            storePassword = System.getenv("CODEX_WIDGET_STORE_PASSWORD")
            keyAlias = System.getenv("CODEX_WIDGET_KEY_ALIAS")
            keyPassword = System.getenv("CODEX_WIDGET_KEY_PASSWORD")
        }
    }
    buildTypes {
        getByName("release") {
            isDebuggable = false
            if (System.getenv("CODEX_WIDGET_KEYSTORE") != null) signingConfig = signingConfigs.getByName("distribution")
        }
    }
    buildFeatures { buildConfig = true }

    testBuildType = providers.gradleProperty("testBuildType").getOrElse("debug")

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("junit:junit:4.13.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("org.robolectric:robolectric:4.16.1")
}
