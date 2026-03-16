plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("org.jetbrains.kotlin.kapt")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.kubedroid.app"
    compileSdk = 35

    val releaseStoreFile = providers.gradleProperty("KUBEDROID_STORE_FILE")
    val releaseStorePassword = providers.gradleProperty("KUBEDROID_STORE_PASSWORD")
    val releaseKeyAlias = providers.gradleProperty("KUBEDROID_KEY_ALIAS")
    val releaseKeyPassword = providers.gradleProperty("KUBEDROID_KEY_PASSWORD")
    val hasReleaseSigning = releaseStoreFile.isPresent &&
        releaseStorePassword.isPresent &&
        releaseKeyAlias.isPresent &&
        releaseKeyPassword.isPresent
    val isReleaseTaskRequested = gradle.startParameter.taskNames.any {
        it.contains("release", ignoreCase = true)
    }

    defaultConfig {
        applicationId = "com.kubedroid.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (isReleaseTaskRequested) {
                check(hasReleaseSigning) {
                    "Release signing is required. Set KUBEDROID_STORE_FILE, KUBEDROID_STORE_PASSWORD, KUBEDROID_KEY_ALIAS, and KUBEDROID_KEY_PASSWORD."
                }
            }
            if (hasReleaseSigning) {
                storeFile = file(releaseStoreFile.get())
                storePassword = releaseStorePassword.get()
                keyAlias = releaseKeyAlias.get()
                keyPassword = releaseKeyPassword.get()
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
        resources {
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE.md"
            excludes += "META-INF/NOTICE.md"
        }
    }
}

kapt {
    correctErrorTypes = true
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.ui)
    implementation(libs.ui.graphics)
    implementation(libs.ui.tooling.preview)
    implementation("androidx.compose.ui:ui-text-google-fonts")
    implementation(libs.material3)
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.window:window:1.3.0")

    implementation(libs.navigation.compose)
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")

    implementation("com.google.dagger:hilt-android:2.52")
    kapt("com.google.dagger:hilt-compiler:2.52")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    implementation(project(":core:network"))
    implementation(project(":core:security"))
    implementation(project(":core:ui"))

    implementation(project(":feature:pods"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:resources"))
    implementation(project(":feature:deployments"))
    implementation(project(":feature:nodes"))
    implementation(project(":feature:helm"))
    implementation(project(":feature:crd"))
    implementation(project(":feature:rbac"))
    implementation(project(":feature:events"))
    implementation(project(":feature:storage"))
    implementation(project(":feature:network"))
    implementation(project(":feature:widget"))
    implementation(project(":feature:onboarding"))

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.ui.test.junit4)
    debugImplementation(libs.ui.tooling)
    debugImplementation(libs.ui.test.manifest)
}

tasks.matching { it.name == "bundleRelease" }.configureEach {
    doLast {
        println("Native debug symbols at: app/build/outputs/native-debug-symbols/release/")
    }
}
