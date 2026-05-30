param(
  [string]$AppRoot = "$env:ProgramFiles\Lynk Bridge",
  [string]$NodeBin = "node.exe"
)

$ErrorActionPreference = "Stop"
$Cli = Join-Path $AppRoot "dist\host\cli.js"

if ($env:LYNK_BRIDGE_CONFIGURE_MCP) {
  & $NodeBin $Cli refresh --configure-mcp | Out-Host
} else {
  & $NodeBin $Cli refresh | Out-Host
}

& $NodeBin $Cli install-service | Out-Host
& $NodeBin $Cli service-status | Out-Host

Write-Host "Lynk Bridge installed and configured to start at login."
Write-Host "Pair Android with:"
& $NodeBin $Cli pairing --qr | Out-Host
