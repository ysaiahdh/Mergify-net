; Inno Setup script. Built by scripts/build.ps1 (needs dist/ filled first):
;   ISCC /DVersion=0.1.0 installer\mergify-net.iss
#ifndef Version
  #define Version "0.0.0"
#endif

[Setup]
AppId={{8F2A6C1E-5B3D-4E7A-9C41-2D6B8E0F7A13}
AppName=mergify-net
AppVersion={#Version}
AppVerName=mergify-net {#Version}
AppPublisher=mergify-net contributors
#ifdef RepoUrl
AppPublisherURL={#RepoUrl}
AppSupportURL={#RepoUrl}/issues
AppUpdatesURL={#RepoUrl}/releases
#endif
VersionInfoVersion={#Version}
DefaultDirName={autopf}\mergify-net
DefaultGroupName=mergify-net
DisableProgramGroupPage=yes
; The TUN adapter and routes need admin, and so does writing to Program Files.
PrivilegesRequired=admin
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0.17763
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
LicenseFile=LICENSE
SourceDir=..
OutputDir=dist
OutputBaseFilename=mergify-net-setup-{#Version}
UninstallDisplayIcon={app}\mergify-net.ico
CloseApplications=no
; Uninstall removes %LOCALAPPDATA%\mergify-net (status/log files) of the user who installed.
UsedUserAreasWarning=no

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "autostart"; Description: "Start mergify-net when I sign in (no UAC prompt)"; GroupDescription: "Startup:"; Flags: unchecked
Name: "desktopicon"; Description: "Create a &desktop shortcut"; GroupDescription: "Shortcuts:"; Flags: unchecked

[Files]
Source: "dist\mergify-net.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "dist\mergify-net-tray.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "dist\wintun.dll"; DestDir: "{app}"; Flags: ignoreversion
Source: "dist\wintun-LICENSE.txt"; DestDir: "{app}"; Flags: ignoreversion
Source: "dist\mergify-net.ico"; DestDir: "{app}"; Flags: ignoreversion
Source: "LICENSE"; DestDir: "{app}"; Flags: ignoreversion
Source: "THIRD-PARTY-NOTICES.md"; DestDir: "{app}"; Flags: ignoreversion
Source: "README.md"; DestDir: "{app}"; Flags: ignoreversion isreadme

[Icons]
Name: "{autoprograms}\mergify-net"; Filename: "{app}\mergify-net-tray.exe"; IconFilename: "{app}\mergify-net.ico"; Comment: "Use all your network adapters at once"
Name: "{autodesktop}\mergify-net"; Filename: "{app}\mergify-net-tray.exe"; IconFilename: "{app}\mergify-net.ico"; Tasks: desktopicon

[Run]
Filename: "{app}\mergify-net.exe"; Parameters: "autostart on"; Flags: runhidden; Tasks: autostart; StatusMsg: "Enabling start at sign-in..."
; The installer is already elevated, so the tray starts without a second UAC prompt.
Filename: "{app}\mergify-net-tray.exe"; Description: "Start mergify-net now"; Flags: nowait postinstall skipifsilent

[UninstallRun]
Filename: "{app}\mergify-net.exe"; Parameters: "autostart off"; Flags: runhidden; RunOnceId: "AutostartOff"

[UninstallDelete]
Type: filesandordirs; Name: "{localappdata}\mergify-net"

[Code]
// Turn mergify-net off and make sure no copy is running before files are replaced or removed.
// Killing it is safe: the TUN adapter and its routes disappear with the process.
procedure StopMergify;
var
  rc: Integer;
begin
  if FileExists(ExpandConstant('{app}\mergify-net.exe')) then
    Exec(ExpandConstant('{app}\mergify-net.exe'), 'quit', '', SW_HIDE, ewWaitUntilTerminated, rc);
  Exec(ExpandConstant('{sys}\taskkill.exe'), '/F /IM mergify-net-tray.exe', '', SW_HIDE, ewWaitUntilTerminated, rc);
  Exec(ExpandConstant('{sys}\taskkill.exe'), '/F /IM mergify-net.exe', '', SW_HIDE, ewWaitUntilTerminated, rc);
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
begin
  StopMergify;
  Result := '';
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
begin
  if CurUninstallStep = usUninstall then
    StopMergify;
end;
