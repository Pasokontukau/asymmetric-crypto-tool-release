plugins {
    `java-library`
    `maven-publish`
}

// 共享内核的发布坐标。桌面端以固定版本依赖它，不再复制 ../android 源码。
group = "com.cryptoassistant"
version = "1.4.0"

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
    options.encoding = "UTF-8"
}

dependencies {
    // Android 的 Conscrypt AES/GCM 会在 Cipher.update 阶段缓存整段密文，大文件会 OOM；
    // BouncyCastle 的 GCMBlockCipher 支持真正的分块输入/输出，线格式仍是标准 AES-GCM。
    implementation(libs.bouncycastle.bcprov)
    // VOLU 分卷的 VolumeInfo 是桌面端用 Gson 2.10.1 序列化的 JSON；
    // 用同版本解析可保持字段/转义行为一致，避免自研 JSON 解析器的边界风险。
    implementation(libs.gson)
    testImplementation(libs.junit)
}

// 共享内核的默认适配类是可移植的 no-op；桌面端另有带落盘/监控的真实实现，
// 因此发布一个不含默认适配类的 classifier 变体，避免依赖 jar 覆盖桌面类。
val desktopJar by tasks.registering(Jar::class) {
    archiveClassifier.set("desktop")
    from(sourceSets.main.get().output)
    exclude("core/util/LogManager*.class", "core/util/OperationMonitor*.class")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifact(desktopJar)
            artifactId = "crypto-core"
            pom {
                name.set("crypto-core")
                description.set("Shared cryptography core for the Android app and desktop client")
                url.set("https://github.com/Pasokontukau/asymmetric-crypto-tool")
                licenses {
                    license {
                        name.set("GNU General Public License v3.0")
                        url.set("https://www.gnu.org/licenses/gpl-3.0.html")
                    }
                }
                scm {
                    connection.set("scm:git:https://github.com/Pasokontukau/asymmetric-crypto-tool.git")
                    developerConnection.set("scm:git:git@github.com:Pasokontukau/asymmetric-crypto-tool.git")
                    url.set("https://github.com/Pasokontukau/asymmetric-crypto-tool")
                }
            }
        }
    }

    // 有凭据时才注册 GitHub Packages；日常本地发布走 publishToMavenLocal，不需要 token。
    val githubActor = System.getenv("GITHUB_ACTOR")
    val githubToken = System.getenv("GITHUB_TOKEN")
    if (!githubActor.isNullOrBlank() && !githubToken.isNullOrBlank()) {
        repositories {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/Pasokontukau/asymmetric-crypto-tool")
                credentials {
                    username = githubActor
                    password = githubToken
                }
            }
        }
    }
}

// 互操作测试需要桌面端源码编译出的 bridge jar（仅测试使用，不进入 Android 产物）。
val desktopBridgeJarFile = project(":tools:desktop-bridge")
    .layout.buildDirectory.file("libs/desktop-bridge.jar")

tasks.withType<Test>().configureEach {
    // 大文件测试在 128 MB 堆下运行，确保实现没有把整段密文缓存在内存里。
    maxHeapSize = "128m"
    dependsOn(":tools:desktop-bridge:jar")
    systemProperty("desktop.bridge.jar", desktopBridgeJarFile.get().asFile.absolutePath)
    testLogging {
        events("passed", "failed", "skipped")
    }
}
