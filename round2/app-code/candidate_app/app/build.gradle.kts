import java.util.zip.ZipFile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.scpc.r2.safeshare"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.scpc.r2.safeshare"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0-safeshare"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets {
        getByName("main").assets.srcDir("../../tools/synthetic-data/generated/app-input")
        getByName("test").resources.srcDirs(
            "../../tools/synthetic-data/generated/app-input",
            "../../tools/synthetic-data/generated/test-oracle",
            "../../tools/synthetic-data/generated/generalization",
        )
    }

    testOptions {
        unitTests.all {
            it.systemProperty(
                "safeshare.synthetic.report.dir",
                layout.buildDirectory.dir("reports/synthetic-e2e").get().asFile.absolutePath,
            )
        }
    }
}

dependencies {
    implementation(files("libs/scpc-probe-starter-3.0.0-draft.aar"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

tasks.register("verifyReleaseCorpusBoundary") {
    group = "verification"
    description = "Checks that release APK contains 14 input-only corpus assets and no test oracle."
    dependsOn("assembleRelease")
    doLast {
        val apk = layout.buildDirectory
            .file("outputs/apk/release/app-release-unsigned.apk")
            .get()
            .asFile
        check(apk.isFile) { "release APK not found: $apk" }
        ZipFile(apk).use { zip ->
            val entries = zip.entries().asSequence().map { it.name }.toList()
            val corpus = entries
                .filter { Regex("^assets/SS-KO-[0-9]{4}\\.input\\.json$").matches(it) }
                .sorted()
            val expected = (1..14).map { index ->
                "assets/SS-KO-${index.toString().padStart(4, '0')}.input.json"
            }
            check(corpus == expected) {
                "release corpus asset mismatch: expected=$expected actual=$corpus"
            }
            val expectedPdfAssets = listOf(
                "assets/synthetic/alpha-q3-settlement-v1.pdf",
                "assets/synthetic/alpha-q3-settlement-v2.pdf",
                "assets/synthetic/alpha-q3-settlement-v3.pdf",
            )
            expectedPdfAssets.forEach { name ->
                val entry = zip.getEntry(name)
                check(entry != null && entry.size > 0L) {
                    "synthetic PDF asset is missing or empty: $name"
                }
                val header = zip.getInputStream(entry).use { input ->
                    input.readNBytes(5).toString(Charsets.US_ASCII)
                }
                check(header == "%PDF-") { "synthetic PDF has an invalid header: $name" }
            }
            val forbidden = entries.filter { entry ->
                val lower = entry.lowercase()
                lower.startsWith("assets/") &&
                    lower.endsWith(".json") &&
                    listOf("oracle", "expected", "score", "answer", "gold").any(lower::contains)
            }
            check(forbidden.isEmpty()) {
                "test-only result knowledge leaked into release APK assets: $forbidden"
            }
        }
    }
}
