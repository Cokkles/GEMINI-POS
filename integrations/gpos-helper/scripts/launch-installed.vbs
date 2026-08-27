Option Explicit

Dim shell, fileSystem, scriptDirectory, powerShell, launchScript, command
Set shell = CreateObject("WScript.Shell")
Set fileSystem = CreateObject("Scripting.FileSystemObject")
scriptDirectory = fileSystem.GetParentFolderName(WScript.ScriptFullName)
powerShell = shell.ExpandEnvironmentStrings("%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe")
launchScript = fileSystem.BuildPath(scriptDirectory, "launch-installed.ps1")
command = """" & powerShell & """ -NoProfile -Sta -WindowStyle Hidden -ExecutionPolicy Bypass -File """ & launchScript & """"
shell.Run command, 0, False
