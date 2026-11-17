plugins { java }

group = "cn.mc"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Optional offline build using a verified Paper installation's libraries.
    if (project.hasProperty("localLibraries")) {
        val local = fileTree(project.property("localLibraries")!!) { include("**/*.jar") }
        compileOnly(local)
        compileOnly(files("build/tools/annotations-26.0.2.jar"))
        testImplementation(local)
        testImplementation(files("build/tools/junit-platform-console-standalone-1.12.2.jar"))
    } else {
        compileOnly("io.papermc.paper:paper-api:26.3.build.252-stable")
        testImplementation("io.papermc.paper:paper-api:26.3.build.252-stable")
        testImplementation(platform("org.junit:junit-bom:5.12.2"))
        testImplementation("org.junit.jupiter:junit-jupiter")
        testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    }
}

java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }
tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("paper-plugin.yml") { expand("version" to project.version) }
}
tasks.test { useJUnitPlatform() }
tasks.jar { archiveFileName.set("EnchantmentWorkshop-${project.version}.jar") }

val integration = sourceSets.create("integration") {
    compileClasspath += sourceSets.main.get().output + configurations.compileClasspath.get()
    compileClasspath += files("build/local-verification/server/versions/26.3/paper-26.3.jar")
    runtimeClasspath += output + compileClasspath
}
tasks.register<Jar>("integrationJar") {
    dependsOn(integration.classesTaskName)
    from(integration.output)
    archiveFileName.set("EnchantmentWorkshopProbe.jar")
}
