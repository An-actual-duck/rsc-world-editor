param(
    [switch]$Automatic
)

$ErrorActionPreference = "Stop"
$RootDir = [IO.Path]::GetFullPath((Split-Path -Parent $MyInvocation.MyCommand.Path))
$Workspace = Join-Path $RootDir "workspace"
$UpdatesDir = Join-Path $RootDir "updates"
$LockDir = Join-Path $RootDir ".world-builder-v2-update.lock"
$Repository = "An-actual-duck/rsc-world-editor"
$ProductId = "rsc-world-editor-v2"
$PackageName = "Spoiled Milk World Builder 2"
$ArtifactPrefix = "rsc-world-editor-v2"
$TagPrefix = "$ArtifactPrefix-"
$ApiUrl = if ($env:WORLD_BUILDER_V2_RELEASE_API_URL) {
    $env:WORLD_BUILDER_V2_RELEASE_API_URL
} else {
    "https://api.github.com/repos/$Repository/releases/latest"
}

function Fail-Update([string]$Message) {
    throw "World Builder 2 update failed: $Message"
}

function Test-BuilderVersion([string]$Version) {
    return $Version -match '^v\d+\.\d+\.\d+(?:-alpha\.\d+)?$'
}

function Test-NewerVersion([string]$Candidate, [string]$Current) {
    if ($Candidate -notmatch '^v(\d+)\.(\d+)\.(\d+)(?:-alpha\.(\d+))?$') {
        return $false
    }
    $CandidateParts = @([int]$Matches[1], [int]$Matches[2], [int]$Matches[3])
    $CandidateAlpha = if ($Matches[4]) { [int]$Matches[4] } else { -1 }
    if ($Current -notmatch '^v(\d+)\.(\d+)\.(\d+)(?:-alpha\.(\d+))?$') {
        return $false
    }
    $CurrentParts = @([int]$Matches[1], [int]$Matches[2], [int]$Matches[3])
    $CurrentAlpha = if ($Matches[4]) { [int]$Matches[4] } else { -1 }
    for ($Index = 0; $Index -lt 3; $Index++) {
        if ($CandidateParts[$Index] -gt $CurrentParts[$Index]) { return $true }
        if ($CandidateParts[$Index] -lt $CurrentParts[$Index]) { return $false }
    }
    if ($CandidateAlpha -eq -1) { return $CurrentAlpha -ne -1 }
    if ($CurrentAlpha -eq -1) { return $false }
    return $CandidateAlpha -gt $CurrentAlpha
}

function Read-ReleaseIdentity(
    [string]$Path,
    [string]$ExpectedVersion,
    [string]$ExpectedTag
) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        Fail-Update "release identity is missing: $Path"
    }
    if ((Get-Item -LiteralPath $Path -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) {
        Fail-Update "release identity is a reparse point"
    }
    $Raw = [IO.File]::ReadAllText($Path)
    try {
        $Identity = $Raw | ConvertFrom-Json
    } catch {
        Fail-Update "release identity is malformed JSON"
    }
    $ExpectedProperties = @(
        "schemaVersion", "productId", "productGeneration", "displayName",
        "updateChannel", "releaseTag", "artifactPrefix",
        "worldCoordinateModel", "automaticUpgradeFromProductIds",
        "legacyProductId", "legacyFinalTag", "legacyWorkspaceMigration",
        "version", "sourceCommit", "coreSourceCommit"
    )
    $ActualProperties = @($Identity.PSObject.Properties.Name)
    if (($ActualProperties -join "`n") -cne ($ExpectedProperties -join "`n")) {
        Fail-Update "release identity fields are missing, reordered, or unexpected"
    }
    $UpgradeSources = @($Identity.automaticUpgradeFromProductIds)
    if (
        $Identity.schemaVersion -ne 1 -or
        $Identity.productId -cne $ProductId -or
        $Identity.productGeneration -ne 2 -or
        $Identity.displayName -cne $PackageName -or
        $Identity.updateChannel -cne $ProductId -or
        $Identity.releaseTag -cne $ExpectedTag -or
        $Identity.artifactPrefix -cne $ArtifactPrefix -or
        $Identity.worldCoordinateModel -cne "signed-layered-v1" -or
        $UpgradeSources.Count -ne 1 -or
        $UpgradeSources[0] -cne $ProductId -or
        $Identity.legacyProductId -cne "rsc-world-editor-v1" -or
        $Identity.legacyFinalTag -cne "v1.1.0" -or
        $Identity.legacyWorkspaceMigration -ne $false -or
        $Identity.version -cne $ExpectedVersion -or
        $Identity.sourceCommit -notmatch '^[0-9a-f]{40}$' -or
        $Identity.coreSourceCommit -notmatch '^[0-9a-f]{40}$'
    ) {
        Fail-Update "release identity is not an exact $ProductId identity"
    }
    $ExpectedRaw = @(
        '{',
        '  "schemaVersion": 1,',
        ('  "productId": "{0}",' -f $ProductId),
        '  "productGeneration": 2,',
        ('  "displayName": "{0}",' -f $PackageName),
        ('  "updateChannel": "{0}",' -f $ProductId),
        ('  "releaseTag": "{0}",' -f $ExpectedTag),
        ('  "artifactPrefix": "{0}",' -f $ArtifactPrefix),
        '  "worldCoordinateModel": "signed-layered-v1",',
        '  "automaticUpgradeFromProductIds": [',
        ('    "{0}"' -f $ProductId),
        '  ],',
        '  "legacyProductId": "rsc-world-editor-v1",',
        '  "legacyFinalTag": "v1.1.0",',
        '  "legacyWorkspaceMigration": false,',
        ('  "version": "{0}",' -f $ExpectedVersion),
        ('  "sourceCommit": "{0}",' -f $Identity.sourceCommit),
        ('  "coreSourceCommit": "{0}"' -f $Identity.coreSourceCommit),
        '}'
    ) -join "`n"
    $ExpectedRaw += "`n"
    if ($Raw -cne $ExpectedRaw) {
        Fail-Update "release identity is not in the canonical v2 format"
    }
    return $Identity
}

function Test-SafeRelativePath([string]$Relative) {
    if (
        [string]::IsNullOrWhiteSpace($Relative) -or
        [IO.Path]::IsPathRooted($Relative) -or
        $Relative.Contains("\") -or
        $Relative.Contains("`r") -or
        $Relative.Contains("`n") -or
        $Relative.Contains("`t")
    ) {
        return $false
    }
    foreach ($Segment in $Relative.Split('/')) {
        if ([string]::IsNullOrEmpty($Segment) -or $Segment -eq "." -or $Segment -eq "..") {
            return $false
        }
    }
    return $true
}

function Test-DurablePath([string]$Relative) {
    $Top = $Relative.Split('/')[0]
    return $Top -in @(
        "workspace", "updates", "exports", "backups", "receipts", "logs",
        "credentials", ".world-builder-v2-update.lock",
        ".workspace.world-builder.lock"
    )
}

function Read-PackageManifest([string]$PackageRoot, [string]$ManifestPath) {
    if (-not (Test-Path -LiteralPath $ManifestPath -PathType Leaf)) {
        Fail-Update "package manifest is missing: $ManifestPath"
    }
    $Seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $Records = [Collections.Generic.List[object]]::new()
    foreach ($Line in Get-Content -LiteralPath $ManifestPath) {
        if ($Line -notmatch '^([0-9a-f]{64})  \./(.+)$') {
            Fail-Update "package manifest is malformed"
        }
        $Hash = $Matches[1]
        $Relative = $Matches[2]
        if (
            -not (Test-SafeRelativePath $Relative) -or
            (Test-DurablePath $Relative) -or
            $Relative -ceq "PACKAGE-MANIFEST.sha256" -or
            -not $Seen.Add($Relative)
        ) {
            Fail-Update "package manifest contains an unsafe or duplicate path: $Relative"
        }
        $FilePath = Join-Path $PackageRoot ($Relative.Replace('/', [IO.Path]::DirectorySeparatorChar))
        if (-not (Test-Path -LiteralPath $FilePath -PathType Leaf)) {
            Fail-Update "package manifest file is missing: $Relative"
        }
        $Item = Get-Item -LiteralPath $FilePath -Force
        if ($Item.Attributes -band [IO.FileAttributes]::ReparsePoint) {
            Fail-Update "package manifest file is a reparse point: $Relative"
        }
        $ActualHash = (Get-FileHash -LiteralPath $FilePath -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($ActualHash -cne $Hash) {
            Fail-Update "package file verification failed: $Relative"
        }
        $Records.Add([pscustomobject]@{
            Relative = $Relative
            FullName = $FilePath
            Hash = $Hash
        })
    }
    if ($Records.Count -eq 0) {
        Fail-Update "package manifest is empty"
    }
    return $Records.ToArray()
}

function Assert-NoReparsePoints([string]$PackageRoot) {
    foreach ($Item in Get-ChildItem -LiteralPath $PackageRoot -Force -Recurse) {
        if ($Item.Attributes -band [IO.FileAttributes]::ReparsePoint) {
            Fail-Update "package contains a reparse point: $($Item.FullName)"
        }
    }
}

function Assert-ExactPackageInventory(
    [string]$PackageRoot,
    [object[]]$Records
) {
    $Expected = @($Records | ForEach-Object { "./$($_.Relative)" } | Sort-Object -CaseSensitive)
    $Actual = @(
        Get-ChildItem -LiteralPath $PackageRoot -Force -Recurse -File |
            Where-Object { $_.Name -cne "PACKAGE-MANIFEST.sha256" } |
            ForEach-Object {
                $Relative = $_.FullName.Substring($PackageRoot.Length).TrimStart('\', '/')
                "./$($Relative.Replace('\', '/'))"
            } |
            Sort-Object -CaseSensitive
    )
    if (($Actual -join "`n") -cne ($Expected -join "`n")) {
        Fail-Update "package inventory contains missing or untracked files"
    }
}

function Assert-RequiredManagedFiles([object[]]$Records) {
    $Managed = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $Records | ForEach-Object { [void]$Managed.Add($_.Relative) }
    foreach ($Required in @(
        "VERSION.txt", "SOURCE-COMMIT.txt", "CORE-SOURCE-COMMIT.txt",
        "RELEASE-IDENTITY.json", "Start World Builder.sh",
        "Start World Builder.cmd", "Update World Builder.sh",
        "Update World Builder.cmd", "Update World Builder.ps1"
    )) {
        if (-not $Managed.Contains($Required)) {
            Fail-Update "package manifest omits required application file: $Required"
        }
    }
}

function Assert-SafeArchive([string]$ArchivePath) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $Archive = [IO.Compression.ZipFile]::OpenRead($ArchivePath)
    $Seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $FoundFile = $false
    try {
        foreach ($Entry in $Archive.Entries) {
            $Name = $Entry.FullName
            if (
                [string]::IsNullOrEmpty($Name) -or
                $Name.Contains("\") -or
                $Name.StartsWith("/") -or
                $Name.Contains("`r") -or
                $Name.Contains("`n") -or
                $Name.Contains("`t") -or
                -not $Seen.Add($Name) -or
                -not $Name.StartsWith("$PackageName/", [StringComparison]::Ordinal)
            ) {
                Fail-Update "downloaded archive has an unsafe or unexpected entry"
            }
            $Relative = $Name.Substring($PackageName.Length + 1).TrimEnd('/')
            if ($Relative -and -not (Test-SafeRelativePath $Relative)) {
                Fail-Update "downloaded archive has an unsafe path: $Name"
            }
            $UnixType = (($Entry.ExternalAttributes -shr 16) -band 0xF000)
            if ($UnixType -eq 0xA000) {
                Fail-Update "downloaded archive contains a symbolic link: $Name"
            }
            if (-not $Name.EndsWith('/')) { $FoundFile = $true }
        }
    } finally {
        $Archive.Dispose()
    }
    if (-not $FoundFile) {
        Fail-Update "downloaded archive contains no application files"
    }
}

function Remove-ManagedFiles([string]$InstallRoot, [object[]]$Records) {
    $Directories = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($Record in $Records) {
        $Path = Join-Path $InstallRoot ($Record.Relative.Replace('/', [IO.Path]::DirectorySeparatorChar))
        if (Test-Path -LiteralPath $Path) {
            [IO.File]::SetAttributes($Path, [IO.FileAttributes]::Normal)
            Remove-Item -LiteralPath $Path -Force
        }
        $Parent = Split-Path -Parent $Record.Relative
        while ($Parent) {
            [void]$Directories.Add($Parent)
            $Next = Split-Path -Parent $Parent
            if ($Next -ceq $Parent) { break }
            $Parent = $Next
        }
    }
    $Manifest = Join-Path $InstallRoot "PACKAGE-MANIFEST.sha256"
    if (Test-Path -LiteralPath $Manifest) {
        [IO.File]::SetAttributes($Manifest, [IO.FileAttributes]::Normal)
        Remove-Item -LiteralPath $Manifest -Force
    }
    $Directories |
        Sort-Object { $_.Split('/').Count } -Descending |
        ForEach-Object {
            $Directory = Join-Path $InstallRoot ($_.Replace('/', [IO.Path]::DirectorySeparatorChar))
            if (Test-Path -LiteralPath $Directory -PathType Container) {
                $Children = @(Get-ChildItem -LiteralPath $Directory -Force)
                if ($Children.Count -eq 0) { Remove-Item -LiteralPath $Directory -Force }
            }
        }
}

$VersionPath = Join-Path $RootDir "VERSION.txt"
if (-not (Test-Path -LiteralPath $VersionPath -PathType Leaf)) {
    Fail-Update "VERSION.txt is missing"
}
$CurrentVersion = (Get-Content -LiteralPath $VersionPath -Raw).Trim()
if (-not (Test-BuilderVersion $CurrentVersion)) {
    Fail-Update "VERSION.txt does not contain a supported World Builder 2 version"
}
$CurrentTag = "$TagPrefix$($CurrentVersion.Substring(1))"
$InstalledIdentity = Read-ReleaseIdentity (Join-Path $RootDir "RELEASE-IDENTITY.json") $CurrentVersion $CurrentTag
if (
    (Get-Content -LiteralPath (Join-Path $RootDir "SOURCE-COMMIT.txt") -Raw).Trim() -cne $InstalledIdentity.sourceCommit -or
    (Get-Content -LiteralPath (Join-Path $RootDir "CORE-SOURCE-COMMIT.txt") -Raw).Trim() -cne $InstalledIdentity.coreSourceCommit
) {
    Fail-Update "installed release provenance does not match its v2 identity"
}
$InstalledManifestPath = Join-Path $RootDir "PACKAGE-MANIFEST.sha256"
$InstalledRecords = @(Read-PackageManifest $RootDir $InstalledManifestPath)

foreach ($PidPath in @(
    (Join-Path $Workspace "run/server.pid"),
    (Join-Path $Workspace "run/client.pid")
)) {
    if (Test-Path -LiteralPath $PidPath -PathType Leaf) {
        $PidText = (Get-Content -LiteralPath $PidPath -Raw).Trim()
        if ($PidText -match '^\d+$' -and (Get-Process -Id ([int]$PidText) -ErrorAction SilentlyContinue)) {
            Fail-Update "Close World Builder 2 before updating (active process $PidText)"
        }
    }
}

New-Item -ItemType Directory -Force -Path $UpdatesDir | Out-Null
try {
    New-Item -ItemType Directory -Path $LockDir -ErrorAction Stop | Out-Null
} catch {
    Fail-Update "another World Builder 2 update is already running"
}

$Stage = $null
$Backup = $null
$DownloadedRecords = @()
$RollbackArmed = $false
try {
    $Release = Invoke-RestMethod -Uri $ApiUrl -Headers @{ "User-Agent" = "RSC-World-Editor-V2-Updater" }
    $LatestTag = [string]$Release.tag_name
    if ($LatestTag -notmatch '^rsc-world-editor-v2-\d+\.\d+\.\d+(?:-alpha\.\d+)?$') {
        Fail-Update "the latest release is not on the $ProductId update channel"
    }
    $LatestVersion = "v$($LatestTag.Substring($TagPrefix.Length))"
    if (-not (Test-BuilderVersion $LatestVersion)) {
        Fail-Update "the latest World Builder 2 release has an unsupported version"
    }
    if ($LatestVersion -ceq $CurrentVersion) {
        if (-not $Automatic) { Write-Host "World Builder 2 is up to date ($CurrentVersion)." }
        return
    }
    if (-not (Test-NewerVersion $LatestVersion $CurrentVersion)) {
        if (-not $Automatic) {
            Write-Host "Installed World Builder 2 $CurrentVersion is newer than channel release $LatestVersion; no downgrade was performed."
        }
        return
    }

    $AssetName = "$ArtifactPrefix-$($LatestVersion.Substring(1))-windows-x64.zip"
    $ArchiveAsset = $Release.assets | Where-Object { $_.name -ceq $AssetName } | Select-Object -First 2
    $ChecksumAsset = $Release.assets | Where-Object { $_.name -ceq "SHA256SUMS.txt" } | Select-Object -First 2
    if (@($ArchiveAsset).Count -ne 1 -or @($ChecksumAsset).Count -ne 1) {
        Fail-Update "release $LatestTag does not contain one exact Windows archive and checksum asset"
    }

    $Stage = Join-Path $UpdatesDir (".update-{0}-{1}" -f $LatestVersion.Substring(1), [guid]::NewGuid())
    $Extracted = Join-Path $Stage "extracted"
    $ArchivePath = Join-Path $Stage $AssetName
    $Checksums = Join-Path $Stage "SHA256SUMS.txt"
    New-Item -ItemType Directory -Force -Path $Extracted | Out-Null

    Write-Host "Updating World Builder 2 from $CurrentVersion to $LatestVersion..."
    Invoke-WebRequest -Uri $ArchiveAsset.browser_download_url -OutFile $ArchivePath -Headers @{ "User-Agent" = "RSC-World-Editor-V2-Updater" }
    Invoke-WebRequest -Uri $ChecksumAsset.browser_download_url -OutFile $Checksums -Headers @{ "User-Agent" = "RSC-World-Editor-V2-Updater" }

    $ChecksumLines = @(
        Get-Content -LiteralPath $Checksums |
            Where-Object { $_ -match ('^[0-9a-fA-F]{64}\s+\*?' + [regex]::Escape($AssetName) + '$') }
    )
    if ($ChecksumLines.Count -ne 1) {
        Fail-Update "SHA256SUMS.txt does not contain one unambiguous checksum for $AssetName"
    }
    $ExpectedHash = ($ChecksumLines[0] -split '\s+')[0].ToLowerInvariant()
    $ActualHash = (Get-FileHash -LiteralPath $ArchivePath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($ExpectedHash -cne $ActualHash) {
        Fail-Update "downloaded archive checksum does not match the published checksum"
    }

    Assert-SafeArchive $ArchivePath
    Expand-Archive -LiteralPath $ArchivePath -DestinationPath $Extracted
    $PackageRoot = Join-Path $Extracted $PackageName
    $ExtractedRoots = @(Get-ChildItem -LiteralPath $Extracted -Force)
    if (
        $ExtractedRoots.Count -ne 1 -or
        -not (Test-Path -LiteralPath $PackageRoot -PathType Container) -or
        ($ExtractedRoots[0].Attributes -band [IO.FileAttributes]::ReparsePoint)
    ) {
        Fail-Update "downloaded archive has an unexpected package root"
    }
    Assert-NoReparsePoints $PackageRoot
    $DownloadedManifestPath = Join-Path $PackageRoot "PACKAGE-MANIFEST.sha256"
    $DownloadedRecords = @(Read-PackageManifest $PackageRoot $DownloadedManifestPath)
    Assert-ExactPackageInventory $PackageRoot $DownloadedRecords
    Assert-RequiredManagedFiles $DownloadedRecords

    if ((Get-Content -LiteralPath (Join-Path $PackageRoot "VERSION.txt") -Raw).Trim() -cne $LatestVersion) {
        Fail-Update "downloaded package version does not match its release tag"
    }
    $DownloadedIdentity = Read-ReleaseIdentity (Join-Path $PackageRoot "RELEASE-IDENTITY.json") $LatestVersion $LatestTag
    if (
        (Get-Content -LiteralPath (Join-Path $PackageRoot "SOURCE-COMMIT.txt") -Raw).Trim() -cne $DownloadedIdentity.sourceCommit -or
        (Get-Content -LiteralPath (Join-Path $PackageRoot "CORE-SOURCE-COMMIT.txt") -Raw).Trim() -cne $DownloadedIdentity.coreSourceCommit
    ) {
        Fail-Update "downloaded package provenance does not match its v2 identity"
    }

    $OldManaged = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $InstalledRecords | ForEach-Object { [void]$OldManaged.Add($_.Relative) }
    [void]$OldManaged.Add("PACKAGE-MANIFEST.sha256")
    foreach ($Record in $DownloadedRecords) {
        $Destination = Join-Path $RootDir ($Record.Relative.Replace('/', [IO.Path]::DirectorySeparatorChar))
        if ((Test-Path -LiteralPath $Destination) -and -not $OldManaged.Contains($Record.Relative)) {
            Fail-Update "update would overwrite an unmanaged installed path: $($Record.Relative)"
        }
        $Ancestor = Split-Path -Parent $Record.Relative
        while ($Ancestor) {
            $AncestorPath = Join-Path $RootDir ($Ancestor.Replace('/', [IO.Path]::DirectorySeparatorChar))
            if (
                (Test-Path -LiteralPath $AncestorPath) -and
                -not (Test-Path -LiteralPath $AncestorPath -PathType Container) -and
                -not $OldManaged.Contains($Ancestor)
            ) {
                Fail-Update "update path is blocked by unmanaged installed data: $Ancestor"
            }
            $Next = Split-Path -Parent $Ancestor
            if ($Next -ceq $Ancestor) { break }
            $Ancestor = $Next
        }
    }

    $Backup = Join-Path $Stage "backup"
    New-Item -ItemType Directory -Path $Backup | Out-Null
    foreach ($Record in $InstalledRecords) {
        $BackupPath = Join-Path $Backup ($Record.Relative.Replace('/', [IO.Path]::DirectorySeparatorChar))
        $BackupParent = Split-Path -Parent $BackupPath
        if ($BackupParent) { New-Item -ItemType Directory -Force -Path $BackupParent | Out-Null }
        Copy-Item -LiteralPath $Record.FullName -Destination $BackupPath -Force
    }
    Copy-Item -LiteralPath $InstalledManifestPath -Destination (Join-Path $Backup "PACKAGE-MANIFEST.sha256") -Force

    $RollbackArmed = $true
    Remove-ManagedFiles $RootDir $InstalledRecords
    Get-ChildItem -LiteralPath $PackageRoot -Force | ForEach-Object {
        Copy-Item -LiteralPath $_.FullName -Destination $RootDir -Recurse -Force
    }
    $VerifiedRecords = @(Read-PackageManifest $RootDir (Join-Path $RootDir "PACKAGE-MANIFEST.sha256"))
    $VerifiedIdentity = Read-ReleaseIdentity (Join-Path $RootDir "RELEASE-IDENTITY.json") $LatestVersion $LatestTag
    if ((Get-Content -LiteralPath $VersionPath -Raw).Trim() -cne $LatestVersion) {
        Fail-Update "installed update version verification failed"
    }
    $RollbackArmed = $false

    Write-Host "World Builder 2 updated successfully to $LatestVersion."
    if (Test-Path -LiteralPath $Workspace -PathType Container) {
        Write-Host "Your existing v2 workspace, exports, backups, receipts, credentials, database, and logs were preserved."
        Write-Host "The existing project remains tied to the runtime snapshot with which it was created."
    }
} catch {
    $OriginalFailure = $_
    if ($RollbackArmed) {
        try {
            Remove-ManagedFiles $RootDir $DownloadedRecords
            Get-ChildItem -LiteralPath $Backup -Force | ForEach-Object {
                Copy-Item -LiteralPath $_.FullName -Destination $RootDir -Recurse -Force
            }
            [void](Read-PackageManifest $RootDir (Join-Path $RootDir "PACKAGE-MANIFEST.sha256"))
            Write-Warning "The previous World Builder 2 application files were restored."
            $RollbackArmed = $false
        } catch {
            throw "World Builder 2 update failed and automatic rollback could not fully restore the previous application. Preserve workspace/ and updates/ for recovery. Original failure: $($OriginalFailure.Exception.Message); rollback failure: $($_.Exception.Message)"
        }
    }
    throw $OriginalFailure
} finally {
    if ($Stage -and (Test-Path -LiteralPath $Stage)) {
        Remove-Item -LiteralPath $Stage -Recurse -Force -ErrorAction SilentlyContinue
    }
    Remove-Item -LiteralPath $LockDir -Force -ErrorAction SilentlyContinue
}
