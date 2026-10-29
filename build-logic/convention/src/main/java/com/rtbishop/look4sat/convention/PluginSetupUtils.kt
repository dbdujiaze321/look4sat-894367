/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.rtbishop.look4sat.convention

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.ApkSigningConfig
import com.android.build.api.dsl.CommonExtension
import org.gradle.accessors.dm.LibrariesForLibs
import org.gradle.api.Project
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.accessors.runtime.extensionOf
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.plugin.use.PluginDependency
import org.jetbrains.kotlin.gradle.dsl.kotlinExtension

internal val Project.libs
    get(): LibrariesForLibs = extensionOf(this, "libs") as LibrariesForLibs

internal fun Project.applyPlugin(notation: Provider<PluginDependency>) {
    pluginManager.apply(notation.get().pluginId)
}

internal fun DependencyHandler.implementation(dependencyNotation: Any) =
    add("implementation", dependencyNotation)

internal fun DependencyHandler.debugImplementation(dependencyNotation: Any) =
    add("debugImplementation", dependencyNotation)

internal fun DependencyHandler.testImplementation(dependencyNotation: Any) =
    add("testImplementation", dependencyNotation)

internal fun DependencyHandler.androidTestImplementation(dependencyNotation: Any) =
    add("androidTestImplementation", dependencyNotation)

internal fun DependencyHandler.ksp(dependencyNotation: Any) =
    add("ksp", dependencyNotation)

internal fun Project.setupAndroidApp() {
    applyPlugin(libs.plugins.android.application)
    extensions.configure<ApplicationExtension> {
        namespace = libs.versions.packageName.get()
        compileSdk = libs.versions.compileSdk.get().toInt()
        defaultConfig {
            applicationId = libs.versions.packageName.get()
            minSdk = libs.versions.minSdk.get().toInt()
            versionCode = libs.versions.appVersionCode.get().toInt()
            versionName = libs.versions.appVersionName.get()
        }
        val releaseSigning = readReleaseSigning(this)
        buildTypes {
            debug {
                applicationIdSuffix = ".debug"
            }
            release {
                isMinifyEnabled = true
                isShrinkResources = true
                proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro"
                )
                // Absent on a clean checkout: the build still produces an unsigned APK.
                releaseSigning?.let { signingConfig = it }
            }
        }
        androidResources {
            generateLocaleConfig = true
            localeFilters.addAll(listOf("en", "es", "ru", "si", "tr", "uk", "zh"))
        }
        packaging { resources { excludes += listOf("META-INF/*") } }
    }
}

internal fun Project.setupAndroidLib() {
    applyPlugin(libs.plugins.android.library)
    extensions.configure<CommonExtension> {
        compileSdk = libs.versions.compileSdk.get().toInt()
        defaultConfig.minSdk = libs.versions.minSdk.get().toInt()
    }
    dependencies {
        androidTestImplementation(libs.bundles.androidTest)
    }
}

internal fun Project.setupCompose() {
    applyPlugin(libs.plugins.compose.compiler)
    extensions.configure<CommonExtension> {
        buildFeatures.compose = true
    }
    dependencies {
        implementation(platform(libs.compose.bom))
        implementation(libs.bundles.composeAll)
        debugImplementation(libs.bundles.composeDebug)
    }
}

internal fun Project.setupKotlin() {
    kotlinExtension.jvmToolchain(libs.versions.jdkVersion.get().toInt())
    dependencies {
        testImplementation(libs.bundles.unitTest)
    }
}

/**
 * Reads `keystore.properties` from the app module. The file is intentionally not in version
 * control: it holds the release key password, and a build without it must still succeed so that a
 * fresh clone can produce an unsigned release APK.
 */
private fun Project.readReleaseSigning(extension: ApplicationExtension): ApkSigningConfig? {
    // Lives at the repository root, not in the app module: it is local build material.
    val propsFile = rootProject.file("keystore.properties")
    if (!propsFile.exists()) return null
    val props = java.util.Properties().apply { propsFile.inputStream().use { load(it) } }
    val storePath = props.getProperty("storeFile") ?: return null
    return extension.signingConfigs.create("release") {
        storeFile = file(storePath)
        storePassword = props.getProperty("storePassword")
        keyAlias = props.getProperty("keyAlias")
        keyPassword = props.getProperty("keyPassword")
    }
}
