# World Builder 2 v0.4.0-alpha.2 validation — ACCEPTED

This record accepts one exact restricted pre-gate candidate as the validation
basis for World Builder 2 v0.4.0-alpha.2. It opens production packaging through
`release/world-builder-v2/RELEASE-READY`; it does not promote or reuse the
candidate archives. Production archives must be rebuilt from the later clean,
published gate commit.

## Acceptance

- Status: **ACCEPTED — RELEASE READY**
- Accepted on: **2026-08-24**
- Accepted by: **project owner**, with manager verification of the recorded
  automated evidence
- Restricted candidate World Editor commit:
  `f3cd405f74fac4045f3d73213b4f4b29093a65d5`
- Locked runtime-provider commit:
  `55c8a956b1dfe400f672fbabd9133f2c594b2130`
- Version: `v0.4.0-alpha.2`
- Product identity: `rsc-world-editor-v2`
- World-source identity: `target-adaptive-v1`

This follow-up alpha release adds complete neutral NPC-provider consumption,
including rich animation and archive bindings for target extension NPCs, and a
fail-closed compatibility check that prevents a copied provider package from
being silently reused after its target definitions, placement set, or sprite
archives change. The broader format-aware discovery initiative remains a
subsequent development objective.

## Exact candidate artifacts

| Artifact | SHA-256 |
| --- | --- |
| `rsc-world-editor-v2-0.4.0-alpha.2-linux-x64.zip` | `5ab2fe699ba438f4788f311a6f2d3173209ffde1df0062d19c8d6b5fc83cafea` |
| `rsc-world-editor-v2-0.4.0-alpha.2-windows-x64.zip` | `241a3496f7817c3e766d4dc160c74084fcbcfa26dfe6774735735875329327b8` |
| `SHA256SUMS.txt` | `deb98a5c967122f8cf47622d0d94e525f2a280461a2c0476d3fc804ae276de13` |
| `candidate-archive-inspection.json` | `336b01efa56bc2cd1cb6fa73763983c6b2fef9918592cf2dd76fc99b38cbfe74` |

Independent inspection reported `automated-archive-inspection-passed`.

| Platform | Inner manifest SHA-256 | Reviewed JRE inventory SHA-256 | Files |
| --- | --- | --- | ---: |
| Linux x64 | `ce532725454a55dcff577d8938705c7894ed1885da28fe5ed7d341c96d9f6f09` | `56e02eae89660c0d7baef03b276f2c8f6ef1749d79403c074dc41ea8f3403c9e` | 410 |
| Windows x64 | `aced3daaa14fb26643fbaeddbf949c28dce7d2b4a2547b0984bc81a02f42092e` | `9aaf15bca3b380b3b9099d3097182d85cbc83003d57d0844c8fc36f7c25b2967` | 468 |

The inspector SHA-256 was
`0e067b21b4729070f9fbc55c3693cc7e06c534616953b8ddb25e5f09678ffb55`.
It verified clean published sources, the exact runtime lock, exhaustive
manifests, reviewed JRE bytes and modes, application allowlists, an empty
Builder seed, the production runtime marker, and absence of bundled world,
creator, credential, project, log, receipt, and backup data.

## Automated evidence

| Check | Result |
| --- | --- |
| `git diff --check` | PASS |
| `WORLD_BUILDER_NO_TERMINAL=1 ./scripts/test.sh` | PASS |
| Restricted candidate build from clean published `main` | PASS |
| Independent inspection of both external candidate archives | PASS |

The complete suite covered portable provider discovery and resilient fallback,
neutral item and NPC provider contracts, target/provider compatibility,
packed conversion, layered adoption, standalone creation, placement families,
project save/reopen, export/import/undo/recovery, transactions, updater
rollback, packaging, release gates, and product independence. Five native
PowerShell updater cases were skipped because a reviewed `pwsh` executable was
unavailable; their static and cross-platform transaction coverage passed.

## Owner-native report

The owner requested textual validation without screenshots.

- The exact independently inspected Linux candidate was extracted into a fresh
  temporary installation and launched visibly.
- The owner exercised the current source/provider flow and loaded the target
  map with its custom NPC content.
- The owner confirmed that the exact candidate worked correctly and accepted
  it for public release.
- The candidate launcher and client exited cleanly after validation.

No screenshot was captured or judged by an AI session. Source discovery and
project creation did not export changes back into the selected server.

## Accepted limitations

- Native Windows application launch and native PowerShell updater execution
  were not performed on this Linux host. Windows archive, Java, launcher,
  application allowlist, updater, rollback, and cross-platform transaction
  validation passed.
- This release supports explicit portable provider packages and recognized
  layouts. Server projects with unfamiliar declarative formats may still need
  a generated provider package; broader format-aware discovery and guided
  adaptation are planned for the next development cycle.
- The compatibility check intentionally blocks reuse when relevant target
  definition, placement, or sprite evidence changes. The user must refresh the
  provider rather than risk silently loading stale custom content.
- Optional custom visuals that are missing, unfamiliar, malformed, or unsafe
  use deterministic placeholders with project-local actionable warnings; they
  do not execute target code or discard entity identity.

## Production rule

The candidate archives listed above are validation evidence only. They must not
be copied, renamed, uploaded, or promoted as production release files.
Production archives must be rebuilt after this record and `RELEASE-READY` are
committed and published on clean `main`, using the exact locked runtime and
reviewed JRE inputs.

