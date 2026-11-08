# End-to-end test in the Windows VM (run via prlctl exec --current-user, after build.sh --install).
# -Wav: plays a recording instead of the microphone (test-input.wav hook) and dictates with `--toggle`
#       (needs internet in the VM).
# -Finals: a UTF-8 file with one "final" per line, each handed to the app with `--paste-test` (no network).
# Target: a plain WinForms text box (target.ps1; Windows 11 Notepad restores unsaved tabs, which mixes runs).
# Prints what landed in it and whether the user's clipboard came back.
param([string]$Wav, [string]$Finals, [int]$Seconds = 20)
$ErrorActionPreference = 'Stop'
$exe = Join-Path $env:LOCALAPPDATA 'Programs\Persian Dictation\Persian Dictation.exe'
$data = Join-Path $env:LOCALAPPDATA 'Persian Dictation'
[Console]::OutputEncoding = [Text.Encoding]::UTF8

Set-Clipboard -Value 'USER-CLIP-123'
$out = Join-Path $env:TEMP 'pd-target.txt'
Remove-Item $out -ErrorAction SilentlyContinue
$wait = if ($Wav) { $Seconds + 14 } else { 12 }
$target = Start-Process powershell -PassThru -WindowStyle Normal -ArgumentList @('-NoProfile', '-STA', '-File', (Join-Path $PSScriptRoot 'target.ps1'), '-Out', $out, '-Seconds', $wait)
try {
    Start-Sleep 3
    if ($Wav) {
        Copy-Item $Wav (Join-Path $data 'test-input.wav')
        & $exe --toggle
        Start-Sleep $Seconds
        & $exe --toggle
    } else {
        $i = 0
        foreach ($line in Get-Content $Finals -Encoding UTF8) {
            $tmp = Join-Path $env:TEMP "pd-final-$i.txt"; $i++
            [IO.File]::WriteAllText($tmp, $line, (New-Object Text.UTF8Encoding $false))
            & $exe --paste-test "@$tmp"
            Start-Sleep -Milliseconds 700
        }
    }
    $target.WaitForExit(($wait + 10) * 1000) | Out-Null
    $text = Get-Content $out -Raw -Encoding UTF8
    Write-Output "TARGET: [$($text -replace "`r", '\r' -replace "`n", '\n')]"
    Write-Output "CLIPBOARD: [$(Get-Clipboard -Raw)]"
} finally {
    Remove-Item (Join-Path $data 'test-input.wav') -ErrorAction SilentlyContinue
    if (-not $target.HasExited) { Stop-Process -Id $target.Id -Force -ErrorAction SilentlyContinue }
}
