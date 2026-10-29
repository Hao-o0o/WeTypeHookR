plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.serialization")
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
        minSdk = 33
        targetSdk = 35
        versionCode = 1400
        versionName = "1.4.0"

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
    // 模块资源注入宿主进程：必须使用非 0x7f 的资源包 ID，否则与宿主资源冲突。
    androidResources {
        additionalParameters +=
            listOf("--allow-reserved-package-id", "--package-id", "0x64")
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
    compileOnly("io.github.libxposed:api:102.0.0")
    // YukiHook：核心 DSL + libxposed 底座 + KSP 编译器（生成入口与 META-INF/xposed）
    implementation("com.highcapable.yukihookapi:yukihook-core:1.5.0")
    implementation("com.highcapable.yukihookapi:yukihook-runtime-libxposed:1.5.0")
    ksp("com.highcapable.yukihookapi:yukihook-compiler:1.5.0")
    // ModuleActivity 需要 AppCompat 宿主主题
    implementation("androidx.appcompat:appcompat:1.7.1")
    // 配置序列化（单快照 state.json）
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.9.3")
}
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}