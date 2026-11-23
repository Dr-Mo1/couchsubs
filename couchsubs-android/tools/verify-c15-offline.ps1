# Offline JVM verification using the installed Kotlin compiler and Gradle's JUnit jars.
# Does not build Android UI/ML Kit/JNI. SecondScreen's QR-only dependency is unavailable;
# the harness substitutes ONLY its protocol constants (QR/address behavior is not tested).
$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
$repo = Split-Path $project -Parent
$out = Join-Path $project '.c15-jvm'
New-Item -ItemType Directory -Force $out | Out-Null
$cache = 'C:/Users/ASUS/.gradle/caches/modules-2/files-2.1'
function Jar($path) {
    $found = Get-ChildItem -LiteralPath "$cache/$path" -Recurse -Filter '*.jar' | Select-Object -First 1
    if (!$found) { throw "Missing cached jar: $path" }
    $found.FullName
}
$stdlib = Jar 'org.jetbrains.kotlin/kotlin-stdlib/2.0.21'
$compiler = @((Jar 'org.jetbrains.kotlin/kotlin-compiler-embeddable/2.0.21'), $stdlib,
    (Jar 'org.jetbrains.kotlin/kotlin-script-runtime/2.0.21'), (Jar 'org.jetbrains.kotlin/kotlin-reflect/1.6.10'),
    (Jar 'org.jetbrains.intellij.deps/trove4j'), (Jar 'org.jetbrains/annotations/13.0'),
    (Jar 'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.6.4')) -join ';'
$gradleLib = 'C:/Users/ASUS/.gradle/wrapper/dists/gradle-8.14.3-bin/cv11ve7ro1n3o1j4so8xd9n66/gradle-8.14.3/lib'
$junit = "$gradleLib/junit-4.13.2.jar"
$hamcrest = "$gradleLib/hamcrest-core-1.3.jar"
$android = 'C:/Users/ASUS/scoop/apps/android-clt/current/platforms/android-36/android.jar'
$compileCp = @($stdlib, $junit, $hamcrest, $android) -join ';'
$stub = Join-Path $out 'SecondScreen.kt'
[IO.File]::WriteAllText($stub, 'package com.couchsubs.mobile.core; object SecondScreen { const val PATH = "/view"; const val VIEWER = "second" }')
$main = Join-Path $project 'app/src/main/java/com/couchsubs/mobile'
$tests = Join-Path $project 'app/src/test/java/com/couchsubs/mobile'
$sources = @(Get-ChildItem "$main/core" -Filter '*.kt' | Where-Object Name -ne 'SecondScreen.kt' | ForEach-Object FullName)
$sources += @(Get-ChildItem "$main/translation" -Filter '*.kt' | Where-Object Name -notin @('MlKitTranslator.kt','ModelDownloadRecovery.kt') | ForEach-Object FullName)
$newTests = @('TargamgiLanguageTest','ArabicPromptDriftTest','ArabicGlossaryTest','ArabicDialogueTest','ArabicEngineOrderTest','TargamgiRequestTest',
    'ArabicSpeakerNamesTest','ControllerPromptTest','InstructionDisplayTest','TargamgiPrivacyRenderingTest')
$existing = @('SpeakerPrefixTest','SpeakerColorsTest','ObjectiveWithDialogueTest','ReadingHoldTest','TurkishTextTest','MissionLineTest','StickyRowsTest')
$testFiles = @(Get-ChildItem $tests -Recurse -Filter '*.kt' | Where-Object { $_.BaseName -in ($newTests + $existing) })
$sources += @($testFiles | ForEach-Object FullName)
$enumFile = Join-Path $out 'EngineChoice.kt'
$settingsSource = Get-Content -Raw -Encoding UTF8 "$main/settings/AppSettings.kt"
$enum = [regex]::Match($settingsSource, 'enum class EngineChoice \{[^}]+\}').Value
if (!$enum) { throw 'EngineChoice declaration not found' }
[IO.File]::WriteAllText($enumFile, "package com.couchsubs.mobile.settings; $enum")
$runner = Join-Path $out 'OfflineJUnit.kt'
[IO.File]::WriteAllText($runner, @'
import org.junit.runner.*
import org.junit.runner.manipulation.Filter
import org.junit.internal.TextListener
fun main(args: Array<String>) {
    val unavailable = setOf("colorBookMatchesTheSharedVector", "speakerListenerGenderAndContinuation",
        "packFirstThenAiDeepLAndRawArabicMachine", "everyProviderReceivesTheSelectedTargetAndGlossary",
        "keyValidationTestsTheSelectedLanguage", "subtitleAndScreenPathsFilterTheGlossaryAndKeepTenArabicContextLines",
        "sharedCacheSeparatesLanguageAndSpeakerAndTurkishKeepsThreeContextLines")
    val request = Request.classes(*args.map { Class.forName(it) }.toTypedArray()).filterWith(object : Filter() {
        override fun describe() = "Exclude seven tests requiring the unavailable real org.json library"
        override fun shouldRun(d: Description): Boolean = if (d.isTest) d.methodName !in unavailable else d.children.any { shouldRun(it) }
    })
    println("Excluded (real org.json unavailable): " + unavailable.sorted().joinToString())
    val junit = JUnitCore(); junit.addListener(TextListener(System.out))
    val result = junit.run(request)
    kotlin.system.exitProcess(if (result.wasSuccessful()) 0 else 1)
}
'@)
$sources += @($stub, $enumFile, $runner)
$argsFile = Join-Path $out 'compile.args'
$compileArgs = @('-no-stdlib', '-no-reflect', '-jvm-target', '17', '-Xjvm-default=all', '-nowarn', '-classpath', $compileCp, '-d', "$out/classes") + $sources
[IO.File]::WriteAllLines($argsFile, @($compileArgs | ForEach-Object { '"' + $_.Replace('\','/') + '"' }), [Text.UTF8Encoding]::new($false))
$ErrorActionPreference = 'Continue'
& java -Xmx1g -cp $compiler org.jetbrains.kotlin.cli.jvm.K2JVMCompiler "@$argsFile" 2>&1 | Tee-Object "$out/compile.log"
if ($LASTEXITCODE) { exit $LASTEXITCODE }
# android.jar supplies signatures only: its org.json stubs cannot execute payload tests.
# The runner filters those seven methods; the remaining checks use local files and real JVM code.
$run = @($testFiles | ForEach-Object {
    $package = (Select-String -LiteralPath $_.FullName -Pattern '^package (.+)$').Matches[0].Groups[1].Value
    "$package.$($_.BaseName)"
})
Push-Location $project
& java "-Dtargamgi.repoRoot=$repo" '-Dtargamgi.dataDir=D:/Games/couchsubs-ar/build/android-data' -cp "$out/classes;$stdlib;$junit;$hamcrest;$android" OfflineJUnitKt @run 2>&1 | Tee-Object "$out/tests.log"
$result = $LASTEXITCODE
Pop-Location
exit $result
