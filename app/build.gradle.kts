plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.rolloprint"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.rolloprint"
        minSdk = 24
        targetSdk = 37
        versionCode = 15
        versionName = "1.0.14"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = file("rolloprint.jks")
            storePassword = "rolloprint123"
            keyAlias = "rolloprint"
            keyPassword = "rolloprint123"
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = true
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            optimization {
                enable = false
            }
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.activity.ktx)
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.material)
    testImplementation(libs.junit)
    // Library removed as we now use the expert native TSPL2 manager
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set("rolloprint.apk")
        }
    }
}
