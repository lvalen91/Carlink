plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jlleitschuh.gradle.ktlint")
    id("io.gitlab.arturbosch.detekt")
}

android {
    namespace = "com.carlink"
    compileSdk = 37

    // Owner identity for the cluster icon ContentProvider hook (issue #6).
    // A FORK changes ONLY ownerApplicationId below — applicationId and the play-flavor
    // cluster icon authority both follow it automatically.
    val ownerApplicationId = "zeno.carlink"
    val gmClusterIconAuthority =
        "com.google.android.apps.automotive.templates.host.ClusterIconContentProvider"

//###############################################
//###############################################
//###############################################

    defaultConfig {
        applicationId = ownerApplicationId
        minSdk = 29
        targetSdk = 36
        // versionCode is the single source of truth (Play requires a monotonic integer;
        // it also matches the revisions.txt [NNN] entry and the release tag vNNN).
        // versionName is DERIVED so the two can never drift: 157 -> "1.57", 207 -> "2.07".
        val appVersionCode = 159
        versionCode = appVersionCode
        versionName = "%d.%02d".format(appVersionCode / 100, appVersionCode % 100)

//###############################################
//###############################################
//###############################################

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    // Release signing is driven entirely by environment variables, set by CI from GitHub
    // Secrets (the workflow base64-decodes the keystore to a file first). Nothing secret
    // lives in the repo — .gitignore already excludes *.jks/*.keystore/key.properties.
    // When the env is absent (normal local build) the release stays UNSIGNED and the build
    // still succeeds; CI is the signing authority for distributed APKs.
    val releaseKeystoreFile: String? = System.getenv("CARLINK_KEYSTORE_FILE")
    signingConfigs {
        create("release") {
            if (releaseKeystoreFile != null) {
                storeFile = file(releaseKeystoreFile)
                storePassword = System.getenv("CARLINK_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("CARLINK_KEY_ALIAS")
                keyPassword = System.getenv("CARLINK_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Apply the release signing config only when the keystore env is present (CI).
            // Otherwise leave it unset so local release builds produce an unsigned APK
            // instead of failing on a missing keystore.
            if (releaseKeystoreFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    // Distribution split (cluster icon ContentProvider authority — issue #6):
    //   NOTE: this hook only renders icons on the gminfo3.7 platform (Info 3.7, AAOS 12).
    //   The authority is orphaned/claimable on the GM VCU platform too (Bosch VCUNH1, EV/newer-ICE
    //   on AAOS 14 — same unmodified Google Templates Host, firmware-verified), but there it is
    //   BYPASSED: GM's VMSPlugin force-renders the cluster glyph from a maneuver-type enum
    //   (setManeuverType), so the app bitmap is masked regardless — the shim is futile on VCUNH1.
    //   See documents/reference/gminfo/projection/cluster_navigation.md (2026-06-06 firmware-verified).
    //   sideload → APK, installed directly on the head unit and never uploaded to Play,
    //              so the Play Console authority-uniqueness check never applies. Always
    //              claims GM's Templates Host ClusterIconContentProvider authority, so the
    //              GM hook works on-device for anyone (owner or fork).
    //   play     → AAB for the Play Store. The OWNER (first publisher to claim the GM
    //              authority) keeps the GM literal — Google accepts it, and GM AAOS, which
    //              only ever calls that authority, delivers the maneuver bitmaps to the
    //              cluster. A FORK (any other applicationId) automatically falls back to an
    //              applicationId-derived authority so its bundle passes the Play Console
    //              "authority in use by other developers" check. GM never calls that
    //              derived authority, so a fork's Play build gets no maneuver icons in the
    //              cluster — it shows text navigation only. The GM hook is permanently
    //              fork-unavailable on Play; only the first claimant can own it.
    //              NavigationStateManager.initialize() probes the GM literal at runtime and,
    //              when it isn't claimable, the cluster falls back to text-only navigation
    //              (no icons) — so the probe must stay the GM literal, NOT this per-flavor value.
    flavorDimensions += "distribution"

    productFlavors {
        create("sideload") {
            dimension = "distribution"
            manifestPlaceholders["clusterIconAuthority"] = gmClusterIconAuthority
            buildConfigField(
                "String",
                "CLUSTER_ICON_AUTHORITY",
                "\"$gmClusterIconAuthority\""
            )
        }
        create("play") {
            dimension = "distribution"
            // Owner → GM literal (icons render on the owner's Play app). Fork → its own
            // applicationId-derived authority (uploads, but renders type-based icons).
            val playClusterIconAuthority =
                if (ownerApplicationId == "zeno.carlink") gmClusterIconAuthority
                else "$ownerApplicationId.ClusterIconContentProvider"
            manifestPlaceholders["clusterIconAuthority"] = playClusterIconAuthority
            buildConfigField(
                "String",
                "CLUSTER_ICON_AUTHORITY",
                "\"$playClusterIconAuthority\""
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true  // Enable BuildConfig generation for debug checks
        aidl = true         // INaviVideoSink / INaviVideoSource for ClusterHomeDisplay AltVideo (0x2C)
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // Robolectric 4.17 emulates SDK 36+ (ApplicationSharedMemory), whose
            // FileDescriptorInterceptor reflects into java.base internals. On JDK 17+ the
            // module system blocks that unless opened, which otherwise fails every test with
            // "Failed to interact with raw FileDescriptor internals; perhaps JRE has changed?".
            // These are Robolectric's own documented --add-opens flags.
            all { test ->
                test.jvmArgs(
                    // The load-bearing one: the interceptor reflects into
                    // jdk.internal.access.SharedSecrets, closed to unnamed modules by default.
                    "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                    "--add-opens=java.base/java.io=ALL-UNNAMED",
                    "--add-opens=java.base/java.lang=ALL-UNNAMED",
                    "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
                )
            }
        }
    }

    lint {
        // Suppress DiscouragedApi warning for scheduleAtFixedRate usage.
        // Tested alternatives (coroutines, scheduleWithFixedDelay) caused issues
        // with microphone timing - Timer.scheduleAtFixedRate works reliably.
        // See documents/revisions.txt [19], [21] for history.
        disable += "DiscouragedApi"
        disable += "Instantiatable"  // CarAppActivity from app-automotive AAR — false positive
        disable += "InvalidUsesTagAttribute"  // "navigation" is valid for Car App Library nav apps
        // Media3 UnstableApi false positive. Tool disagreement, verified on AGP 9.1 / media3
        // 1.10.1: the Kotlin compiler treats UnstableApi as a non-opt-in marker (a @file:OptIn
        // would warn "has no effect", so it is intentionally absent), but lint's
        // experimental-annotation check still demands the opt-in and errors on every Media3
        // use in MediaSessionManager.kt / CarlinkMediaBrowserService.kt. The code is correct
        // and compiles clean; suppress the lint check.
        disable += "UnsafeOptInUsageError"
        // Baseline captures the remaining accepted warnings (UnusedResources, OldTargetApi,
        // etc.) so lintSideloadDebug passes and surfaces only NEW issues going forward.
        baseline = file("lint-baseline.xml")
    }
}

// Drop playDebug — Play Console rejects debuggable bundles, and there is no
// reason to build/install/run a debug variant tied to the play flavor. Result:
// Build Variants panel and the Generate Signed Bundle / APK wizard show only
// sideloadDebug, sideloadRelease, playRelease.
androidComponents {
    beforeVariants(selector().withName("playDebug")) { variant ->
        variant.enable = false
    }
}

// Kotlin compiler: report deprecations and unchecked casts as warnings
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.addAll("-opt-in=kotlin.RequiresOptIn")
        allWarningsAsErrors.set(false) // report but don't fail — tighten later
    }
}

ktlint {
    android.set(true)
    outputToConsole.set(true)
    ignoreFailures.set(true) // report only on first run — fix incrementally
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    // The repo ships neither detekt.yml nor detekt-baseline.xml, so referencing them
    // unconditionally failed the task ("Provided path ... does not exist"). Use each only
    // when present; otherwise run on detekt's default ruleset (buildUponDefaultConfig) with
    // no baseline, so all findings are reported rather than suppressed.
    file("$rootDir/detekt.yml").takeIf { it.exists() }?.let { config.setFrom(it) }
    file("$rootDir/detekt-baseline.xml").takeIf { it.exists() }?.let { baseline = it }
    ignoreFailures = true // report only on first run
}

dependencies {
    // Kotlin
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // AndroidX Core
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.activity:activity-compose:1.13.0")

    // DataStore for preferences persistence
    implementation("androidx.datastore:datastore-preferences:1.2.1")

    // DocumentFile for SAF file operations (capture recording)
    implementation("androidx.documentfile:documentfile:1.1.0")

    // Compose BOM
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // MediaSession for AAOS integration — Media3 1.10.1.
    // media3-session supersedes legacy androidx.media:media (MediaSessionCompat, deprecated
    // in androidx.media 1.8.0-alpha01). GM AAOS observers use platform android.media.session.*
    // APIs which Media3 auto-registers under the hood for backwards compatibility, so the
    // GMCarMediaService → ClusterService → cluster pipeline keeps working.
    // media3-common provides Player / SimpleBasePlayer / MediaItem / MediaMetadata.
    // media3-exoplayer is intentionally NOT included: this app does not decode/play audio
    // locally — the connected phone plays over USB; we only mirror metadata + forward commands.
    val media3Version = "1.11.1"
    implementation("androidx.media3:media3-session:$media3Version")
    implementation("androidx.media3:media3-common:$media3Version")

    // Car App Library for AAOS cluster navigation (Templates Host)
    implementation("androidx.car.app:app:1.7.0")
    implementation("androidx.car.app:app-automotive:1.7.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

