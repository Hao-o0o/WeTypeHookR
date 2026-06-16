plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    signingConfigs {
        create("release") {

            val keystoreFile = project.findProperty("MY_KEYSTORE_FILE") as String?

            if (keystoreFile != null) {

                storeFile = file(keystoreFile)
                storePassword = project.property("MY_KEYSTORE_PASSWORD") as String
                keyAlias = project.property("MY_KEY_ALIAS") as String
                keyPassword = project.property("MY_KEY_PASSWORD") as String
            }
        }
    }
    namespace = "com.haostoo.wetypehookr"
    compileSdk {
        version = release(37)
    }
    splits {
        abi {
            isEnable = true
            reset()
            include(
                "armeabi-v7a",
                "arm64-v8a"
            )
            isUniversalApk = true
        }
    }

    defaultConfig {
        applicationId = "com.haostoo.wetypehookr"
        minSdk = 30
        targetSdk = 35
        versionCode = 1217
        versionName = "1.2.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation(platform("androidx.compose:compose-bom:2026.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.04.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    compileOnly("io.github.libxposed:api:101.0.1")
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.0")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.0")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.9.0")
    implementation("androidx.navigation:navigation-compose:2.9.8")
    implementation("androidx.navigationevent:navigationevent-compose:1.1.0")
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("com.github.jeziellago:compose-markdown:0.5.4")
}
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}