#requires -Version 5.1
<#
.SYNOPSIS
Installs a portable Windows x64 Android toolchain in the current working folder.
.EXAMPLE
Set-Location C:\Projects\Cursorly
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools.ps1
Creates .tools in the current working folder, regardless of where tools.ps1 is stored.
Downloads and extracts missing tools; rerunning keeps installed tools.
Internet access is needed for missing downloads. No Node.js or admin rights needed.
Android's installer may ask you to accept SDK license terms.
Only these top-level folders are created inside .tools:
  gradle-9.9.1, gradle-cache, jdk, android-sdk
Download archives and extraction staging are kept inside gradle-cache/bootstrap.
Versions target Cursorly's Android build (compileSdk 37, Gradle 9.9.1, Oracle JDK 25).
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT -or ![Environment]::Is64BitOperatingSystem) {
    throw 'This script installs Windows x64 tools. Run it on 64-bit Windows with x64 application support.'
}
[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
$installLocation = Get-Location
if ($installLocation.Provider.Name -ne 'FileSystem') {
    throw 'Run tools.ps1 from a filesystem folder.'
}
$toolsRoot = Join-Path $installLocation.ProviderPath '.tools'
$gradleHome = Join-Path $toolsRoot 'gradle-9.9.1'
$env:JAVA_HOME = Join-Path $toolsRoot 'jdk'
$env:ANDROID_HOME = Join-Path $toolsRoot 'android-sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:ANDROID_USER_HOME = Join-Path $env:ANDROID_HOME '.android'
$env:GRADLE_USER_HOME = Join-Path $toolsRoot 'gradle-cache'
$downloads = Join-Path $env:GRADLE_USER_HOME 'bootstrap'
Remove-Item Env:ANDROID_SDK_HOME -ErrorAction SilentlyContinue
Remove-Item Env:ANDROID_PREFS_ROOT -ErrorAction SilentlyContinue
foreach ($directory in @($toolsRoot, $downloads, $env:ANDROID_HOME, $env:ANDROID_USER_HOME, $env:GRADLE_USER_HOME)) {
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
}

function Get-VerifiedArchive([string]$Url, [string]$Name, [string]$Checksum, [string]$Algorithm = 'SHA256') {
    if (($Algorithm -eq 'SHA256' -and $Checksum -notmatch '^[a-fA-F0-9]{64}$') -or
        ($Algorithm -eq 'SHA1' -and $Checksum -notmatch '^[a-fA-F0-9]{40}$') -or
        $Algorithm -notin @('SHA256', 'SHA1')) {
        throw "Invalid checksum for $Name."
    }
    $archivePath = Join-Path $downloads $Name
    if (!(Test-Path -LiteralPath $archivePath -PathType Leaf) -or
        (Get-FileHash -LiteralPath $archivePath -Algorithm $Algorithm).Hash -ne $Checksum) {
        Write-Host "Downloading $Name ..."
        $partial = "$archivePath.partial"
        if (Get-Command curl.exe -ErrorAction SilentlyContinue) {
            & curl.exe --fail --location --proto '=https' --proto-redir '=https' --retry 3 --connect-timeout 30 --output $partial $Url
            if ($LASTEXITCODE -ne 0) { throw "Download failed: $Name. Rerun tools.ps1 to retry." }
        } else {
            $previousProgress = $ProgressPreference
            try {
                $ProgressPreference = 'SilentlyContinue'
                Invoke-WebRequest -UseBasicParsing -Uri $Url -OutFile $partial
            } finally { $ProgressPreference = $previousProgress }
        }
        if ((Get-FileHash -LiteralPath $partial -Algorithm $Algorithm).Hash -ne $Checksum) {
            throw "Checksum mismatch: $Name. Archive was not extracted. Rerun to download again."
        }
        Move-Item -LiteralPath $partial -Destination $archivePath -Force
    }
    return $archivePath
}

function Install-ZipFolder([string]$Archive, [string]$Folder, [string]$Destination, [string]$RequiredFile) {
    if (Test-Path -LiteralPath $Destination) {
        throw "Incomplete installation at $Destination. Move that folder aside and rerun tools.ps1."
    }
    $stage = Join-Path $downloads ([Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $stage | Out-Null
    Write-Host "Extracting $([IO.Path]::GetFileName($Archive)) ..."
    if (Get-Command tar.exe -ErrorAction SilentlyContinue) {
        & tar.exe -xf $Archive -C $stage
        if ($LASTEXITCODE -ne 0) { throw "Extraction failed: $Archive" }
    } else {
        Expand-Archive -LiteralPath $Archive -DestinationPath $stage
    }
    $source = Join-Path $stage $Folder
    if (!(Test-Path -LiteralPath (Join-Path $source $RequiredFile) -PathType Leaf)) {
        throw "Archive does not contain $Folder/$RequiredFile. Extracted files: $stage"
    }
    New-Item -ItemType Directory -Path (Split-Path $Destination -Parent) -Force | Out-Null
    Move-Item -LiteralPath $source -Destination $Destination
    # Only remove an empty staging directory; leave unexpected files for inspection.
    if (!(Get-ChildItem -LiteralPath $stage -Force)) { Remove-Item -LiteralPath $stage }
}

Write-Host "Installing tools in $toolsRoot"
if (!(Test-Path -LiteralPath "$env:JAVA_HOME/bin/java.exe") -or
    !(Test-Path -LiteralPath "$env:JAVA_HOME/bin/javac.exe")) {
    # Same Oracle JDK version as this project's existing .tools/jdk/release.
    # Official source: https://www.oracle.com/java/technologies/javase/jdk25-archive-downloads.html
    $jdkUrl = 'https://download.oracle.com/java/25/archive/jdk-25.0.3_windows-x64_bin.zip'
    $checksumText = (Invoke-WebRequest -UseBasicParsing -Uri "$jdkUrl.sha256").Content
    $jdkChecksum = ([string]$checksumText).Trim().Split()[0]
    $zip = Get-VerifiedArchive $jdkUrl 'jdk-25.0.3_windows-x64_bin.zip' $jdkChecksum
    Install-ZipFolder $zip 'jdk-25.0.3' $env:JAVA_HOME 'bin/javac.exe'
}
if (!(Test-Path -LiteralPath "$env:JAVA_HOME/release") -or
    !(Select-String -LiteralPath "$env:JAVA_HOME/release" -Pattern '^JAVA_VERSION="25[.\"]' -Quiet)) {
    throw "Expected JDK 25 at $env:JAVA_HOME. Move the incompatible folder aside and rerun."
}

if (!(Test-Path -LiteralPath "$gradleHome/bin/gradle.bat")) {
    $zip = Get-VerifiedArchive 'https://downloads.gradle.org/distributions/gradle-9.9.1-bin.zip' 'gradle-9.9.1-bin.zip' 'd8a18a7a841dd5df825d35a685fad6f2d7f12ac0c280e57b67c27b4a520ba228'
    Install-ZipFolder $zip 'gradle-9.9.1' $gradleHome 'bin/gradle.bat'
}

# Checksum-verified Android platform-tools archive from Google.
if (!(Test-Path -LiteralPath "$env:ANDROID_HOME/platform-tools/adb.exe" -PathType Leaf)) {
    $zip = Get-VerifiedArchive 'https://dl.google.com/android/repository/platform-tools_r37.0.1-win.zip' 'platform-tools_r37.0.1-win.zip' 'e03e78b1d80b396f1c3358e31251cb31740e1110' 'SHA1'
    Install-ZipFolder $zip 'platform-tools' (Join-Path $env:ANDROID_HOME 'platform-tools') 'adb.exe'
}

$android = Join-Path $env:ANDROID_HOME 'cmdline-tools/latest/bin/android.exe'
if (!(Test-Path -LiteralPath $android)) {
    # Resolve the current Windows archive and its published checksum from Google.
    [xml]$feed = (Invoke-WebRequest -UseBasicParsing -Uri 'https://dl.google.com/android/repository/repository2-1.xml').Content
    $archive = $feed.SelectSingleNode("/*[local-name()='sdk-repository']/*[local-name()='remotePackage' and @path='cmdline-tools;latest']/*[local-name()='archives']/*[local-name()='archive'][*[local-name()='host-os' and normalize-space(.)='windows']]/*[local-name()='complete']")
    if (!$archive) { throw 'Google SDK feed has no Windows command-line tools archive.' }
    $nameNode = $archive.SelectSingleNode("*[local-name()='url']")
    $checksumNode = $archive.SelectSingleNode("*[local-name()='checksum']")
    if (!$nameNode -or !$checksumNode) { throw 'Google SDK feed is missing the archive URL or checksum.' }
    $name = $nameNode.InnerText.Trim()
    if ($name -notmatch '^commandlinetools-win-[a-zA-Z0-9_.-]+\.zip$') { throw "Unexpected SDK archive: $name" }
    $algorithm = $checksumNode.GetAttribute('type').Replace('-', '').ToUpperInvariant()
    if (!$algorithm) { $algorithm = 'SHA1' }
    $zip = Get-VerifiedArchive "https://dl.google.com/android/repository/$name" $name $checksumNode.InnerText.Trim() $algorithm
    Install-ZipFolder $zip 'cmdline-tools' (Join-Path $env:ANDROID_HOME 'cmdline-tools/latest') 'bin/android.exe'
}

$requirements = @(
    @{ Package = 'platform-tools'; Files = @('platform-tools/adb.exe') },
    @{ Package = 'platforms/android-37.0'; Files = @('platforms/android-37.0/android.jar', 'platforms/android-37.0/source.properties') },
    @{ Package = 'build-tools/37.0.0'; Files = @('build-tools/37.0.0/aapt2.exe', 'build-tools/37.0.0/zipalign.exe', 'build-tools/37.0.0/apksigner.bat', 'build-tools/37.0.0/lib/apksigner.jar', 'build-tools/37.0.0/source.properties') }
)
foreach ($requirement in $requirements) {
    $missing = @($requirement.Files | Where-Object { !(Test-Path -LiteralPath (Join-Path $env:ANDROID_HOME $_) -PathType Leaf) })
    if ($missing.Count) {
        Write-Host "Installing Android SDK package: $($requirement.Package)"
        & $android '--no-metrics' "--sdk=$env:ANDROID_HOME" sdk install $requirement.Package
        if ($LASTEXITCODE -ne 0) { throw "Android SDK installation failed: $($requirement.Package). Rerun to retry." }
        foreach ($file in $requirement.Files) {
            if (!(Test-Path -LiteralPath (Join-Path $env:ANDROID_HOME $file) -PathType Leaf)) {
                throw "Android SDK installer did not create $file. Check its output above and rerun."
            }
        }
    } else { Write-Host "Already installed: $($requirement.Package)" }
}

$env:PATH = "$env:JAVA_HOME/bin;$gradleHome/bin;$env:ANDROID_HOME/platform-tools;$env:ANDROID_HOME/cmdline-tools/latest/bin;$env:PATH"
Write-Host "`nTools ready:"
Write-Host "  $gradleHome"
Write-Host "  $env:GRADLE_USER_HOME"
Write-Host "  $env:JAVA_HOME"
Write-Host "  $env:ANDROID_HOME"
Write-Host 'Environment variables are set for this PowerShell process only.'
Write-Host 'ZIP downloads are cached in .tools/gradle-cache/bootstrap for reuse.'
