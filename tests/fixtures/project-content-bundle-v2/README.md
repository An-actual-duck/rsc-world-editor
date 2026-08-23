# Project content bundle v2 compatibility fixture

`bundle/` is the frozen `project-local-custom-content-v2` producer/consumer
oracle. It retains floor 31, wall 219, scenery 59, and NPC 846 coverage. Ground
items 9000, 9001, and 9002 demonstrate ordinary beyond-packaged mappings to a
named custom-sprite entry, authentic sprite ID 417, and a named spritepack
entry. Their signed recolor masks are authoritative manifest data.

Frozen fingerprints:

- definition: `6a070461aaf4d8b304ae295e485c909bd04242017f63a539d7fa74d62872dcfe`
- assets: `2320bfd31effa33c0e8cc47ec919e881809f69599b5504c6369e547697f844bc`
- item visuals: `aa7c9deae89d9cda0497dad1bf00ac7f2f28b0143d127b584acecb9726f9ac6c`
- bundle: `44510eb65894689c510ef55072a3e5406dfae3821d6368c5b0a6869ce516a9e1`

Verify every checked-in byte and fingerprint with:

```bash
python3 scripts/generate-project-content-bundle-v2-fixture.py \
  --check tests/fixtures/project-content-bundle-v2/bundle
```
