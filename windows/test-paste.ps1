# End-to-end test in the Windows VM (run via prlctl exec --current-user, after build.sh --install).
# -Wav: plays a recording instead of the microphone (test-input.wav hook, only in Development Mode) and
#       dictates with `--toggle` (needs internet in the VM).
# -Mic: dictates from the real microphone for -Seconds (play speech to it meanwhile).
# -Finals: a UTF-8 file with one "final" per line, each handed to the app with `--paste-test` (no network).
# Target: a plain WinForms text box (target.ps1; Windows 11 Notepad restores unsaved tabs, which mixes runs).
# Prints what landed in it and whether the user's clipboard came back.
param([string]$Wav, [string]$Finals, [switch]$Mic, [int]$Seconds = 20)
$ErrorActionPreference = 'Stop'
$exe = Join-Path $env:LOCALAPPDATA 'Programs\Persian Dictation\Persian Dictation.exe'
$data = Join-Path $env:LOCALAPPDATA 'Persian Dictation'
[Console]::OutputEncoding = [Text.Encoding]::UTF8

Set-Clipboard -Value 'USER-CLIP-123'
$out = Join-Path $env:TEMP 'pd-target.txt'
Remove-Item $out -ErrorAction SilentlyContinue
$wait = if ($Wav -or $Mic) { $Seconds + 14 } else { 12 }
$target = Start-Process powershell -PassThru -WindowStyle Normal -ArgumentList @('-NoProfile', '-STA', '-File', (Join-Path $PSScriptRoot 'target.ps1'), '-Out', $out, '-Seconds', $wait)
try {
    Start-Sleep 3
    if ($Mic) {
        & $exe --toggle
        Start-Sleep $Seconds
        & $exe --toggle
    } elseif ($Wav) {
        # Read-only when copied from the Mac share; clear it so the cleanup below can delete it.
        Copy-Item $Wav (Join-Path $data 'test-input.wav')
        Set-ItemProperty (Join-Path $data 'test-input.wav') -Name IsReadOnly -Value $false
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
    Remove-Item (Join-Path $data 'test-input.wav') -Force -ErrorAction SilentlyContinue
    if (Test-Path (Join-Path $data 'test-input.wav')) { Write-Output 'WARNING: test-input.wav was not removed' }
    if (-not $target.HasExited) { Stop-Process -Id $target.Id -Force -ErrorAction SilentlyContinue }
}
