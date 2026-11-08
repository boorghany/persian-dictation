# Runs inside the Windows VM (called by windows/build.sh): jpackage app images for x64 and ARM64,
# zipped into dist\. With -Install, installs the ARM64 one for this user and launches it.
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
    $zip = Join-Path $stage "dist\PersianDictation-$Version-build$Build-windows-$label.zip"
    Remove-Item -Force $zip -ErrorAction SilentlyContinue
    tar -a -c -f $zip -C $out 'Persian Dictation'
    if ($LASTEXITCODE -ne 0) { throw "zip ($arch) failed" }
    Write-Output "built $zip"
}

if ($Install) {
    $running = Get-Process -Name 'Persian Dictation' -ErrorAction SilentlyContinue
    if ($running) { $running | Stop-Process -Force; Start-Sleep -Milliseconds 800 }
    $dest = Join-Path $env:LOCALAPPDATA 'Programs\Persian Dictation'
    Remove-Item -Recurse -Force $dest -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force (Split-Path $dest) | Out-Null
    Copy-Item -Recurse (Join-Path $work 'out-arm64\Persian Dictation') $dest
    $shell = New-Object -ComObject WScript.Shell
    $lnk = $shell.CreateShortcut("$env:APPDATA\Microsoft\Windows\Start Menu\Programs\Persian Dictation.lnk")
    $lnk.TargetPath = "$dest\Persian Dictation.exe"
    $lnk.WorkingDirectory = $dest
    $lnk.Save()
    Start-Process "$dest\Persian Dictation.exe"
    Write-Output "installed and launched $dest"
}
