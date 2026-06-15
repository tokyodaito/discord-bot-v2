param(
    [string[]]$Hosts = @("147.182.178.32"),
    [string]$SshUser = "root",
    [string]$SshPort = "22",
    [string]$KeyPath = "$env:USERPROFILE\.ssh\neuroslim\deploy_ed25519",
    [string]$DiscordToken = $env:DISCORD_TOKEN
)

$ErrorActionPreference = "Stop"

& gh auth status | Out-Host

if (-not (Test-Path -LiteralPath $KeyPath)) {
    throw "SSH key not found: $KeyPath"
}

& gh secret set DISCORD_BOT_HOSTS --body ($Hosts -join ",")
& gh secret set DISCORD_BOT_SSH_USER --body $SshUser
& gh secret set DISCORD_BOT_SSH_PORT --body $SshPort
& gh secret set DISCORD_BOT_SSH_KEY --body-file $KeyPath

if ($DiscordToken) {
    $tokenFile = New-TemporaryFile
    try {
        Set-Content -LiteralPath $tokenFile -Value $DiscordToken -Encoding ASCII -NoNewline
        & gh secret set DISCORD_TOKEN --body-file $tokenFile
    }
    finally {
        Remove-Item -LiteralPath $tokenFile -Force -ErrorAction SilentlyContinue
    }
} else {
    Write-Warning "DISCORD_TOKEN is not set. Set it later with: gh secret set DISCORD_TOKEN"
}

Write-Host "GitHub deploy secrets are configured."
