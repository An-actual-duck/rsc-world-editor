param(
    [switch]$Automatic
)

$ErrorActionPreference = "Stop"
[Net.ServicePointManager]::SecurityProtocol = (
    [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
)
$RootDir = [IO.Path]::GetFullPath((Split-Path -Parent $MyInvocation.MyCommand.Path))
$Workspace = Join-Path $RootDir "workspace"
$Projects = Join-Path $RootDir "projects"
$ProjectRegistry = Join-Path $RootDir "project-registry.json"
$ActiveProject = Join-Path $RootDir "active-project.json"
$UpdatesDir = Join-Path $RootDir "updates"
$LockDir = Join-Path $RootDir ".world-builder-v2-update.lock"
$Repository = "An-actual-duck/rsc-world-editor"
$ProductId = "rsc-world-editor-v2"
$PackageName = "World Builder 2"
$ArtifactPrefix = "rsc-world-editor-v2"
$WorldSourceIdentity = "target-adaptive-v1"
$TagPrefix = "$ArtifactPrefix-"
$ApiUrl = if ($env:WORLD_BUILDER_V2_RELEASE_API_URL) {
    $env:WORLD_BUILDER_V2_RELEASE_API_URL
} else {
    "https://api.github.com/repos/$Repository/releases?per_page=100"
}

function Fail-Update([string]$Message) {
    throw "World Builder 2 update failed: $Message"
}

function Test-BuilderVersion([string]$Version) {
    return $Version -cmatch '^v(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)(?:-alpha\.(?:0|[1-9]\d*))?$'
}

function Compare-NumericIdentifier([string]$Candidate, [string]$Current) {
    if ($Candidate.Length -gt $Current.Length) { return 1 }
    if ($Candidate.Length -lt $Current.Length) { return -1 }
    return [Math]::Sign([String]::CompareOrdinal($Candidate, $Current))
}

function Test-NewerVersion([string]$Candidate, [string]$Current) {
    if ($Candidate -cnotmatch '^v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-alpha\.(0|[1-9]\d*))?$') {
        return $false
    }
    $CandidateParts = @($Matches[1], $Matches[2], $Matches[3])
    $CandidateAlpha = if ([string]::IsNullOrEmpty($Matches[4])) { $null } else { $Matches[4] }
    if ($Current -cnotmatch '^v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-alpha\.(0|[1-9]\d*))?$') {
        return $false
    }
    $CurrentParts = @($Matches[1], $Matches[2], $Matches[3])
    $CurrentAlpha = if ([string]::IsNullOrEmpty($Matches[4])) { $null } else { $Matches[4] }
    for ($Index = 0; $Index -lt 3; $Index++) {
        $Comparison = Compare-NumericIdentifier $CandidateParts[$Index] $CurrentParts[$Index]
        if ($Comparison -gt 0) { return $true }
        if ($Comparison -lt 0) { return $false }
    }
    if ($null -eq $CandidateAlpha) { return $null -ne $CurrentAlpha }
    if ($null -eq $CurrentAlpha) { return $false }
    return (Compare-NumericIdentifier $CandidateAlpha $CurrentAlpha) -gt 0
}

function Select-NewestV2Release([object[]]$Releases) {
    $SeenTags = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $LatestRelease = $null
    $LatestVersion = $null
    foreach ($Release in @($Releases)) {
        if ($null -eq $Release) { continue }
        $Properties = @($Release.PSObject.Properties.Name)
        if (
            -not ($Properties -ccontains "tag_name") -or
            -not ($Properties -ccontains "draft") -or
            -not ($Properties -ccontains "prerelease") -or
            $Release.tag_name -isnot [string] -or
            $Release.draft -isnot [bool] -or
            $Release.prerelease -isnot [bool] -or
            $Release.draft
        ) {
            continue
        }
        $CandidateTag = [string]$Release.tag_name
        if (-not $CandidateTag.StartsWith($TagPrefix, [StringComparison]::Ordinal)) {
            continue
        }
        $CandidateVersion = "v$($CandidateTag.Substring($TagPrefix.Length))"
        if (-not (Test-BuilderVersion $CandidateVersion)) { continue }
        if (-not $SeenTags.Add($CandidateTag)) {
            Fail-Update "the World Builder 2 release channel returned duplicate tag $CandidateTag"
        }
        if (
            $null -eq $LatestRelease -or
            (Test-NewerVersion $CandidateVersion $LatestVersion)
        ) {
            $LatestRelease = $Release
            $LatestVersion = $CandidateVersion
        }
    }
    if ($null -eq $LatestRelease) {
        Fail-Update "the World Builder 2 release channel contains no published valid $ProductId release"
    }
    return $LatestRelease
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
        "worldSourceIdentity", "automaticUpgradeFromProductIds",
        "legacyProductId", "legacyFinalTag", "legacyWorkspaceMigration",
        "version", "sourceCommit", "runtimeProviderCommit"
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
        $Identity.worldSourceIdentity -cne $WorldSourceIdentity -or
        $UpgradeSources.Count -ne 1 -or
        $UpgradeSources[0] -cne $ProductId -or
        $Identity.legacyProductId -cne "rsc-world-editor-v1" -or
        $Identity.legacyFinalTag -cne "v1.1.0" -or
        $Identity.legacyWorkspaceMigration -ne $false -or
        $Identity.version -cne $ExpectedVersion -or
        $Identity.sourceCommit -notmatch '^[0-9a-f]{40}$' -or
        $Identity.runtimeProviderCommit -notmatch '^[0-9a-f]{40}$'
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
        ('  "worldSourceIdentity": "{0}",' -f $WorldSourceIdentity),
        '  "automaticUpgradeFromProductIds": [',
        ('    "{0}"' -f $ProductId),
        '  ],',
        '  "legacyProductId": "rsc-world-editor-v1",',
        '  "legacyFinalTag": "v1.1.0",',
        '  "legacyWorkspaceMigration": false,',
        ('  "version": "{0}",' -f $ExpectedVersion),
        ('  "sourceCommit": "{0}",' -f $Identity.sourceCommit),
        ('  "runtimeProviderCommit": "{0}"' -f $Identity.runtimeProviderCommit),
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
        $Relative -match '[\x00-\x1f<>:"|?*]'
    ) {
        return $false
    }
    $Reserved = @(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    )
    foreach ($Segment in $Relative.Split('/')) {
        $BaseName = $Segment.Split('.')[0].ToUpperInvariant()
        if (
            [string]::IsNullOrEmpty($Segment) -or
            $Segment -eq "." -or
            $Segment -eq ".." -or
            $Segment.EndsWith(" ") -or
            $Segment.EndsWith(".") -or
            $Reserved -contains $BaseName
        ) {
            return $false
        }
    }
    return $true
}

function Test-DurablePath([string]$Relative) {
    $Top = $Relative.Split('/')[0]
    return $Top -in @(
        "projects", "project-registry.json", "active-project.json", "workspace",
        "updates", "exports", "backups", "receipts", "diagnostics", "logs",
        "settings", "credentials", "recovery", ".world-builder-v2-update.lock",
        ".workspace.world-builder.lock", ".project-registry.lock"
    )
}

function Assert-SafeManagedPath([string]$PackageRoot, [string]$Relative) {
    $RootItem = Get-Item -LiteralPath $PackageRoot -Force
    if ($RootItem.Attributes -band [IO.FileAttributes]::ReparsePoint) {
        Fail-Update "package root is a reparse point"
    }
    $Candidate = $PackageRoot
    foreach ($Segment in $Relative.Split('/')) {
        $Candidate = Join-Path $Candidate $Segment
        $Item = Get-Item -LiteralPath $Candidate -Force -ErrorAction SilentlyContinue
        if ($Item -and ($Item.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            Fail-Update "managed path crosses a reparse point: $Relative"
        }
    }
}

function Read-PackageManifest([string]$PackageRoot, [string]$ManifestPath) {
    if (-not (Test-Path -LiteralPath $ManifestPath -PathType Leaf)) {
        Fail-Update "package manifest is missing: $ManifestPath"
    }
    if ((Get-Item -LiteralPath $ManifestPath -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) {
        Fail-Update "package manifest is a reparse point"
    }
    $Seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
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
        Assert-SafeManagedPath $PackageRoot $Relative
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

function Assert-RequiredManagedFiles(
    [object[]]$Records,
    [string]$RuntimeJava
) {
    $Managed = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    $Records | ForEach-Object { [void]$Managed.Add($_.Relative) }
    foreach ($Required in @(
        "VERSION.txt", "SOURCE-COMMIT.txt", "RUNTIME-PROVIDER-COMMIT.txt",
        "RELEASE-IDENTITY.json", "Start World Builder.sh",
        "Start World Builder.cmd", "Update World Builder.sh",
        "Update World Builder.cmd", "Update World Builder.ps1",
        "Import Map Changes.sh", "Import Map Changes.cmd",
        "Recover Map Transaction.sh", "Recover Map Transaction.cmd",
        "Undo Last Map Import.sh", "Undo Last Map Import.cmd",
        "RUNTIME-ASSET-ALLOWLIST.txt",
        "builder-runtime/Client_Base/Open_RSC_Client.jar",
        "builder-runtime/server/core.jar",
        "builder-runtime/server/plugins.jar",
        "builder-runtime/server/inc/sqlite/world_builder_seed.db",
        "builder-runtime/server/world-builder.conf",
        "builder-runtime/server/conf/world-builder/adaptive-runtime-capability-v2.json",
        "builder-runtime/launcher/world-builder-tools.jar",
        $RuntimeJava
    )) {
        if (-not $Managed.Contains($Required)) {
            Fail-Update "package manifest omits required application file: $Required"
        }
    }
}

function Assert-ApplicationAllowlist(
    [string]$PackageRoot,
    [object[]]$Records
) {
    $Allowed = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($Relative in @(
        "ASSET-SOURCES.txt", "RUNTIME-PROVIDER-COMMIT.txt", "EDITOR-ICON-CREDITS.txt",
        "Import Map Changes.cmd", "Import Map Changes.sh", "LICENSE",
        "PLAYER-ASSET-SOURCES.txt", "README.txt", "RELEASE-IDENTITY.json",
        "Recover Map Transaction.cmd", "Recover Map Transaction.sh",
        "RUNTIME-ASSET-ALLOWLIST.txt", "SOURCE-COMMIT.txt", "Start World Builder.cmd",
        "Start World Builder.sh", "Undo Last Map Import.cmd", "Undo Last Map Import.sh",
        "Update World Builder.cmd", "Update World Builder.ps1", "Update World Builder.sh",
        "VERSION.txt", "builder-runtime/Client_Base/Open_RSC_Client.jar",
        "builder-runtime/server/core.jar", "builder-runtime/server/plugins.jar",
        "builder-runtime/server/world-builder.conf",
        "builder-runtime/launcher/world-builder-tools.jar"
    )) {
        [void]$Allowed.Add($Relative)
    }
    foreach ($Schema in @(
        "active-project-v1.schema.json", "adaptive-contract-definitions-v1.schema.json",
        "conversion-plan-v1.schema.json", "conversion-report-v1.schema.json",
        "discovery-report-v2.schema.json", "export-manifest-v1.schema.json",
        "export-manifest-v2.schema.json", "import-receipt-v1.schema.json",
        "import-receipt-v3.schema.json", "project-manifest-v1.schema.json",
        "project-manifest-v2.schema.json", "project-registry-v1.schema.json",
        "region-bundle-manifest-v1.schema.json",
        "region-compatibility-report-v1.schema.json",
        "region-operation-plan-v1.schema.json", "region-selection-v1.schema.json",
        "region-snapshot-v1.schema.json", "region-snapshot-v2.schema.json",
        "source-snapshot-v2.schema.json", "target-capability-v1.schema.json",
        "target-mutation-plan-v1.schema.json"
    )) {
        [void]$Allowed.Add("builder-runtime/launcher/schema/$Schema")
    }
    $RuntimeAllowlist = Join-Path $PackageRoot "RUNTIME-ASSET-ALLOWLIST.txt"
    if (
        -not (Test-Path -LiteralPath $RuntimeAllowlist -PathType Leaf) -or
        ((Get-Item -LiteralPath $RuntimeAllowlist -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)
    ) {
        Fail-Update "runtime asset allowlist is missing or unsafe"
    }
    foreach ($Line in Get-Content -LiteralPath $RuntimeAllowlist) {
        if ([string]::IsNullOrEmpty($Line) -or $Line.StartsWith("#", [StringComparison]::Ordinal)) {
            continue
        }
        $Fields = $Line.Split("`t")
        if (
            $Fields.Count -ne 3 -or
            -not (Test-SafeRelativePath $Fields[0]) -or
            -not (Test-SafeRelativePath $Fields[1]) -or
            [string]::IsNullOrWhiteSpace($Fields[2]) -or
            -not $Allowed.Add("builder-runtime/$($Fields[1])")
        ) {
            Fail-Update "runtime asset allowlist is malformed or has duplicate destinations"
        }
    }
    foreach ($Record in $Records) {
        if (
            -not $Allowed.Contains($Record.Relative) -and
            -not $Record.Relative.StartsWith("runtime/", [StringComparison]::Ordinal)
        ) {
            Fail-Update "package manifest owns a path outside the content-neutral application allowlist: $($Record.Relative)"
        }
    }
}

function Assert-SafeArchive([string]$ArchivePath) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $Archive = [IO.Compression.ZipFile]::OpenRead($ArchivePath)
    $Seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
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
    $Directories = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($Record in $Records) {
        Assert-SafeManagedPath $InstallRoot $Record.Relative
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
$InstalledIdentityPath = Join-Path $RootDir "RELEASE-IDENTITY.json"
if (
    (Test-Path -LiteralPath $InstalledIdentityPath -PathType Leaf) -and
    ([IO.File]::ReadAllText($InstalledIdentityPath).Contains('"worldCoordinateModel": "signed-layered-v1"'))
) {
    Fail-Update "this is a historical pre-adaptive World Builder 2 installation. Automatic relabelling or workspace migration is unsupported; preserve the complete folder and install adaptive World Builder 2 separately"
}
$InstalledIdentity = Read-ReleaseIdentity $InstalledIdentityPath $CurrentVersion $CurrentTag
if (
    (Get-Content -LiteralPath (Join-Path $RootDir "SOURCE-COMMIT.txt") -Raw).Trim() -cne $InstalledIdentity.sourceCommit -or
    (Get-Content -LiteralPath (Join-Path $RootDir "RUNTIME-PROVIDER-COMMIT.txt") -Raw).Trim() -cne $InstalledIdentity.runtimeProviderCommit
) {
    Fail-Update "installed release provenance does not match its v2 identity"
}
$InstalledManifestPath = Join-Path $RootDir "PACKAGE-MANIFEST.sha256"
$InstalledRecords = @(Read-PackageManifest $RootDir $InstalledManifestPath)
Assert-RequiredManagedFiles $InstalledRecords "runtime/bin/java.exe"
Assert-ApplicationAllowlist $RootDir $InstalledRecords

if ((Test-Path -LiteralPath $Workspace) -and -not (Test-Path -LiteralPath $ProjectRegistry)) {
    Fail-Update "this is a historical pre-adaptive World Builder 2 installation. Its workspace was preserved, but it cannot be relabelled or migrated automatically. Keep the complete installation for matching-version recovery and install adaptive World Builder 2 in a separate folder"
}

$PidPaths = [Collections.Generic.List[string]]::new()
foreach ($PidPath in @(
    (Join-Path $Workspace "run/server.pid"),
    (Join-Path $Workspace "run/client.pid")
)) {
    $PidPaths.Add($PidPath)
}
$ProjectsItem = Get-Item -LiteralPath $Projects -Force -ErrorAction SilentlyContinue
if ($ProjectsItem -and $ProjectsItem.PSIsContainer -and -not ($ProjectsItem.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
    Get-ChildItem -LiteralPath $Projects -Filter "*.pid" -File -Recurse -Force |
        Where-Object { $_.FullName -match '[\\/]run[\\/](?:server|client)\.pid$' } |
        ForEach-Object { $PidPaths.Add($_.FullName) }
}
foreach ($PidPath in $PidPaths) {
    if (Test-Path -LiteralPath $PidPath -PathType Leaf) {
        $PidText = (Get-Content -LiteralPath $PidPath -Raw).Trim()
        if ($PidText -match '^\d+$' -and (Get-Process -Id ([int]$PidText) -ErrorAction SilentlyContinue)) {
            Fail-Update "Close World Builder 2 before updating (active process $PidText)"
        }
    }
}

$UpdatesItem = Get-Item -LiteralPath $UpdatesDir -Force -ErrorAction SilentlyContinue
if (
    $UpdatesItem -and
    (($UpdatesItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -or -not $UpdatesItem.PSIsContainer)
) {
    Fail-Update "the updates path is unsafe; preserve it for review before retrying"
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
$PreserveStage = $false
try {
    $RawReleaseResponse = Invoke-RestMethod -Uri $ApiUrl -Headers @{ "User-Agent" = "RSC-World-Editor-V2-Updater" }
    $ReleaseResponse = @($RawReleaseResponse | ForEach-Object { $_ })
    $Release = Select-NewestV2Release $ReleaseResponse
    $LatestTag = [string]$Release.tag_name
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
    Invoke-WebRequest -UseBasicParsing -Uri $ArchiveAsset.browser_download_url -OutFile $ArchivePath -Headers @{ "User-Agent" = "RSC-World-Editor-V2-Updater" }
    Invoke-WebRequest -UseBasicParsing -Uri $ChecksumAsset.browser_download_url -OutFile $Checksums -Headers @{ "User-Agent" = "RSC-World-Editor-V2-Updater" }

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
    Assert-RequiredManagedFiles $DownloadedRecords "runtime/bin/java.exe"
    Assert-ApplicationAllowlist $PackageRoot $DownloadedRecords

    if ((Get-Content -LiteralPath (Join-Path $PackageRoot "VERSION.txt") -Raw).Trim() -cne $LatestVersion) {
        Fail-Update "downloaded package version does not match its release tag"
    }
    $DownloadedIdentity = Read-ReleaseIdentity (Join-Path $PackageRoot "RELEASE-IDENTITY.json") $LatestVersion $LatestTag
    if (
        (Get-Content -LiteralPath (Join-Path $PackageRoot "SOURCE-COMMIT.txt") -Raw).Trim() -cne $DownloadedIdentity.sourceCommit -or
        (Get-Content -LiteralPath (Join-Path $PackageRoot "RUNTIME-PROVIDER-COMMIT.txt") -Raw).Trim() -cne $DownloadedIdentity.runtimeProviderCommit
    ) {
        Fail-Update "downloaded package provenance does not match its v2 identity"
    }

    $OldManaged = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    $InstalledRecords | ForEach-Object { [void]$OldManaged.Add($_.Relative) }
    [void]$OldManaged.Add("PACKAGE-MANIFEST.sha256")
    foreach ($Record in $DownloadedRecords) {
        $Destination = Join-Path $RootDir ($Record.Relative.Replace('/', [IO.Path]::DirectorySeparatorChar))
        $DestinationItem = Get-Item -LiteralPath $Destination -Force -ErrorAction SilentlyContinue
        if ($DestinationItem -and -not $OldManaged.Contains($Record.Relative)) {
            Fail-Update "update would overwrite an unmanaged installed path: $($Record.Relative)"
        }
        $Ancestor = Split-Path -Parent $Record.Relative
        while ($Ancestor) {
            $AncestorPath = Join-Path $RootDir ($Ancestor.Replace('/', [IO.Path]::DirectorySeparatorChar))
            $AncestorItem = Get-Item -LiteralPath $AncestorPath -Force -ErrorAction SilentlyContinue
            if ($AncestorItem -and ($AncestorItem.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
                Fail-Update "update path crosses an installed reparse point: $Ancestor"
            }
            if (
                $AncestorItem -and
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
    foreach ($Record in $DownloadedRecords) {
        $Destination = Join-Path $RootDir ($Record.Relative.Replace('/', [IO.Path]::DirectorySeparatorChar))
        $DestinationParent = Split-Path -Parent $Destination
        if ($DestinationParent) {
            New-Item -ItemType Directory -Force -Path $DestinationParent | Out-Null
        }
        Copy-Item -LiteralPath $Record.FullName -Destination $Destination -Force
    }
    Copy-Item -LiteralPath $DownloadedManifestPath -Destination (Join-Path $RootDir "PACKAGE-MANIFEST.sha256") -Force
    $VerifiedRecords = @(Read-PackageManifest $RootDir (Join-Path $RootDir "PACKAGE-MANIFEST.sha256"))
    Assert-RequiredManagedFiles $VerifiedRecords "runtime/bin/java.exe"
    Assert-ApplicationAllowlist $RootDir $VerifiedRecords
    $VerifiedIdentity = Read-ReleaseIdentity (Join-Path $RootDir "RELEASE-IDENTITY.json") $LatestVersion $LatestTag
    if ((Get-Content -LiteralPath $VersionPath -Raw).Trim() -cne $LatestVersion) {
        Fail-Update "installed update version verification failed"
    }
    if (
        (Test-Path -LiteralPath $ProjectRegistry) -or
        (Test-Path -LiteralPath $ActiveProject) -or
        (Test-Path -LiteralPath $Projects)
    ) {
        $RegistryItem = Get-Item -LiteralPath $ProjectRegistry -Force -ErrorAction SilentlyContinue
        $ActiveItem = Get-Item -LiteralPath $ActiveProject -Force -ErrorAction SilentlyContinue
        $ProjectsItem = Get-Item -LiteralPath $Projects -Force -ErrorAction SilentlyContinue
        if (
            -not $RegistryItem -or $RegistryItem.PSIsContainer -or
            ($RegistryItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -or
            -not $ActiveItem -or $ActiveItem.PSIsContainer -or
            ($ActiveItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -or
            -not $ProjectsItem -or -not $ProjectsItem.PSIsContainer -or
            ($ProjectsItem.Attributes -band [IO.FileAttributes]::ReparsePoint)
        ) {
            Fail-Update "adaptive project state is incomplete or unsafe after the application update"
        }
        $CompatibilityJava = if ($env:WORLD_BUILDER_V2_COMPATIBILITY_JAVA) {
            $env:WORLD_BUILDER_V2_COMPATIBILITY_JAVA
        } else {
            Join-Path $RootDir "runtime/bin/java.exe"
		}
		& $CompatibilityJava -jar (Join-Path $RootDir "builder-runtime/launcher/world-builder-tools.jar") `
			open-project --installation-root $RootDir --target-root (Split-Path -Parent $RootDir) `
			--validate-only |
			Out-Null
        if ($LASTEXITCODE -ne 0) {
            Fail-Update "the selected adaptive project is incompatible with the updated runtime"
        }
    }
    $RollbackArmed = $false

    Write-Host "World Builder 2 updated successfully to $LatestVersion."
    if ((Test-Path -LiteralPath $Projects -PathType Container) -or (Test-Path -LiteralPath $Workspace -PathType Container)) {
        Write-Host "All adaptive projects, registries, exports, backups, receipts, diagnostics, settings, logs, and historical workspace state were preserved."
        Write-Host "The selected project passed the compatibility checks available in this runtime."
    }
} catch {
    $OriginalFailure = $_
    if ($RollbackArmed) {
        try {
            Remove-ManagedFiles $RootDir $DownloadedRecords
            $InstalledRecords | ForEach-Object {
                Assert-SafeManagedPath $RootDir $_.Relative
            }
            Get-ChildItem -LiteralPath $Backup -Force | ForEach-Object {
                Copy-Item -LiteralPath $_.FullName -Destination $RootDir -Recurse -Force
            }
            $RestoredRecords = @(Read-PackageManifest $RootDir (Join-Path $RootDir "PACKAGE-MANIFEST.sha256"))
            Assert-RequiredManagedFiles $RestoredRecords "runtime/bin/java.exe"
            Write-Warning "The previous World Builder 2 application files were restored."
            $RollbackArmed = $false
        } catch {
            $PreserveStage = $true
            throw "World Builder 2 update failed and automatic rollback could not fully restore the previous application. Preserve workspace/ and recovery staging at $Stage. Original failure: $($OriginalFailure.Exception.Message); rollback failure: $($_.Exception.Message)"
        }
    }
    throw $OriginalFailure
} finally {
    if (-not $PreserveStage -and $Stage -and (Test-Path -LiteralPath $Stage)) {
        Remove-Item -LiteralPath $Stage -Recurse -Force -ErrorAction SilentlyContinue
    }
    if (-not $PreserveStage) {
        Remove-Item -LiteralPath $LockDir -Force -ErrorAction SilentlyContinue
    }
}
