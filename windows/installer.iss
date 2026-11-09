; Inno Setup script for the Windows installer (compiled by package.ps1 in the VM).
; One Setup.exe for x64 and ARM64: it installs the build that matches the PC. Per-user install
; (no administrator prompt) into %LOCALAPPDATA%\Programs\Persian Dictation.
; Defines passed by package.ps1: Version, Build, X64Dir, Arm64Dir, IconFile, OutDir.

[Setup]
AppId={{8F2B6C3E-5A47-4E8B-9C1D-2E7A3B9F4D61}
AppName=Persian Dictation
AppVersion={#Version}
AppVerName=Persian Dictation {#Version} (build {#Build})
AppPublisher=Ahmad Boorghany
VersionInfoVersion={#Version}.{#Build}
DefaultDirName={localappdata}\Programs\Persian Dictation
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible or arm64
ArchitecturesInstallIn64BitMode=x64compatible or arm64
OutputDir={#OutDir}
OutputBaseFilename=PersianDictation-{#Version}-build{#Build}-Setup
SetupIconFile={#IconFile}
UninstallDisplayIcon={app}\Persian Dictation.exe
UninstallDisplayName=Persian Dictation
Compression=lzma2/ultra64
SolidCompression=yes
WizardStyle=modern
; We close the running app ourselves (PrepareToInstall), with --quit and then taskkill.
CloseApplications=no

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "startup"; Description: "Start Persian Dictation when Windows starts"
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[Files]
; overwritereadonly: builds up to 1.0.4 build 16 were packaged with read-only files (from the JDK unpacked off the
; Mac share), which made Setup fail to replace them.
Source: "{#X64Dir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs overwritereadonly uninsremovereadonly; Check: not IsArm64
Source: "{#Arm64Dir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs overwritereadonly uninsremovereadonly; Check: IsArm64

[InstallDelete]
; A new version replaces the whole folder (the bundled runtime's files change between versions).
Type: filesandordirs; Name: "{app}\app"
Type: filesandordirs; Name: "{app}\runtime"

[Icons]
Name: "{autoprograms}\Persian Dictation"; Filename: "{app}\Persian Dictation.exe"
Name: "{autodesktop}\Persian Dictation"; Filename: "{app}\Persian Dictation.exe"; Tasks: desktopicon

[Registry]
; Same value the app's "Start with Windows" menu item writes.
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "Persian Dictation"; ValueData: """{app}\Persian Dictation.exe"""; Tasks: startup

[Run]
Filename: "{app}\Persian Dictation.exe"; Description: "{cm:LaunchProgram,Persian Dictation}"; Flags: nowait postinstall skipifsilent

[UninstallDelete]
; Settings, logs and (development mode) recordings, which may contain dictated speech and text.
Type: filesandordirs; Name: "{localappdata}\Persian Dictation"
Type: filesandordirs; Name: "{userappdata}\Persian Dictation"
Type: dirifempty; Name: "{app}"

[Code]
procedure StopApp(Exe: String; WaitForQuit: Boolean);
var
  Code: Integer;
begin
  { Ask a running copy to quit (removes its tray icon cleanly), then make sure it's gone. }
  if FileExists(Exe) then
  begin
    if WaitForQuit then
      { Uninstall: the exe is this same build, whose --quit returns at once. Waiting matters: while the
        --quit process still ran from the app folder, the folder couldn't be removed and stayed behind, empty. }
      Exec(Exe, '--quit', '', SW_HIDE, ewWaitUntilTerminated, Code)
    else
      { Update: the installed exe may be older (builds before 1.0.4 build 15 don't know --quit and
        would just start), so don't wait on it; taskkill below ends it. }
      Exec(Exe, '--quit', '', SW_HIDE, ewNoWait, Code);
    Sleep(2000);
  end;
  Exec(ExpandConstant('{sys}\taskkill.exe'), '/F /IM "Persian Dictation.exe"', '', SW_HIDE, ewWaitUntilTerminated, Code);
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
begin
  StopApp(AddBackslash(WizardDirValue) + 'Persian Dictation.exe', False);
  Result := '';
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
begin
  if CurUninstallStep = usUninstall then
    StopApp(ExpandConstant('{app}\Persian Dictation.exe'), True);
  if CurUninstallStep = usPostUninstall then
    { Also when "Start with Windows" was turned on from the app's menu rather than by Setup. }
    RegDeleteValue(HKCU, 'Software\Microsoft\Windows\CurrentVersion\Run', 'Persian Dictation');
end;
