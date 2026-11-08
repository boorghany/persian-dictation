# A plain WinForms text box as a paste target; writes its text to $Out when it closes after $Seconds.
param([string]$Out, [int]$Seconds = 15)
Add-Type -AssemblyName System.Windows.Forms
$f = New-Object Windows.Forms.Form
$f.Text = 'PD paste target'; $f.Width = 700; $f.Height = 300; $f.TopMost = $true
$t = New-Object Windows.Forms.TextBox
$t.Multiline = $true; $t.Dock = 'Fill'; $t.RightToLeft = 'Yes'; $t.Font = New-Object Drawing.Font('Segoe UI', 14)
$f.Controls.Add($t)
$timer = New-Object Windows.Forms.Timer
$timer.Interval = $Seconds * 1000
$timer.add_Tick({ [IO.File]::WriteAllText($Out, $t.Text, [Text.Encoding]::UTF8); $f.Close() })
$f.add_Shown({ $f.Activate(); $t.Focus(); $timer.Start() })
[void]$f.ShowDialog()
