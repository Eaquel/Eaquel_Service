import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val APP_VERSION_CODE = 1
val APP_VERSION_NAME = "1.0.0"

android {
    namespace         = "com.eaquel.service"
    compileSdk        = 36
    buildToolsVersion = "36.0.0"
    ndkVersion        = "29.0.14206865"

    defaultConfig {
        applicationId = "com.eaquel.service"
        minSdk        = 30
        targetSdk     = 36
        versionCode   = APP_VERSION_CODE
        versionName   = APP_VERSION_NAME

        buildConfigField("String",  "APP_VERSION", "\"$APP_VERSION_NAME\"")
        buildConfigField("String",  "PKG",         "\"com.eaquel.service\"")
        buildConfigField("int",     "MIN_SDK",      "30")
        buildConfigField("int",     "MAX_SDK",      "36")

        ndk { abiFilters += setOf("arm64-v8a", "armeabi-v7a") }

        externalNativeBuild {
            cmake {
                cppFlags += listOf(
                    "-std=c++26",
                    "-O3",
                    "-fvisibility=hidden",
                    "-fstack-protector-all",
                    "-fPIC",
                    "-D_FORTIFY_SOURCE=3",
                    "-DANDROID_STL=c++_shared",
                    "-DEAQUEL_VERSION=1",
                    "-DEAQUEL_MIN_SDK=30",
                    "-DEAQUEL_MAX_SDK=36"
                )
                arguments += listOf(
                    "-DANDROID_PLATFORM=android-30",
                    "-DANDROID_STL=c++_shared",
                    "-DCMAKE_BUILD_TYPE=Release"
                )
            }
        }
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("Source/Main/AndroidManifest.xml")
            kotlin.srcDirs("Source/Main/Kotlin")
            res.srcDirs("Source/Main/res")
            assets.srcDirs("Source/Main/Assets")
        }
    }

    externalNativeBuild {
        cmake {
            path    = file("Source/Main/Native/CMakeLists.txt")
            version = "4.3.2"
        }
    }

    signingConfigs {
        create("release") {
            val f = rootProject.file("signing.properties")
            if (f.canRead()) {
                val p = Properties().apply { load(f.inputStream()) }
                storeFile     = file(p["KEYSTORE_FILE"]!!)
                storePassword = p["KEYSTORE_PASSWORD"] as String
                keyAlias      = p["KEYSTORE_ALIAS"] as String
                keyPassword   = p["KEYSTORE_ALIAS_PASSWORD"] as String
            } else {
                storeFile     = signingConfigs.getByName("debug").storeFile
                storePassword = signingConfigs.getByName("debug").storePassword
                keyAlias      = signingConfigs.getByName("debug").keyAlias
                keyPassword   = signingConfigs.getByName("debug").keyPassword
            }
            v1SigningEnabled = false
            v2SigningEnabled = false
            v3SigningEnabled = true
            v4SigningEnabled = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled   = true
            isShrinkResources = true
            isDebuggable      = false
            signingConfig     = signingConfigs.getByName("release")
            buildConfigField("boolean", "ANTICHEAT_ENABLED", "true")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }

    kotlinOptions {
        jvmTarget = "25"
        freeCompilerArgs += listOf(
            "-Xjvm-default=all",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=androidx.compose.animation.ExperimentalAnimationApi",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi"
        )
    }

    buildFeatures {
        compose     = true
        buildConfig = true
    }

    splits {
        abi {
            isEnable       = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "DebugProbesKt.bin",
                "META-INF/versions/**"
            )
        }
        jniLibs { useLegacyPackaging = false }
    }

    lint {
        abortOnError = false
        disable      += setOf("MissingTranslation", "ObsoleteLintCustomCheck")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    val bom = platform(libs.androidx.compose.bom)
    implementation(bom)
    androidTestImplementation(bom)

    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.core.splashscreen)
}
