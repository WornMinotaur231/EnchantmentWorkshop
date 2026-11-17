param(
    [string]$JdkHome = $env:JAVA_HOME,
    [switch]$Offline
)

$ErrorActionPreference = 'Stop'
$previousJavaHome = $env:JAVA_HOME
Push-Location $PSScriptRoot
try {
    if ($JdkHome) {
        if (-not (Test-Path -LiteralPath (Join-Path $JdkHome 'bin\javac.exe'))) {
            throw 'JdkHome must point to a JDK 25 directory containing bin\javac.exe.'
        }
        $env:JAVA_HOME = $JdkHome
    }
    $gradleArgs = @('build', '--console=plain', '--no-daemon')
    if ($Offline) {
        foreach ($required in @('build\local-verification\server\libraries', 'build\tools\junit-platform-console-standalone-1.12.2.jar', 'build\tools\annotations-26.0.2.jar')) {
            if (-not (Test-Path -LiteralPath $required)) { throw "Missing offline dependency: $required" }
        }
        $gradleArgs += @('-PlocalLibraries=build/local-verification/server/libraries', '--offline')
    }
    & .\gradlew.bat @gradleArgs
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
    $artifact = Join-Path $PSScriptRoot 'build\libs\EnchantmentWorkshop-1.0.0.jar'
    $destination = Join-Path (Split-Path $PSScriptRoot -Parent) 'EnchantmentWorkshop-1.0.0.jar'
    Copy-Item -LiteralPath $artifact -Destination $destination -Force
    Write-Output "Built: $destination"
    Get-FileHash -LiteralPath $destination -Algorithm SHA256
} finally {
    $env:JAVA_HOME = $previousJavaHome
    Pop-Location
}
