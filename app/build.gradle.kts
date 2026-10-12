plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.myapplication"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.myapplication"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // ══ 【这一行是低端电视上流畅与否的关键】══════════════════
            // 原本这里是：
            //     optimization { enable = false }
            // 也就是把 release 的代码优化【关掉了】。
            //
            // 对 Compose 应用来说这代价极大。Compose 的性能高度依赖
            // R8 做的跨函数内联和死代码消除 —— 关掉之后，
            // 每次重组都会走过一堆本该被优化掉的中间层。
            //
            // 实测表现（小米电视，澎湃 OS 3）：
            //     UI 线程 450ms/帧，而 GPU 只用 2~4ms
            // 也就是说瓶颈完全在 CPU 上。同一份代码在 x86 模拟器上
            // 却感觉不到卡 —— 因为模拟器跑在 PC 的强 CPU 上，有余量。
            // 到了电视的 Cortex-A55 级别核心上，差距就被放大了。
            //
            // 所以这里必须是 true。
            // ═══════════════════════════════════════════════════════════
            optimization {
                enable = true
            }

            isMinifyEnabled = true
            isShrinkResources = true

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )

            // ══ 【用 debug 签名，方便直接装到电视上试】══════════════════
            // 正式发布前要换成自己的 release keystore。
            // 现在这样是为了让 `adb install` 能直接装上，
            // 而不必每次都折腾签名配置。
            // 注意：debug 签名的 release 包【不能】上传应用商店。
            // ═══════════════════════════════════════════════════════════
            signingConfig = signingConfigs.getByName("debug")
        }

        debug {
            // debug 包保持不优化，编译快、方便调试。
            // 但【不要】拿它去评估电视上的流畅度 —— 那不是真实性能。
            applicationIdSuffix = ".debug"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}