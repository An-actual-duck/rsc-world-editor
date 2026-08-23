# Project content bundle v1 compatibility fixture

`bundle/` is a complete canonical `project-local-custom-content-v1` runtime
input. It is synthetic and contains no user or server content.

Acceptance IDs:

- floor 31
- wall 219
- scenery 59
- NPC 846
- ground item 9000

The exact expected fingerprints are recorded in `bundle/manifest.json`.
Regenerate only into an empty directory, review all byte changes, and verify
the checked-in fixture with:

```bash
python3 scripts/generate-project-content-bundle-v1-fixture.py \
  --check tests/fixtures/project-content-bundle-v1/bundle
```
