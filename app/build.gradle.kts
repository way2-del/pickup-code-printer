plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.pickup.print"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.pickup.print"
        minSdk = 33
        targetSdk = 37
        versionCode = 2
        versionName = "2.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
        }
    }
}

composeCompiler {
    stabilityConfigurationFiles.set(
        listOf(project.layout.projectDirectory.file("compose-stability.conf"))
    )
}

// miuix-ui 已 fork 到本地源码，排除传递依赖中的 miuix-ui jar 避免 R8 重复定义
configurations.all {
    exclude(group = "top.yukonga.miuix.kmp", module = "miuix-ui-android")
}

dependencies {
    implementation(files("libs/LPAPI-2026-01-08-R.jar"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.miuix.preference)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.squircle)
    implementation(libs.miuix.blur)

    implementation(libs.navigationevent.compose)
    implementation("org.jetbrains:annotations:26.1.0")
    implementation(libs.materialKolor.utilities)

    // OCR
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")

    // Camera
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")

    // Shizuku（超级岛 XMSF bypass）
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
