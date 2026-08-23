# Project content bundle v2 compatibility fixture

`bundle/` is the frozen `project-local-custom-content-v2` producer/consumer
oracle. It retains floor 31, wall 219, scenery 59, and NPC 846 coverage. Ground
items 9000, 9001, and 9002 demonstrate ordinary beyond-packaged mappings to a
runtime-compatible GZIP OSAR `items/0` entry, authentic sprite ID 417, and a
GZIP OSAR spritepack `GUI/0` entry. Both named entries contain real palette
pixels. Their signed recolor masks are authoritative manifest data.

Frozen fingerprints:

- definition: `f97a96299023e4cf1d738c1f3520af0c2e4339ed95aab952814832cc77e52baf`
- assets: `e0ab18b793a91db852557689b9734eeb1d459e216be61b902d75a69e6e2c5bfa`
- item visuals: `f9aaf43d6cac1c96bbf10d129e1976f9638562036e1b187f684e7219a7cda8d3`
- bundle: `88542556c723be2c4312f48eb2b42f65fb08a169edd21afa55eda075c6d4aa8b`

Verify every checked-in byte and fingerprint with:

```bash
python3 scripts/generate-project-content-bundle-v2-fixture.py \
  --check tests/fixtures/project-content-bundle-v2/bundle
```
