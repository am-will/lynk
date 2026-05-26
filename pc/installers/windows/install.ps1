param(
  [string]$AppRoot = "$env:ProgramFiles\Android Agent Bridge",
  [string]$NodeBin = "node.exe"
)

$ErrorActionPreference = "Stop"
$TaskName = "AndroidAgentBridge"
$Bridge = Join-Path $AppRoot "dist\bridge\server.js"
$Cli = Join-Path $AppRoot "dist\host\cli.js"

& $NodeBin $Cli refresh | Out-Host

$Action = New-ScheduledTaskAction -Execute $NodeBin -Argument "`"$Bridge`""
$Trigger = New-ScheduledTaskTrigger -AtLogOn
$Principal = New-ScheduledTaskPrincipal -UserId $env:USERNAME -LogonType Interactive -RunLevel LeastPrivilege
$Settings = New-ScheduledTaskSettingsSet -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1)

Register-ScheduledTask -TaskName $TaskName -Action $Action -Trigger $Trigger -Principal $Principal -Settings $Settings -Force | Out-Null

if (-not (Get-NetFirewallRule -DisplayName $TaskName -ErrorAction SilentlyContinue)) {
  New-NetFirewallRule -DisplayName $TaskName -Direction Inbound -Action Allow -Protocol TCP -LocalPort 8788 | Out-Null
}

Write-Host "Android Agent Bridge scheduled task installed."
