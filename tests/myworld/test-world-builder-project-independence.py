#!/usr/bin/env python3
"""Guard World Editor and runtime-provider ownership from Spoiled Milk state."""

from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def main() -> None:
    agents = (ROOT / "AGENTS.md").read_text(encoding="utf-8")
    workspace_docs = (ROOT / "docs/AI-WORKSPACES.md").read_text(encoding="utf-8")
    development = (ROOT / "docs/DEVELOPMENT.md").read_text(encoding="utf-8")
    guide_source = (ROOT / "scripts/lib/ai-workspace-common.sh").read_text(
        encoding="utf-8"
    )
    manager = (ROOT / "scripts/ai-manager.sh").read_text(encoding="utf-8")
    workspace = (ROOT / "scripts/ai-workspace.sh").read_text(encoding="utf-8")
    checkout = (ROOT / "scripts/checkout-runtime-provider.sh").read_text(
        encoding="utf-8"
    )
    dependency_lock = (ROOT / "runtime-provider.lock").read_text(encoding="utf-8")
    ci_workflow = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
    v2_packager = (
        ROOT / "scripts/package-world-builder-v2-release.sh"
    ).read_text(encoding="utf-8")
    runtime_allowlist = (
        ROOT / "release/world-builder-v2/RUNTIME-ASSET-ALLOWLIST.txt"
    ).read_text(encoding="utf-8")
    normalized_agents = " ".join(agents.split())
    normalized_development = " ".join(development.split())

    for required in (
        "manage only the `rsc-world-editor` Git",
        "Never run `.runtime-provider/scripts/ai-manager.sh`",
        "does not create a World Editor task",
        "only when the user explicitly assigns a dependency-update task",
        "Never activate, inspect, or collect `/home/justin/Core-Framework-ai-*`",
        "`/home/justin/rsc-world-editor-runtime`",
    ):
        require(
            required in normalized_agents,
            f"AGENTS.md is missing independence rule: {required}",
        )

    require(
        "advances the pinned Spoiled Milk source" not in agents,
        "World Editor manager still treats Spoiled Milk advancement as routine work",
    )
    require(
        "Do not run collaboration scripts inside `.runtime-provider`" in workspace_docs,
        "workspace documentation does not isolate the dependency checkout",
    )
    require(
        "is never triggered by another project's activity"
        in normalized_development,
        "development routing still allows implicit cross-project synchronization",
    )
    require(
        "check-runtime-provider-parity.sh" not in v2_packager,
        "World Builder 2 packaging still requires repository source parity",
    )
    for forbidden in (
        "--layered-package",
        'cp -R "$RUNTIME_PROVIDER_ROOT/server/conf"',
        'cp -R "$RUNTIME_PROVIDER_ROOT/server/database"',
        'cp -R "$RUNTIME_PROVIDER_ROOT/Client_Base/Cache/video"',
        "spoiled-milk-package",
    ):
        require(
            forbidden not in v2_packager,
            f"World Builder 2 packaging still uses broad or world-specific input: {forbidden}",
        )
    require(
        "default-definition-catalog" in runtime_allowlist
        and "default-render-catalog" in runtime_allowlist
        and "/defs/locs/" not in runtime_allowlist
        and "Landscape.orsc" not in runtime_allowlist,
        "runtime allowlist is missing generic catalogs or admits world content",
    )
    require(
        "./scripts/checkout-runtime-provider.sh" in ci_workflow
        and "RUNTIME_PROVIDER_DIR: .runtime-provider" in ci_workflow
        and "run: ./scripts/test.sh" in ci_workflow,
        "routine CI no longer tests with the exact locked dependency checkout",
    )
    require(
        "check-runtime-provider-parity.sh" not in ci_workflow,
        "routine CI still requires repository source parity",
    )
    require(
        "RUNTIME_PROVIDER_REPOSITORY=https://github.com/An-actual-duck/rsc-world-editor-runtime.git"
        in dependency_lock
        and "RUNTIME_PROVIDER_REF=refs/heads/main" in dependency_lock,
        "runtime dependency is not owned by the independent provider main ref",
    )
    require(
        'fetch origin "$RUNTIME_PROVIDER_REF"' in checkout
        and "FETCH_HEAD^{commit}" in checkout,
        "dependency checkout does not verify its provider ref against RUNTIME_PROVIDER_COMMIT",
    )
    sync = (ROOT / "scripts/sync-from-runtime-provider.sh").read_text(
        encoding="utf-8"
    )
    require(
        "rsync" not in sync
        and "No World Builder-owned source was copied" in sync,
        "dependency adoption can still overwrite World Builder-owned source",
    )
    for explicit_tool in (
        ROOT / "scripts/check-runtime-provider-parity.sh",
        ROOT / "scripts/sync-from-runtime-provider.sh",
    ):
        require(
            explicit_tool.is_file(),
            f"explicit dependency-update tool is missing: {explicit_tool.name}",
        )
    require(
        "this worker belongs only to the RSC World Editor repository" in guide_source,
        "generated worker guides do not state the project boundary",
    )
    require(
        "ai_require_invocation_from_managed_worktree" in guide_source
        and "Refusing cross-project invocation" in guide_source,
        "collaboration scripts do not enforce their worktree boundary",
    )

    for script_name, source in (
        ("ai-manager.sh", manager),
        ("ai-workspace.sh", workspace),
    ):
        require(
            "/home/justin/Core-Framework" not in source
            and "Core-Framework-ai-" not in source,
            f"{script_name} contains a hard-coded Spoiled Milk worktree",
        )

    print("PASS: World Editor and its runtime provider are isolated from Spoiled Milk state")


if __name__ == "__main__":
    main()
