; Inno Setup Script para generar el instalador oficial de OmniVault Desktop
; Para compilar este instalador, descarga e instala Inno Setup (gratuito) desde https://jrsoftware.org/isdl.php
; Abre este archivo en Inno Setup Compiler y presiona 'Compile' (F9).

#ifndef MyAppVersion
#define MyAppVersion "2.5.7"
#endif

#ifndef OutputBaseFilename
#define OutputBaseFilename "OmniVault-v" + MyAppVersion + "-Windows-x64-Setup"
#endif

#define MyAppName "OmniVault Desktop"
#define MyAppPublisher "OmniVault Media Labs"
#define MyAppURL "https://github.com/SirDurand1708/UniversalMediaDownloader"
#define MyAppExeName "OmniVault.exe"

[Setup]
AppId={{E58C372A-23D1-46B9-A2C3-A20D75A7B7E2}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
AppPublisherURL={#MyAppURL}
AppSupportURL={#MyAppURL}
AppUpdatesURL={#MyAppURL}
DefaultDirName={autopf}\{#MyAppName}
DisableProgramGroupPage=yes
LicenseFile=LICENSE
OutputDir=dist\Releases
OutputBaseFilename={#OutputBaseFilename}
Compression=lzma2/ultra64
SolidCompression=yes
WizardStyle=modern
PrivilegesRequired=lowest
SetupIconFile=assets\icon.ico
UninstallDisplayIcon={app}\{#MyAppExeName}

[Languages]
Name: "spanish"; MessagesFile: "compiler:Languages\Spanish.isl"
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[Files]
; Archivos generados por PyInstaller en dist\OmniVault
Source: "dist\OmniVault\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; IconFilename: "{app}\assets\icon.ico"
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; Tasks: desktopicon; IconFilename: "{app}\assets\icon.ico"

[Run]
Filename: "{app}\{#MyAppExeName}"; Description: "{cm:LaunchProgram,{#StringChange(MyAppName, '&', '&&')}}"; Flags: nowait postinstall skipifsilent
