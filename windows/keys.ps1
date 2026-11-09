# Test helper for the Windows VM: presses a key combination with SendInput-level events, e.g.
# -Keys 'Ctrl+Alt+Space', 'Esc', 'Enter'. Global hotkeys see these like real key presses.
param([Parameter(Mandatory)][string]$Keys)
Add-Type @"
using System; using System.Runtime.InteropServices;
public static class PdKeys { [DllImport("user32.dll")] public static extern void keybd_event(byte vk, byte scan, int flags, IntPtr extra); }
"@
$map = @{ ctrl = 0x11; alt = 0x12; shift = 0x10; space = 0x20; esc = 0x1B; enter = 0x0D; v = 0x56; down = 0x28; up = 0x26; right = 0x27; left = 0x25 }
$vks = $Keys.ToLower().Split('+') | % { $map[$_] }
foreach ($vk in $vks) { [PdKeys]::keybd_event($vk, 0, 0, [IntPtr]::Zero) }
[array]::Reverse($vks)
foreach ($vk in $vks) { [PdKeys]::keybd_event($vk, 0, 2, [IntPtr]::Zero) }
