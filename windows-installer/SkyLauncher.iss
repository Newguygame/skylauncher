#define AppVersion "3.1.2"

[Setup]
AppId={{0C28F03B-7A15-4BAE-8CB4-87AF44A95116}
AppName=SkyLauncher
AppVersion={#AppVersion}
AppPublisher=SkyLauncher
DefaultDirName={localappdata}\Programs\SkyLauncher
DefaultGroupName=SkyLauncher
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
WizardStyle=modern
Compression=lzma2
SolidCompression=yes
OutputDir=..\dist
OutputBaseFilename=SkyLauncher-{#AppVersion}
UninstallDisplayIcon={app}\SkyLauncher.exe
UsePreviousTasks=no

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; GroupDescription: "Shortcuts:"

[Files]
Source: "..\build\windows-app\SkyLauncher\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\SkyLauncher"; Filename: "{app}\SkyLauncher.exe"; WorkingDir: "{app}"
Name: "{autodesktop}\SkyLauncher"; Filename: "{app}\SkyLauncher.exe"; WorkingDir: "{app}"; Tasks: desktopicon

[Run]
Filename: "{app}\SkyLauncher.exe"; Description: "Launch SkyLauncher"; Flags: postinstall nowait skipifsilent
