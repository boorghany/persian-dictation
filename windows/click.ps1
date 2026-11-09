# Test helper for the Windows VM: moves the mouse to (X, Y) in physical screen pixels (the same
# pixels as `prlctl capture`) and clicks. -Right for a right click, -Move to only move.
param([int]$X, [int]$Y, [switch]$Right, [switch]$Move)
Add-Type @"
using System; using System.Runtime.InteropServices;
public static class PdMouse {
  [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
  [DllImport("user32.dll")] public static extern void mouse_event(int flags, int dx, int dy, int data, IntPtr extra);
}
"@
[PdMouse]::SetProcessDPIAware() | Out-Null
[PdMouse]::SetCursorPos($X, $Y) | Out-Null
Start-Sleep -Milliseconds 150
if ($Move) { return }
$down, $up = if ($Right) { 8, 16 } else { 2, 4 }
[PdMouse]::mouse_event($down, 0, 0, 0, [IntPtr]::Zero)
Start-Sleep -Milliseconds 60
[PdMouse]::mouse_event($up, 0, 0, 0, [IntPtr]::Zero)
