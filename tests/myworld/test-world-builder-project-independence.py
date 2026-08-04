#!/usr/bin/env python3
"""Guard the World Editor manager/worker boundary from Spoiled Milk state."""

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
    checkout = (ROOT / "scripts/checkout-core-framework.sh").read_text(
        encoding="utf-8"
    )
    dependency_lock = (ROOT / "core-framework.lock").read_text(encoding="utf-8")
    ci_workflow = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
    v2_packager = (
        ROOT / "scripts/package-world-builder-v2-release.sh"
    ).read_text(encoding="utf-8")
    normalized_agents = " ".join(agents.split())
    normalized_development = " ".join(development.split())

    for required in (
        "manage only the `rsc-world-editor` Git",
        "Never run `.core-framework/scripts/ai-manager.sh`",
        "does not create a World Editor task",
        "only when the user explicitly assigns a dependency-update task",
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
        "Do not run collaboration scripts inside `.core-framework`" in workspace_docs,
        "workspace documentation does not isolate the dependency checkout",
    )
    require(
        "is never triggered by another project's activity"
        in normalized_development,
        "development routing still allows implicit cross-project synchronization",
    )
    require(
        "check-core-parity.sh" not in v2_packager,
        "World Builder 2 packaging still requires repository source parity",
    )
    require(
        "./scripts/checkout-core-framework.sh" in ci_workflow
        and "CORE_FRAMEWORK_DIR: .core-framework" in ci_workflow
        and "run: ./scripts/test.sh" in ci_workflow,
        "routine CI no longer tests with the exact locked dependency checkout",
    )
    require(
        "check-core-parity.sh" not in ci_workflow,
        "routine CI still requires repository source parity",
    )
    require(
        "CORE_REF=refs/heads/world-builder/runtime/" in dependency_lock,
        "runtime dependency is not retained through a durable provider ref",
    )
    require(
        'fetch origin "$CORE_REF"' in checkout
        and "FETCH_HEAD^{commit}" in checkout,
        "dependency checkout does not verify its provider ref against CORE_COMMIT",
    )
    sync = (ROOT / "scripts/sync-from-core-framework.sh").read_text(
        encoding="utf-8"
    )
    require(
        "rsync" not in sync
        and "No World Builder-owned source was copied" in sync,
        "dependency adoption can still overwrite World Builder-owned source",
    )
    for explicit_tool in (
        ROOT / "scripts/check-core-parity.sh",
        ROOT / "scripts/sync-from-core-framework.sh",
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

    print("PASS: World Editor manager and workers are isolated from Spoiled Milk state")


if __name__ == "__main__":
    main()
