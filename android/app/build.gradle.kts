import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import java.util.UUID

// 每次构建生成一个新的构建 UUID 与编译时间，写进 BuildConfig，供「关于」页展示。
val buildUuid = UUID.randomUUID().toString()
val buildTime: String = try {
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())
} catch (e: Exception) {
    "unknown"
}

// 源码提交：回答「这个安装包是哪个提交打的」。没有 git（源码压缩包）或命令失败时写
// unknown，不猜测；构建时工作树不干净也算 HEAD，真实性由 dist/BUILD-INFO.txt 记录。
val gitCommit: String = try {
    val process = ProcessBuilder(
        "git", "-C", rootProject.projectDir.absolutePath, "rev-parse", "--short=9", "HEAD",
    ).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
    if (process.waitFor() == 0 && output.matches(Regex("[0-9a-f]{7,40}"))) output else "unknown"
} catch (e: Exception) {
    "unknown"
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// 本地 release 签名：密钥库与口令放在 android/keystore.properties（已 gitignore，不入库）。
// 缺少该文件时仍可正常构建，只是 release 产物未签名（与改动前行为一致）。
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.cryptoassistant.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.cryptoassistant.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 29
        versionName = "1.4.3"
        // 构建 UUID（每次构建唯一）与编译时间，均只读，不落盘、不用于任何协议逻辑。
        buildConfigField("String", "BUILD_UUID", "\"$buildUuid\"")
        buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")
        buildConfigField("String", "GIT_COMMIT", "\"$gitCommit\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // 注意：signingConfigs 必须先于 buildTypes 声明，否则 release 拿不到签名配置。
    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ""
            // G5 伪语言门禁：debug 包含 en-XA/en-XB（带重音、加长文本），用来检查英文变长的
            // 截断与重叠；release 不生成伪语言资源。
            isPseudoLocalesEnabled = true
            // 让 debug 与 release 共用本地签名：设备仪器测试（androidTest）要求与被测包同签名，
            // 同签名后才能安装到隔离的第二用户跑测试，主用户 release 与其数据完全不受影响。
            // 仅本地开发用；不影响 release 产物签名。
            if (keystoreProperties.isNotEmpty()) {
                signingConfig = signingConfigs.findByName("release")
            }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }

    sourceSets {
        // 互操作夹具与 shared/crypto-core 共用一份，避免两份测试数据漂移。
        getByName("androidTest").assets.srcDir("../../shared/crypto-core/src/test/resources")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.biometric)
    // BiometricPrompt 需要 FragmentActivity；fragment 1.2.x（biometric 1.1.0 默认带入）的
    // FragmentActivity 会给 startActivityForResult 做 16 位码校验，而 ComponentActivity 的
    // ActivityResultRegistry 生成 32 位码，Compose 启动器会被直接抛异常。1.5+ 已移除该重写。
    implementation(libs.androidx.fragment)

    // 从桌面端抽取的纯 JVM 加密核心（Phase 2）。
    implementation(project(":crypto-core"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.junit)
    // 设备端 Compose smoke：启动、设置 → 关于、检查更新开关。
    // androidTest 配置不继承 implementation 的 BOM 约束，必须在这里再声明一次。
    androidTestImplementation(platform(libs.androidx.compose.bom))
    // Compose 1.11.4 仍传递 Espresso 3.5.0，它在 Android 17/API 37 调用了已删除的
    // InputManager.getInstance()；3.7.0 改用 getSystemService，必须显式覆盖。
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
