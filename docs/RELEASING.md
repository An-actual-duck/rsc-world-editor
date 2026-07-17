# Releasing

Release production requires clean, published `main` in this repository and a
clean checkout at the exact commit recorded in `core-framework.lock`.

## Inputs

- Linux x64 JRE 17+ directory
- Windows x64 JRE 17+ directory
- A clean locked Core-Framework checkout
- Confirmed redistribution terms for packaged visual assets

## Build

```bash
./scripts/package-release.sh \
  --version v1.1.0 \
  --core-framework /path/to/open-rsc-spoiled-milk \
  --linux-jre /path/to/linux-jre \
  --windows-jre /path/to/windows-jre \
  --assets-cleared
```

Artifacts are written under `output/releases/world-builder/<version>/` with a
`SHA256SUMS.txt` file. Packages must record both the World Editor source commit
and the Core-Framework runtime commit.

Before publishing:

1. Run `./scripts/test.sh` and `./scripts/check-core-parity.sh`.
2. Extract both archives outside all source repositories.
3. Verify a clean first launch and isolated save on each supported platform.
4. Verify export, import preview, confirmed import, and exact undo against a
   disposable compatible private server.
5. Confirm the archives contain no workspace, credential, database, logs,
   backups, receipts, or generated endpoint state.
6. Create a normal semantic-version tag and GitHub release in this repository.
7. Upload both archives and `SHA256SUMS.txt`.

Publishing a World Editor release does not authorize changing or restarting a
public Spoiled Milk server.
