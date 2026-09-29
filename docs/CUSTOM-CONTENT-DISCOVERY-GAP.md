# Custom NPC visual discovery: corrective investigation

Status: investigation complete; the generic correction below is not implemented.
The alpha.3 identity-specific direction-sheet adapter is a workaround, not
completion of the custom-content discovery objective. It must be replaced.

## Product requirement

Preservation is the known baseline. Custom content and overrides must be derived
from the selected target, including changes to baseline IDs. Finding a definition
does not prove that its appearance has been captured. NPC names, IDs, artwork
paths, and sheet dimensions must not be a World Builder-owned content catalog.
Naga is an external regression example, not a production recognition rule.

## Confirmed missing links

1. `WorldBuilderSupplementalNpcDefinitions` inventories supplemental JSON and
   normalizes its records. That accounts for the correct names and IDs in lookup.
   A server record can contain placeholder visual fields which the matching
   client replaces after loading additional assets.
2. `WorldBuilderNpcDefinitionProvider.consume` returns unchanged when required
   IDs already fit the combined declarative catalog. Its subsequent loop only
   appends missing definitions. It cannot enrich an existing definition's
   appearance, including an unplaced custom NPC.
3. The rich provider selection is limited to placed extensions beyond the
   declarative boundary. Its asset path covers paired authentic/custom archives,
   not a general external NPC frame dependency graph.
4. In the authorized reference's tracked source, `FinalNpcDefinitionsProbe`
   reads definitions after `EntityHandler.load(true)`. External sheet loading and
   visual activation happen later in client startup. Even an export from that
   probe does not capture those final visual associations. The tracked provider
   contains no record for the tested NPC.
5. The target also has tracked provenance metadata containing NPC IDs, asset
   keys, hashes, columns, and image heights. Rendering semantics additionally
   reside in the external loader and its callers. Discovery did not join these
   inputs. Merely copying more PNG files would still omit their interpretation.
6. Alpha.3 supplied those missing associations from an eight-NPC table in the
   Editor. That reproduces the example but bypasses the actual discovery gap.

Only tracked files in the authorized reference copy were inspected. No target
code was executed and no target state was changed.

## Replacement implementation

- Separate definition completeness from visual completeness. Resolve appearance
  for every discovered NPC, irrespective of placement or declarative ID range.
  Preserve server gameplay fields; apply verified presentation metadata only.
- Introduce a target-derived visual-source inventory binding definition
  provenance and NPC layers to animations, exact asset paths/hashes, frame
  layout/order, masks, and bounds. Resolve and seal every referenced dependency.
- Extend the existing neutral provider bridge for full-catalog visual records
  and external frame sources. Retain legacy provider readers as bounded input
  adapters, without retaining their placement-only restriction for new input.
- Read existing supported metadata structurally. Where client code supplies
  missing semantics, use bounded static extraction of supported constructs or
  an explicit neutral descriptor. Do not guess NPC associations from filenames,
  copy runtime content tables into the Editor, or execute arbitrary target code.
- Report missing or contradictory visual evidence separately from successful
  definition discovery. A valid baseline animation number alone cannot certify
  that a custom appearance was captured.
- Compile resolved records into the existing portable animation registry and
  lossless RGB frame support. Remove the identity-specific direction-sheet table.

## Acceptance

Tests must demonstrate existing-definition visual enrichment, unplaced custom
NPCs, customized baseline IDs, and arbitrary unrelated names/IDs. Change paths,
dimensions and metadata independently; mappings must follow target evidence.
Two targets reusing an ID must retain their different definitions and visuals.
Include missing/ambiguous mappings, stale hashes, discovery drift, unsafe paths,
and source-independent project reopen. Preservation-only intake must remain
unchanged. Finally render a fresh capture of the external Naga test case in both
sprite modes. Passing that example alone is insufficient.

No replacement owner candidate should be described as completing this objective
until the generic discovery path and these acceptance cases pass.
