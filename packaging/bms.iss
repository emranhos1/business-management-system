; Inno Setup script for BMS. Built by .github/workflows/release.yml as:
;   iscc /DAppVersion=1.0.0 packaging\bms.iss
; Input is the jpackage app-image in target\jpackage\BMS (app + its own Java runtime).
; It does NOT install MySQL. It is the installer the app downloads for an update, and it also works
; for a first install on a PC that already has MySQL. Data lives in MySQL and %APPDATA%\BMS, so
; installing over an old version never touches it.

#ifndef AppVersion
  #define AppVersion "0.0.0"
#endif
#define AppName "Business Management System"
#define AppExe "BMS.exe"

[Setup]
; Never change AppId - it is how Windows knows a new version replaces the old one.
AppId={{DCBB5E5D-FA3F-4990-82C0-E8D3B5F4A736}
AppName={#AppName}
AppVersion={#AppVersion}
AppVerName={#AppName} {#AppVersion}
; first install suggests this; every later run suggests the folder it was installed in last time
DefaultDirName={autopf}\BMS
DisableProgramGroupPage=yes
DisableDirPage=no
UsePreviousAppDir=yes
OutputDir=..\target\installer
OutputBaseFilename=BMS-Update-{#AppVersion}
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
PrivilegesRequired=admin
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
; close a running BMS before replacing its files
CloseApplications=force
RestartApplications=no
UninstallDisplayIcon={app}\{#AppExe}
SetupIconFile=bms.ico

[InstallDelete]
; drop the previous version's Java runtime and libraries so no stale jars are left behind
Type: filesandordirs; Name: "{app}\runtime"
Type: filesandordirs; Name: "{app}\app\lib"

[Files]
Source: "..\target\jpackage\BMS\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Dirs]
; Program Files is not writable for a normal user, so the app runs from here
Name: "{localappdata}\BMS"

[Icons]
Name: "{autoprograms}\BMS"; Filename: "{app}\{#AppExe}"; WorkingDir: "{localappdata}\BMS"
Name: "{autodesktop}\BMS"; Filename: "{app}\{#AppExe}"; WorkingDir: "{localappdata}\BMS"

[Run]
; "Start BMS" checkbox on the last page; runs as the normal user, not as admin
Filename: "{app}\{#AppExe}"; WorkingDir: "{localappdata}\BMS"; Description: "Start BMS"; Flags: nowait postinstall runasoriginaluser
