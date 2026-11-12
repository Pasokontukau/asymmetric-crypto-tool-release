plugins {
    `java-library`
}

/**
 * 该模块只用于互操作测试：
 * - 加密内核直接依赖共享模块 `:crypto-core`（源码在仓库根 `shared/crypto-core`）；
 * - 直接嵌入共享内核（含它自带的可移植默认 `LogManager` / `OperationMonitor`），
 *   不再维护第二份桩；
 * - 提供 interop.InteropCli 供 crypto-core 测试与主机端验证调用。
 *
 * 它不是 Android 产物的一部分，也不会被 app 模块依赖。
 */
java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

dependencies {
    // 共享内核是唯一实现；bridge 不再复制 crypto/、core/ 源码。
    implementation(project(":crypto-core"))
}

tasks.named<JavaCompile>("compileJava") {
    options.release.set(8)
    options.encoding = "UTF-8"
}

val coreJar = project(":crypto-core").tasks.named<Jar>("jar")

tasks.named<Jar>("jar") {
    dependsOn(coreJar)
    archiveFileName.set("desktop-bridge.jar")
    manifest {
        attributes["Main-Class"] = "interop.InteropCli"
    }
    // 测试用 `java -cp desktop-bridge.jar` 直接启动，因此把共享内核也打进 jar。
    // 共享内核自带可移植的 no-op 适配类，bridge 不再排除它们，也不再维护第二份桩。
    from(coreJar.map { zipTree(it.archiveFile.get().asFile) }) {
        exclude("META-INF/**")
    }
}

tasks.register<JavaExec>("generateInteropFixture") {
    dependsOn(tasks.named("jar"))
    group = "verification"
    description = "用共享内核生成互操作夹具"
    // runtimeClasspath 同时包含 bridge、共享内核与 Gson；只给 jar 会在 VOLU 类加载时缺 Gson。
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("interop.InteropCli")
    args(
        "generate",
        project(":crypto-core").file("src/test/resources/interop/desktop-fixture.properties").absolutePath,
    )
}

tasks.register<JavaExec>("generateSingleFileFixture") {
    dependsOn(tasks.named("jar"))
    group = "verification"
    description = "用共享内核 FileCryptoEngine 生成单文件 .aegix 夹具"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("interop.InteropCli")
    args(
        "generate-single",
        project(":crypto-core").file("src/test/resources/single").absolutePath,
    )
}

tasks.register<JavaExec>("generateExternalPartFixture") {
    dependsOn(tasks.named("jar"))
    group = "verification"
    description = "用共享内核 VolumePartWriter 生成当前外部分卷夹具"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("interop.InteropCli")
    args(
        "generate-external",
        project(":crypto-core").file("src/test/resources/external").absolutePath,
    )
}
