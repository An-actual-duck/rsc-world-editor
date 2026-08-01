# World Builder 2 v0.1.0-alpha.1 validation

Accepted on 2026-08-01 for publication as the first World Builder 2
prerelease. The frozen v1.1.0 product and update channel remain unchanged.

## Accepted candidate

- World Editor source: `b0f36a42b33605e14df0e9b677b74dc606b30def`
- Locked runtime source: `026aab5c028aa9ecf6e78d382a4871e6ed56c3f7`
- Linux archive SHA-256:
  `1a8392c6c11c86d545dddfe93b6d16c9eefb04021c0efbba447bff69de92ef10`
- Windows archive SHA-256:
  `a359c45df88f8fdc85e747c782fb9bebc154a883e5d7e74c4c6f9d25911fd0d7`

These are the exact restricted pre-gate candidate hashes. Production artifacts
are rebuilt from the clean published gate commit; their exact hashes are
published in the release's `SHA256SUMS.txt`.

## Runtime inputs

- Eclipse Temurin JRE 17.0.20+8 Linux x64,
  `OpenJDK17U-jre_x64_linux_hotspot_17.0.20_8.tar.gz`, SHA-256
  `ef491a51a46ef90cc47fbc4abb219fde32483ff91be5ec66ddc896df43524b27`.
- Eclipse Temurin JRE 17.0.20+8 Windows x64,
  `OpenJDK17U-jre_x64_windows_hotspot_17.0.20_8.zip`, SHA-256
  `7fe2324cc5901a89aaa0e8dc232075c59f2aca5270c533bdb1b861a5274af834`.
- LWJGL 3.3.4 modules `lwjgl`, `lwjgl-glfw`, and `lwjgl-opengl`, with exact
  Linux x64 and Windows x64 native classifiers.
- PowerShell 7.4.18 Linux x64 was used for Windows updater transaction tests;
  archive SHA-256
  `21962bfc832119fc8a58e5eba24bc48f0d31707ce94a4e48a90178a223eba619`.

## Results

- `CORE_FRAMEWORK_DIR=.core-framework WORLD_BUILDER_PWSH=/tmp/world-builder-pwsh-5ivbJw/runtime/pwsh ./scripts/test.sh`
  passed, including all Linux and PowerShell updater success, refusal, and
  injected-rollback tests.
- Both real archives passed outer SHA-256 verification, ZIP integrity,
  exhaustive `PACKAGE-MANIFEST.sha256` verification, exact identity and
  provenance checks, platform-runtime checks, and complete inventory checks
  outside the source tree.
- Linux first launch, clean shutdown, reopen, isolated save, export, import,
  normal private-server load, undo, and injected import/undo recovery were
  exercised against disposable targets. The owner visually confirmed the
  native application working correctly.
- Real Linux and PowerShell v2-to-v2 archive updates preserved workspaces and
  unmanaged files byte-for-byte. Injected installation failures restored the
  prior managed application and workspace on both updater implementations.
- Frozen v1, draft, malformed, wrong-product, downgrade, and legacy-workspace
  records were refused by the v2 channel tests.
- The packaged Windows command path was reviewed and its bundled Java runtime
  prepared and saved an isolated project under Wine. The corrected import
  preview reached its exact `IMPORT` confirmation and cancellation left the
  target unchanged.

## Accepted limitation

The owner accepted publication without a native Windows-host launch. Wine's
filesystem denied Java's atomic replacement of `server/myworld.conf` during a
transactional apply. The operation failed closed: the original configuration
hash was restored, the target package remained absent, and the pending receipt
and staging directory were retained for recovery review. This Wine result is
not represented as native Windows validation. Windows packaging, launcher
identity, Java runtime, preview safety, PowerShell updater success, and updater
rollback behavior remain covered by code review and automated validation.

The owner accepted this scope for an alpha prerelease and requested publication.
