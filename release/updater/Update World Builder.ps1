param(
    [switch]$Automatic
)

$ErrorActionPreference = "Stop"
$RootDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$Workspace = Join-Path $RootDir "workspace"
$UpdatesDir = Join-Path $RootDir "updates"
$LockDir = Join-Path $RootDir ".world-builder-update.lock"
$Repository = "An-actual-duck/rsc-world-editor"
$ApiUrl = if ($env:WORLD_BUILDER_RELEASE_API_URL) {
    $env:WORLD_BUILDER_RELEASE_API_URL
} else {
    "https://api.github.com/repos/$Repository/releases/latest"
}

function Fail-Update([string]$Message) {
    throw "World Builder update failed: $Message"
}

$VersionPath = Join-Path $RootDir "VERSION.txt"
if (-not (Test-Path -LiteralPath $VersionPath -PathType Leaf)) {
    Fail-Update "VERSION.txt is missing"
}
$CurrentVersion = (Get-Content -LiteralPath $VersionPath -Raw).Trim()
if ($CurrentVersion -notmatch '^v\d+\.\d+\.\d+(?:-alpha\.\d+)?$') {
    Fail-Update "VERSION.txt does not contain a supported semantic version"
}

foreach ($PidPath in @(
    (Join-Path $Workspace "run/server.pid"),
    (Join-Path $Workspace "run/client.pid")
)) {
    if (Test-Path -LiteralPath $PidPath -PathType Leaf) {
        $PidText = (Get-Content -LiteralPath $PidPath -Raw).Trim()
        if ($PidText -match '^\d+$' -and (Get-Process -Id ([int]$PidText) -ErrorAction SilentlyContinue)) {
            Fail-Update "Close World Builder before updating (active process $PidText)"
        }
    }
}

New-Item -ItemType Directory -Force -Path $UpdatesDir | Out-Null
try {
    New-Item -ItemType Directory -Path $LockDir -ErrorAction Stop | Out-Null
} catch {
    Fail-Update "Another World Builder update is already running"
}

$Stage = $null
try {
    $Release = Invoke-RestMethod -Uri $ApiUrl -Headers @{ "User-Agent" = "RSC-World-Editor-Updater" }
    $LatestVersion = [string]$Release.tag_name
    if ($LatestVersion -notmatch '^v\d+\.\d+\.\d+(?:-alpha\.\d+)?$') {
        Fail-Update "The latest GitHub release does not use a supported semantic version"
    }
    if ($LatestVersion -eq $CurrentVersion) {
        if (-not $Automatic) {
            Write-Host "World Builder is up to date ($CurrentVersion)."
        }
        exit 0
    }

    $AssetName = "rsc-world-editor-$LatestVersion-windows-x64.zip"
    $ArchiveAsset = $Release.assets | Where-Object { $_.name -eq $AssetName } | Select-Object -First 1
    $ChecksumAsset = $Release.assets | Where-Object { $_.name -eq "SHA256SUMS.txt" } | Select-Object -First 1
    if (-not $ArchiveAsset -or -not $ChecksumAsset) {
        Fail-Update "Release $LatestVersion does not contain the required Windows archive and checksums"
    }

    $Stage = Join-Path $UpdatesDir (".update-{0}-{1}" -f $LatestVersion, [guid]::NewGuid())
    $Extracted = Join-Path $Stage "extracted"
    $Archive = Join-Path $Stage $AssetName
    $Checksums = Join-Path $Stage "SHA256SUMS.txt"
    New-Item -ItemType Directory -Force -Path $Extracted | Out-Null

    Write-Host "Updating World Builder from $CurrentVersion to $LatestVersion..."
    Invoke-WebRequest -Uri $ArchiveAsset.browser_download_url -OutFile $Archive -Headers @{ "User-Agent" = "RSC-World-Editor-Updater" }
    Invoke-WebRequest -Uri $ChecksumAsset.browser_download_url -OutFile $Checksums -Headers @{ "User-Agent" = "RSC-World-Editor-Updater" }

    $ChecksumLine = Get-Content -LiteralPath $Checksums | Where-Object { $_ -match "\s\*?$([regex]::Escape($AssetName))$" } | Select-Object -First 1
    if (-not $ChecksumLine) {
        Fail-Update "SHA256SUMS.txt does not contain $AssetName"
    }
    $ExpectedHash = ($ChecksumLine -split '\s+')[0].ToLowerInvariant()
    $ActualHash = (Get-FileHash -LiteralPath $Archive -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($ExpectedHash -ne $ActualHash) {
        Fail-Update "Downloaded archive checksum does not match the published checksum"
    }

    Expand-Archive -LiteralPath $Archive -DestinationPath $Extracted -Force
    $PackageRoot = Join-Path $Extracted "Spoiled Milk World Builder"
    $PackageManifest = Join-Path $PackageRoot "PACKAGE-MANIFEST.sha256"
    if (-not (Test-Path -LiteralPath $PackageManifest -PathType Leaf)) {
        Fail-Update "Downloaded package manifest is missing"
    }
    if ((Get-Content -LiteralPath (Join-Path $PackageRoot "VERSION.txt") -Raw).Trim() -ne $LatestVersion) {
        Fail-Update "Downloaded package version does not match its release tag"
    }

    foreach ($Line in Get-Content -LiteralPath $PackageManifest) {
        if ($Line -match '(?:^|\s)\*?\.?[\\/]*(workspace|updates)(?:[\\/]|$)') {
            Fail-Update "Downloaded package manifest attempts to manage durable user state"
        }
        $Parts = $Line -split '\s+', 2
        if ($Parts.Count -ne 2) {
            Fail-Update "Downloaded package manifest is malformed"
        }
        $Relative = $Parts[1].TrimStart('*').TrimStart('.', '/', '\')
        $FilePath = Join-Path $PackageRoot $Relative
        if (-not (Test-Path -LiteralPath $FilePath -PathType Leaf)) {
            Fail-Update "Downloaded package file is missing: $Relative"
        }
        $Hash = (Get-FileHash -LiteralPath $FilePath -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($Hash -ne $Parts[0].ToLowerInvariant()) {
            Fail-Update "Downloaded package file verification failed: $Relative"
        }
    }

    $Backup = Join-Path $Stage "backup"
    New-Item -ItemType Directory -Path $Backup | Out-Null
    $ManagedItems = Get-ChildItem -LiteralPath $RootDir -Force | Where-Object {
        $_.Name -notin @("workspace", "updates", ".world-builder-update.lock", ".workspace.world-builder.lock")
    }
    $ManagedItems | ForEach-Object {
        Copy-Item -LiteralPath $_.FullName -Destination $Backup -Recurse -Force
    }

    try {
        $ManagedItems | ForEach-Object {
            Remove-Item -LiteralPath $_.FullName -Recurse -Force
        }
        Get-ChildItem -LiteralPath $PackageRoot -Force | ForEach-Object {
            Copy-Item -LiteralPath $_.FullName -Destination $RootDir -Recurse -Force
        }
        if ((Get-Content -LiteralPath $VersionPath -Raw).Trim() -ne $LatestVersion) {
            Fail-Update "Installed package version verification failed"
        }
    } catch {
        Get-ChildItem -LiteralPath $Backup -Force | ForEach-Object {
            Copy-Item -LiteralPath $_.FullName -Destination $RootDir -Recurse -Force
        }
        throw
    }

    Write-Host "World Builder updated successfully to $LatestVersion."
    if (Test-Path -LiteralPath $Workspace -PathType Container) {
        Write-Host "Your existing workspace, exports, backups, receipts, and credentials were preserved."
        Write-Host "The existing project remains tied to the runtime snapshot with which it was created."
    }
} finally {
    if ($Stage -and (Test-Path -LiteralPath $Stage)) {
        Remove-Item -LiteralPath $Stage -Recurse -Force -ErrorAction SilentlyContinue
    }
    Remove-Item -LiteralPath $LockDir -Force -ErrorAction SilentlyContinue
}
