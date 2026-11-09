# Runs inside the Windows VM (called by windows/build.sh): jpackage app images for x64 and ARM64,
# zipped into dist\, plus one Setup.exe for both (Inno Setup, installed once into the build folder).
# With -Install, runs that Setup silently (as a user would, minus the wizard) and launches the app.
param([Parameter(Mandatory)][string]$Version, [Parameter(Mandatory)][string]$Build, [switch]$Install)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$stage = Split-Path -Parent $MyInvocation.MyCommand.Path
$work = Join-Path $env:LOCALAPPDATA 'PersianDictationBuild'
New-Item -ItemType Directory -Force $work | Out-Null

# Local copy of the input: jpackage on the network share is slow.
$inputDir = Join-Path $work 'input'
Remove-Item -Recurse -Force $inputDir -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $inputDir | Out-Null
Copy-Item "$stage\input\*.jar" $inputDir

foreach ($arch in @('x64', 'aarch64')) {
    $jdk = Join-Path $work "jdk-$arch"
    if (-not (Test-Path "$jdk\bin\jpackage.exe")) {
        $tmp = Join-Path $work "unzip-$arch"
        Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
        New-Item -ItemType Directory -Force $tmp | Out-Null
        tar -xf "$stage\jdk-$arch.zip" -C $tmp
        Move-Item (Get-ChildItem $tmp -Directory | Select-Object -First 1).FullName $jdk
        Remove-Item -Recurse -Force $tmp
    }
    $label = if ($arch -eq 'x64') { 'x64' } else { 'arm64' }
    $out = Join-Path $work "out-$label"
    Remove-Item -Recurse -Force $out -ErrorAction SilentlyContinue
    & "$jdk\bin\jpackage.exe" --type app-image --name 'Persian Dictation' `
        --input $inputDir --main-jar PersianDictation.jar --main-class local.voice2text.windows.MainKt `
        --app-version $Version --icon "$stage\PersianDictation.ico" `
        --vendor 'Ahmad Boorghany' --description 'Persian Dictation' `
        --add-modules 'java.base,java.desktop,java.logging,java.naming,jdk.crypto.ec,jdk.unsupported' `
        --java-options '-Xmx256m' --java-options '-XX:+UseSerialGC' `
        --dest $out
    if ($LASTEXITCODE -ne 0) { throw "jpackage ($arch) failed" }
    # Files unpacked from the Mac share are read-only, and jpackage keeps that: installed read-only, they
    # can't be replaced by the next update. Clear it.
    Get-ChildItem $out -Recurse -File | Where-Object IsReadOnly | ForEach-Object { $_.IsReadOnly = $false }
    $zip = Join-Path $stage "dist\PersianDictation-$Version-build$Build-windows-$label.zip"
    Remove-Item -Force $zip -ErrorAction SilentlyContinue
    tar -a -c -f $zip -C $out 'Persian Dictation'
    if ($LASTEXITCODE -ne 0) { throw "zip ($arch) failed" }
    Write-Output "built $zip"
}

# Installer: Inno Setup, installed once for this user into the build folder.
$iscc = Join-Path $work 'InnoSetup\ISCC.exe'
if (-not (Test-Path $iscc)) {
    Start-Process "$stage\innosetup.exe" -Wait -ArgumentList @('/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART', '/CURRENTUSER', "/DIR=$work\InnoSetup", '/NOICONS')
}
$setupName = "PersianDictation-$Version-build$Build-Setup"
& $iscc /Qp "/DVersion=$Version" "/DBuild=$Build" "/DX64Dir=$work\out-x64\Persian Dictation" `
    "/DArm64Dir=$work\out-arm64\Persian Dictation" "/DIconFile=$stage\PersianDictation.ico" "/DOutDir=$stage\dist" `
    "$stage\installer.iss"
if ($LASTEXITCODE -ne 0) { throw 'Inno Setup failed' }
Write-Output "built $stage\dist\$setupName.exe"

if ($Install) {
    # Silent install: keeps "Start with Windows" off (a test install shouldn't change that).
    $p = Start-Process "$stage\dist\$setupName.exe" -Wait -PassThru -ArgumentList @('/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART', '/MERGETASKS=!startup')
    if ($p.ExitCode -ne 0) { throw "Setup failed ($($p.ExitCode))" }
    $dest = Join-Path $env:LOCALAPPDATA 'Programs\Persian Dictation'
    Start-Process "$dest\Persian Dictation.exe"
    Write-Output "installed with Setup and launched $dest"
}
