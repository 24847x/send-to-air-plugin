plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.20.1"
}

group = "com.loudyo.sendtoair"
version = "1.0.0"

val pluginId = "com.loudyo.sendtoair"
val pluginName = "Send to Air"
val pluginVersion = project.version.toString()
// JetBrains Air (a required dependency) supports 2026.2+ only (since-build 262.8665),
// so sinceBuild matches it. IntelliJ IDEA Community ended with 2025.2, hence the unified IntelliJ IDEA.
val platformVersion = providers.gradleProperty("platformVersion").orElse("2026.2.4")
val airVersion = providers.gradleProperty("airVersion").orElse("262.8665.532")
val pluginSinceBuild = providers.gradleProperty("pluginSinceBuild").orElse("262.8665")

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdea(platformVersion.get())
        bundledPlugin("org.jetbrains.plugins.terminal")

        // Air is published per OS/arch on Marketplace, e.g. 262.8665.532-mac-arm64
        val os = System.getProperty("os.name").lowercase().let {
            when {
                it.contains("mac") -> "mac"
                it.contains("win") -> "windows"
                else -> "linux"
            }
        }
        val arch = System.getProperty("os.arch").let {
            if (it == "aarch64" || it == "arm64") "arm64" else "x86_64"
        }
        plugin("com.intellij.air", "${airVersion.get()}-$os-$arch")
    }
}

intellijPlatform {
    pluginConfiguration {
        id = pluginId
        name = pluginName
        version = pluginVersion
        changeNotes = """
            <p>1.0.0</p>
            <ul>
              <li>Initial release.</li>
              <li>Action and shortcut (Alt+Shift+A) for sending a reference to the selected code to JetBrains Air, with the file name and line range.</li>
              <li>Send files and folders from the Project view.</li>
              <li>Supports both Air's chat prompt and terminal-based sessions such as Claude Code.</li>
            </ul>
        """.trimIndent()
        ideaVersion {
            sinceBuild = pluginSinceBuild.get()
        }
        vendor {
            name = "24847x"
            email = "send-to-air@loudyo.com"
        }
    }
    instrumentCode = false
    buildSearchableOptions = false
}

kotlin {
    jvmToolchain(21)
}
