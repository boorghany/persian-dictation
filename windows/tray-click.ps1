# Test helper for the Windows VM: sends Persian Dictation's hidden window the message Explorer sends
# when its tray icon is clicked (NOTIFYICON_VERSION_4), so the menu opens at (X, Y) without having
# to hit the icon in the overflow panel. Also lets the app take the foreground, as Explorer would.
param([int]$X = 2200, [int]$Y = 1600, [int]$TrayEvent = 0x0400)
Add-Type @"
using System; using System.Runtime.InteropServices;
public static class PdTray {
  [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern IntPtr FindWindow(string cls, string name);
  [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr h, int msg, IntPtr w, IntPtr l);
  [DllImport("user32.dll")] public static extern bool AllowSetForegroundWindow(int pid);
}
"@
$h = [PdTray]::FindWindow('PersianDictationHiddenWindow', [NullString]::Value)
if ($h -eq [IntPtr]::Zero) { throw 'Persian Dictation is not running' }
[PdTray]::AllowSetForegroundWindow(-1) | Out-Null
$w = [IntPtr](($Y -shl 16) -bor ($X -band 0xFFFF))
$l = [IntPtr]((1 -shl 16) -bor $TrayEvent)
[PdTray]::PostMessage($h, 0x800A, $w, $l) | Out-Null
