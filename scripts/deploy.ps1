param(
    [string[]]$Hosts = @("147.182.178.32"),
    [string]$SshUser = "root",
    [string]$SshPort = "22",
    [string]$KeyPath = "$env:USERPROFILE\.ssh\neuroslim\deploy_ed25519",
    [string]$AppUser = "discordbot",
    [string]$AppDir = "/opt/discordbot",
    [string]$ServiceName = "discordbot",
    [string]$JarPath = "build\libs\DiscordBot-1.0.0-all.jar",
    [string]$DiscordToken = $env:DISCORD_TOKEN,
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"

if (-not $SkipBuild) {
    & .\gradlew.bat clean build
}

if (-not (Test-Path -LiteralPath $JarPath)) {
    throw "Jar not found: $JarPath"
}

$sshBase = @("-i", $KeyPath, "-o", "IdentitiesOnly=yes", "-o", "StrictHostKeyChecking=accept-new", "-p", $SshPort)
$scpBase = @("-i", $KeyPath, "-o", "IdentitiesOnly=yes", "-o", "StrictHostKeyChecking=accept-new", "-P", $SshPort)

$unitFile = New-TemporaryFile
$envFile = $null

try {
    $unit = @"
[Unit]
Description=DiscordBot
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=$AppUser
Group=$AppUser
WorkingDirectory=$AppDir
EnvironmentFile=$AppDir/runtime.env
ExecStart=/usr/bin/java -Xms32m -Xmx220m -XX:MaxMetaspaceSize=128m -XX:+UseSerialGC -jar $AppDir/bot.jar
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
"@
    Set-Content -LiteralPath $unitFile -Value $unit -Encoding UTF8

    if ($DiscordToken) {
        $envFile = New-TemporaryFile
        Set-Content -LiteralPath $envFile -Value "DISCORD_TOKEN=$DiscordToken" -Encoding ASCII -NoNewline
    }

    foreach ($hostName in $Hosts) {
        if ([string]::IsNullOrWhiteSpace($hostName)) {
            continue
        }

        $target = "$SshUser@$hostName"
        Write-Host "Deploying $ServiceName to $target"

        & ssh @sshBase $target "id -u '$AppUser' >/dev/null 2>&1 || useradd --system --home '$AppDir' --shell /usr/sbin/nologin '$AppUser'; mkdir -p '$AppDir'; chown '${AppUser}:${AppUser}' '$AppDir'"
        & scp @scpBase $JarPath "${target}:$AppDir/bot.jar.tmp"
        & ssh @sshBase $target "mv '$AppDir/bot.jar.tmp' '$AppDir/bot.jar'; chown '${AppUser}:${AppUser}' '$AppDir/bot.jar'; chmod 0644 '$AppDir/bot.jar'"

        if ($envFile) {
            & scp @scpBase $envFile "${target}:$AppDir/runtime.env.tmp"
            & ssh @sshBase $target "chown '${AppUser}:${AppUser}' '$AppDir/runtime.env.tmp'; chmod 0600 '$AppDir/runtime.env.tmp'; mv '$AppDir/runtime.env.tmp' '$AppDir/runtime.env'"
        } else {
            & ssh @sshBase $target "if [ ! -f '$AppDir/runtime.env' ]; then umask 077; printf 'DISCORD_TOKEN=\n' > '$AppDir/runtime.env'; chown '${AppUser}:${AppUser}' '$AppDir/runtime.env'; fi"
        }

        & scp @scpBase $unitFile "${target}:/etc/systemd/system/$ServiceName.service"
        & ssh @sshBase $target "systemctl daemon-reload; systemctl enable '$ServiceName.service'; systemctl restart '$ServiceName.service'; systemctl --no-pager --full status '$ServiceName.service'"
    }
}
finally {
    Remove-Item -LiteralPath $unitFile -Force -ErrorAction SilentlyContinue
    if ($envFile) {
        Remove-Item -LiteralPath $envFile -Force -ErrorAction SilentlyContinue
    }
}
