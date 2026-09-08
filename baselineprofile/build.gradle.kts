plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "com.jjw.easygallery.baselineprofile"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        // Macrobenchmark 는 API 28+, 비루팅 기기에서 프로파일 수집은 API 33+ 가 필요
        minSdk = 33
        targetSdk = libs.versions.targetSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // 벤치마크 대상 앱 모듈. 플러그인이 :app 에 nonMinifiedRelease / benchmarkRelease 변형을 만든다
    targetProjectPath = ":app"

    // 물리 기기 없이도 프로파일을 만들 수 있는 Gradle Managed Device (aosp 이미지 = root 가능)
    testOptions.managedDevices.allDevices {
        create<com.android.build.api.dsl.ManagedVirtualDevice>("pixel6Api34") {
            device = "Pixel 6"
            apiLevel = 34
            systemImageSource = "aosp"
        }
    }
}

kotlin {
    jvmToolchain(17)
}

baselineProfile {
    // ./gradlew :app:generateBaselineProfile 이 이 기기에서 수집한다. 연결된 실기기(API 33+)도 허용
    managedDevices += "pixel6Api34"
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.espresso.core)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
