import org.jetbrains.changelog.exceptions.MissingVersionException

buildscript {
    repositories {
        maven { setUrl("https://cache-redirector.jetbrains.com/maven-central") }
    }

    // https://search.maven.org/artifact/com.jetbrains.rd/rd-gen
    dependencies {
        classpath("com.jetbrains.rd:rd-gen:2023.3.2")
    }
}

plugins {
    id("me.filippov.gradle.jvm.wrapper") version "0.14.0"
    id("org.jetbrains.changelog") version "2.0.0"
    id("org.jetbrains.intellij.platform") version "2.0.0-SNAPSHOT"
    id("org.jetbrains.kotlin.jvm") version "1.8.10"
}

repositories {
    intellijPlatform {
        defaultRepositories()
    }
}


apply {
    plugin("com.jetbrains.rdgen")
}

val dotNetPluginId = "AvaloniaRider.Plugin"
val intellijPluginId = "avalonia-rider"

val riderSdkVersion: String by project
val untilBuildVersion: String by project
val pluginVersionBase: String by project
val buildRelease: String by project

dependencies {
    intellijPlatform {
        rider(riderSdkVersion)
    }

    implementation("de.undercouch:bson4jackson:2.13.1")

    testImplementation("org.testng:testng:7.7.0")
}

val buildConfiguration = ext.properties["buildConfiguration"] ?: "Debug"
val buildNumber = (ext.properties["buildNumber"] as String?)?.toInt() ?: 0

val rdLibDirectory: () -> File = { file("${tasks.setupDependencies.get().idea.get().classes}/lib/rd") }
extra["rdLibDirectory"] = rdLibDirectory

val dotNetSrcDir = File(projectDir, "src/dotnet")

val dotNetSdkGeneratedPropsFile = File(projectDir, "build/DotNetSdkPath.Generated.props")
val nuGetConfigFile = File(projectDir, "nuget.config")

version =
    if (buildRelease.equals("true", ignoreCase = true) || buildRelease == "1") pluginVersionBase
    else "$pluginVersionBase.$buildNumber"

fun File.writeTextIfChanged(content: String) {
    val bytes = content.toByteArray()

    if (!exists() || !readBytes().contentEquals(bytes)) {
        println("Writing $path")
        parentFile.mkdirs()
        writeBytes(bytes)
    }
}

repositories {
    maven { setUrl("https://cache-redirector.jetbrains.com/maven-central") }
}

sourceSets {
    main {
        kotlin.srcDir("src/rider/main/kotlin")
        resources.srcDir("src/rider/main/resources")
    }
}

apply(plugin = "com.jetbrains.rdgen")

configure<com.jetbrains.rd.generator.gradle.RdGenExtension> {
    val modelDir = file("$projectDir/protocol/src/main/kotlin/model")
    val csOutput = file("$projectDir/src/dotnet/AvaloniaRider.Plugin/Model")
    val ktOutput = file("$projectDir/src/rider/main/kotlin/me/fornever/avaloniarider/model")

    verbose = true
    classpath({
        "${rdLibDirectory()}/rider-model.jar"
    })
    sources("$modelDir/rider")
    hashFolder = "$rootDir/build/rdgen/rider"
    packages = "model.rider"

    generator {
        language = "kotlin"
        transform = "asis"
        root = "com.jetbrains.rider.model.nova.ide.IdeRoot"
        directory = "$ktOutput"
    }

    generator {
        language = "csharp"
        transform = "reversed"
        root = "com.jetbrains.rider.model.nova.ide.IdeRoot"
        directory = "$csOutput"
    }
}

intellij {
    plugins.set(listOf("com.intellij.javafx:1.0.3", "com.jetbrains.xaml.previewer"))
}

tasks {
    wrapper {
        gradleVersion = "8.1.1"
        distributionType = Wrapper.DistributionType.ALL
    }

    val riderSdkPath by lazy {
        val path = setupDependencies.get().idea.get().classes.resolve("lib/DotNetSdkForRdPlugins")
        if (!path.isDirectory) error("$path does not exist or not a directory")

        println("Rider SDK path: $path")
        return@lazy path
    }

    val generateDotNetSdkProperties by registering {
        doLast {
            dotNetSdkGeneratedPropsFile.writeTextIfChanged("""<Project>
  <PropertyGroup>
    <DotNetSdkPath>$riderSdkPath</DotNetSdkPath>
  </PropertyGroup>
</Project>
""")
        }
    }

    val generateNuGetConfig by registering {
        doLast {
            nuGetConfigFile.writeTextIfChanged("""<?xml version="1.0" encoding="utf-8"?>
<configuration>
  <packageSources>
    <add key="rider-sdk" value="$riderSdkPath" />
  </packageSources>
</configuration>
""")
        }
    }

    val rdgen by existing

    register("prepare") {
        dependsOn(rdgen, generateDotNetSdkProperties, generateNuGetConfig)
    }

    val compileDotNet by registering {
        dependsOn(rdgen, generateDotNetSdkProperties, generateNuGetConfig)
        doLast {
            exec {
                executable("dotnet")
                args("build", "-c", buildConfiguration)
            }
        }
    }

    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        dependsOn(rdgen)
        kotlinOptions {
            jvmTarget = "17"
            freeCompilerArgs = freeCompilerArgs + "-opt-in=kotlin.RequiresOptIn"
        }
    }

    patchPluginXml {
        untilBuild.set(untilBuildVersion)
        val latestChangelog = try {
            changelog.getUnreleased()
        } catch (_: MissingVersionException) {
            changelog.getLatest()
        }
        changeNotes.set(provider {
            changelog.renderItem(
                latestChangelog
                    .withHeader(false)
                    .withEmptySections(false),
                org.jetbrains.changelog.Changelog.OutputType.HTML
            )
        })
    }

    buildPlugin {
        dependsOn(compileDotNet)
    }

    runIde {
        jvmArgs("-Xmx1500m")
    }

    test {
        useTestNG()
        testLogging {
            showStandardStreams = true
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
        environment["LOCAL_ENV_RUN"] = "true"
    }

    withType<org.jetbrains.intellij.tasks.PrepareSandboxTask> {
        dependsOn(compileDotNet)

        val reSharperPluginDesc = "fvnever.$intellijPluginId"
        from("src/extensions") { into("${rootProject.name}/dotnet/Extensions/$reSharperPluginDesc") }

        val outputFolder = file("$dotNetSrcDir/$dotNetPluginId/bin/${dotNetPluginId}/$buildConfiguration")
        val dllFiles = listOf(
            "$outputFolder/${dotNetPluginId}.dll",
            "$outputFolder/${dotNetPluginId}.pdb"
        )

        for (f in dllFiles) {
            from(f) { into("${rootProject.name}/dotnet") }
        }

        doLast {
            for (f in dllFiles) {
                val file = file(f)
                if (!file.exists()) throw RuntimeException("File \"$file\" does not exist")
            }
        }
    }
}
