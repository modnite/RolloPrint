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
        versionCode = 3
        versionName = "1.0.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
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