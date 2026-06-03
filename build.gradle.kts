plugins {
    java
    id("com.gradleup.shadow") version "9.4.1"
}

group = "com.froquefy"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

java {
    toolchain {
        // Compile under a modern JDK but target 17 bytecode (Velocity's minimum)
        // so the jar runs on any Velocity-supported JVM.
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    // Velocity API + annotation processor (the @Plugin annotation generates
    // velocity-plugin.json at compile time — no hand-written descriptor needed).
    compileOnly("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")
    annotationProcessor("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")
    // Provided by Velocity at runtime; needed at compile time for the injected Logger.
    compileOnly("org.slf4j:slf4j-api:2.0.16")

    // Shaded into the fat jar (Velocity isolates each plugin's classloader).
    implementation("com.zaxxer:HikariCP:7.0.2")
    implementation("com.mysql:mysql-connector-j:9.7.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(17)
}

tasks.test {
    useJUnitPlatform()
}

tasks.shadowJar {
    archiveClassifier.set("")
    // Merge JDBC driver service files so the MySQL driver is discoverable.
    mergeServiceFiles()
}

// `build` (and therefore the default verification flow) produces the runnable fat jar.
tasks.build {
    dependsOn(tasks.shadowJar)
}
