package com.openrsc.worldbuilder;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Initial read-only command-line boundary for World Builder project discovery. */
public final class WorldBuilderCli {
	private WorldBuilderCli() {
	}

	public static void main(String[] args) {
		int result = run(args);
		if (result != 0) {
			System.exit(result);
		}
	}

	static int run(String[] args) {
		if (args.length == 0 || "--help".equals(args[0]) || "-h".equals(args[0])) {
			usage();
			return args.length == 0 ? 2 : 0;
		}
		if ("discover-adaptive".equals(args[0])) {
			return discoverAdaptive(args);
		}
		if ("validate-current-runtime-contract".equals(args[0])) {
			return validateCurrentRuntimeContract(args);
		}
		if ("classify-current-target".equals(args[0])) {
			return classifyCurrentTarget(args);
		}
		if ("discover-legacy-landscape".equals(args[0])) {
			return discoverLegacyLandscape(args);
		}
		if ("discover-item-provider".equals(args[0])) {
			return discoverItemProvider(args);
		}
		if ("import-item-provider".equals(args[0])) {
			return importItemProvider(args);
		}
		if ("export-item-provider-diagnostic".equals(args[0])) {
			return exportItemProviderDiagnostic(args);
		}
		if ("reset-item-provider-cache".equals(args[0])) {
			return resetItemProviderCache(args);
		}
		if ("convert-packed".equals(args[0])) {
			return convertPacked(args);
		}
		if ("create-project".equals(args[0])) {
			return createProject(args);
		}
		if ("create-migrated-project".equals(args[0])) {
			return createMigratedProject(args);
		}
		if ("list-projects".equals(args[0])) {
			return listProjects(args);
		}
		if ("select-project".equals(args[0])) {
			return selectProject(args);
		}
		if ("open-project".equals(args[0])) {
			return openProject(args);
		}
		if ("save-project".equals(args[0])) {
			return saveProject(args);
		}
		if ("region-copy".equals(args[0])) {
			return regionCopy(args, false);
		}
		if ("region-cut-preview".equals(args[0])) {
			return regionCopy(args, true);
		}
		if ("region-cut-apply".equals(args[0])) {
			return regionCutApply(args);
		}
		if ("region-import".equals(args[0])) {
			return regionImportExport(args, true);
		}
		if ("region-export".equals(args[0])) {
			return regionImportExport(args, false);
		}
		if ("region-paste-preview".equals(args[0])) {
			return regionPaste(args, false);
		}
		if ("region-paste-apply".equals(args[0])) {
			return regionPaste(args, true);
		}
		if ("region-paste-undo".equals(args[0])) {
			return regionPasteUndo(args);
		}
		if ("run-adaptive-project".equals(args[0])) {
			return runAdaptiveProject(args);
		}
		if ("export-adaptive".equals(args[0])) {
			return exportAdaptive(args);
		}
		if ("export-active-adaptive".equals(args[0])) {
			return exportActiveAdaptive(args);
		}
		if ("import-adaptive".equals(args[0])) {
			return importAdaptive(args);
		}
		if ("upgrade-target-runtime".equals(args[0])) {
			return upgradeTargetRuntime(args);
		}
		if ("launch-adaptive".equals(args[0])) {
			return launchAdaptive(args);
		}
		if ("desktop-launch".equals(args[0])) {
			return desktopLaunch(args);
		}
		if ("import-active-adaptive".equals(args[0])) {
			return importActiveAdaptive(args);
		}
		if ("upgrade-active-target-runtime".equals(args[0])) {
			return upgradeActiveTargetRuntime(args);
		}
		if ("recover-adaptive".equals(args[0])) {
			return recoverAdaptive(args);
		}
		if ("recover-active-adaptive".equals(args[0])) {
			return recoverActiveAdaptive(args);
		}
		if ("prepare".equals(args[0])) {
			return prepare(args);
		}
		if ("launch".equals(args[0])) {
			int prepared = prepare(args);
			return prepared == 0 ? runPrepared(args, true) : prepared;
		}
		if ("run".equals(args[0])) {
			return runPrepared(args, false);
		}
		if ("create-level".equals(args[0])) {
			return createLevel(args);
		}
		if ("export".equals(args[0])) {
			return export(args);
		}
		if ("import".equals(args[0])) {
			return importChanges(args);
		}
		if ("undo-import".equals(args[0])) {
			return undoImport(args);
		}
		if ("export-import".equals(args[0])) {
			return exportImport(args);
		}
		if ("undo-latest-import".equals(args[0])) {
			return undoLatestImport(args);
		}
		if (!"discover".equals(args[0])) {
			System.err.println("ERROR: Unsupported World Builder command: " + args[0]);
			usage();
			return 2;
		}

		Path root = null;
		String config = null;
		String expectedContent = null;
		for (int index = 1; index < args.length; index++) {
			String argument = args[index];
			if ("--server-root".equals(argument) && index + 1 < args.length) {
				root = Paths.get(args[++index]);
			} else if ("--config".equals(argument) && index + 1 < args.length) {
				config = args[++index];
			} else if ("--expected-content-sha256".equals(argument) && index + 1 < args.length) {
				expectedContent = args[++index];
			} else {
				System.err.println("ERROR: Unknown or incomplete argument: " + argument);
				usage();
				return 2;
			}
		}
		if (root == null) {
			System.err.println("ERROR: --server-root is required.");
			usage();
			return 2;
		}

		try {
			WorldBuilderDiscoveryResult discovered =
				new WorldBuilderDiscovery().discover(root, config, expectedContent);
			System.out.print(discovered.toJson());
			return 0;
		} catch (WorldBuilderDiscoveryException refusal) {
			System.err.println("ERROR: " + refusal.getMessage());
			return 3;
		}
	}

	private static int discoverItemProvider(String[] args) {
		Path installation = null;
		Path source = null;
		for (int index = 1; index < args.length; index++) {
			if ("--installation-root".equals(args[index]) && index + 1 < args.length) {
				installation = Paths.get(args[++index]);
			} else if ("--source-root".equals(args[index]) && index + 1 < args.length) {
				source = Paths.get(args[++index]);
			} else {
				System.err.println("ERROR: Unknown or incomplete argument: " + args[index]);
				return 2;
			}
		}
		if (installation == null || source == null) {
			System.err.println("ERROR: discover-item-provider requires --installation-root and --source-root.");
			return 2;
		}
		try {
			System.out.print(new WorldBuilderPortableProvider().discover(
				source, installation).toJson());
			return 0;
		} catch (Exception failure) {
			System.err.println("ERROR: Item provider discovery failed: " + failure.getMessage());
			return 4;
		}
	}

	private static int importItemProvider(String[] args) {
		Path installation = null;
		Path source = null;
		Path mapping = null;
		Path definitions = null;
		Path authentic = null;
		Path custom = null;
		Path spritepacks = null;
		Path externalItems = null;
		for (int index = 1; index < args.length; index++) {
			String argument = args[index];
			if (index + 1 >= args.length) {
				System.err.println("ERROR: Incomplete argument: " + argument);
				return 2;
			}
			Path value = Paths.get(args[++index]);
			if ("--installation-root".equals(argument)) installation = value;
			else if ("--source-root".equals(argument)) source = value;
			else if ("--item-visuals".equals(argument)) mapping = value;
			else if ("--definitions".equals(argument)) definitions = value;
			else if ("--authentic-archive".equals(argument)) authentic = value;
			else if ("--custom-archive".equals(argument)) custom = value;
			else if ("--spritepacks".equals(argument)) spritepacks = value;
			else if ("--external-items".equals(argument)) externalItems = value;
			else {
				System.err.println("ERROR: Unknown argument: " + argument);
				return 2;
			}
		}
		if (installation == null || source == null
			|| mapping == null && definitions == null) {
			System.err.println("ERROR: import-item-provider requires --installation-root, "
				+ "--source-root, and either --item-visuals or --definitions.");
			return 2;
		}
		try {
			WorldBuilderPortableProvider.GuidedSelection selection =
				new WorldBuilderPortableProvider.GuidedSelection(mapping, definitions,
					authentic, custom, spritepacks, externalItems);
			System.out.print(new WorldBuilderPortableProvider().publishGuided(
				installation, source, selection).toJson());
			return 0;
		} catch (Exception failure) {
			System.err.println("ERROR: Item provider import failed: " + failure.getMessage());
			return 4;
		}
	}

	private static int exportItemProviderDiagnostic(String[] args) {
		Path installation = null;
		Path source = null;
		for (int index = 1; index < args.length; index++) {
			if ("--installation-root".equals(args[index]) && index + 1 < args.length) {
				installation = Paths.get(args[++index]);
			} else if ("--source-root".equals(args[index]) && index + 1 < args.length) {
				source = Paths.get(args[++index]);
			} else {
				System.err.println("ERROR: Unknown or incomplete argument: " + args[index]);
				return 2;
			}
		}
		if (installation == null || source == null) {
			System.err.println("ERROR: export-item-provider-diagnostic requires "
				+ "--installation-root and --source-root.");
			return 2;
		}
		try {
			WorldBuilderAdaptiveDiscoveryReport report =
				new WorldBuilderAdaptiveDiscovery().discover(source, null);
			Path exported = new WorldBuilderPortableProvider().exportDiagnostic(
				installation, source, report.fingerprintSha256());
			Map<String,Object> result = new LinkedHashMap<String,Object>();
			result.put("schemaVersion", Long.valueOf(1L));
			result.put("manifestType", "world-builder-provider-diagnostic-export");
			result.put("diagnosticPath", exported.toString());
			System.out.print(WorldBuilderJsonDocuments.pretty(result));
			return 0;
		} catch (Exception failure) {
			System.err.println("ERROR: Provider diagnostic export failed: "
				+ failure.getMessage());
			return 4;
		}
	}

	private static int resetItemProviderCache(String[] args) {
		Path installation = null;
		Path source = null;
		String confirmation = null;
		for (int index = 1; index < args.length; index++) {
			if ("--installation-root".equals(args[index]) && index + 1 < args.length) {
				installation = Paths.get(args[++index]);
			} else if ("--source-root".equals(args[index]) && index + 1 < args.length) {
				source = Paths.get(args[++index]);
			} else if ("--confirm".equals(args[index]) && index + 1 < args.length) {
				confirmation = args[++index];
			} else {
				System.err.println("ERROR: Unknown or incomplete argument: " + args[index]);
				return 2;
			}
		}
		if (installation == null || source == null || confirmation == null) {
			System.err.println("ERROR: reset-item-provider-cache requires "
				+ "--installation-root, --source-root, and --confirm \""
				+ WorldBuilderPortableProvider.CACHE_RESET_CONFIRMATION + "\".");
			return 2;
		}
		try {
			System.out.print(new WorldBuilderPortableProvider().resetCache(
				installation, source, confirmation).toJson());
			return 0;
		} catch (Exception failure) {
			System.err.println("ERROR: Provider cache reset failed: "
				+ failure.getMessage());
			return 4;
		}
	}

	private static int createProject(String[] args) {
		Path installation = null;
		Path runtime = null;
		Path target = null;
		Path report = null;
		Path itemVisualMappings = null;
		boolean developmentTerrainSeed = false;
		String displayName = null;
		String confirmation = null;
		int port = 0;
		for (int index = 1; index < args.length; index++) {
			String argument = args[index];
			if ("--installation-root".equals(argument) && index + 1 < args.length) {
				installation = Paths.get(args[++index]);
			} else if ("--runtime-root".equals(argument) && index + 1 < args.length) {
				runtime = Paths.get(args[++index]);
			} else if ("--target-root".equals(argument) && index + 1 < args.length) {
				target = Paths.get(args[++index]);
			} else if ("--discovery-report".equals(argument)
				&& index + 1 < args.length) {
				report = Paths.get(args[++index]);
			} else if ("--item-visual-mappings".equals(argument)
				&& index + 1 < args.length) {
				itemVisualMappings = Paths.get(args[++index]);
			} else if ("--development-terrain-seed".equals(argument)
				&& !developmentTerrainSeed) {
				developmentTerrainSeed = true;
			} else if ("--display-name".equals(argument) && index + 1 < args.length) {
				displayName = args[++index];
			} else if ("--port".equals(argument) && index + 1 < args.length) {
				Integer parsed = parseIntOption("--port", args[++index]);
				if (parsed == null) return 2;
				port = parsed.intValue();
			} else if ("--confirm".equals(argument) && index + 1 < args.length) {
				confirmation = args[++index];
			} else {
				System.err.println("ERROR: Unknown or incomplete argument: " + argument);
				usage();
				return 2;
			}
		}
		if (installation == null || runtime == null || report == null
			|| displayName == null || port == 0 || confirmation == null) {
			System.err.println("ERROR: create-project requires --installation-root, "
				+ "--runtime-root, --discovery-report, --display-name, --port, "
				+ "and --confirm CREATE. --target-root is required for a target-backed report.");
			usage();
			return 2;
		}
		try {
			WorldBuilderAdaptiveProjectLifecycle.ProjectResult created =
				new WorldBuilderAdaptiveProjectLifecycle().create(
					installation, runtime, target, report, displayName, port, confirmation,
					itemVisualMappings, developmentTerrainSeed);
			System.out.print(created.toJson());
			return 0;
		} catch (WorldBuilderContractException refusal) {
			return adaptiveRefusal(refusal);
		} catch (Exception failure) {
			System.err.println("ERROR: Adaptive project creation failed before completion: "
				+ failure.getMessage());
			return 4;
		}
	}

	private static int createMigratedProject(String[] args) {
		Path installation = null;
		Path runtime = null;
		Path target = null;
		Path selectedReport = null;
		Path legacyReport = null;
		Path itemVisualMappings = null;
		String displayName = null;
		String confirmation = null;
		int port = 0;
		boolean retirementRequested = false;
		for (int index = 1; index < args.length; index++) {
			String argument = args[index];
			if ("--installation-root".equals(argument) && index + 1 < args.length) {
				installation = Paths.get(args[++index]);
			} else if ("--runtime-root".equals(argument) && index + 1 < args.length) {
				runtime = Paths.get(args[++index]);
			} else if ("--target-root".equals(argument) && index + 1 < args.length) {
				target = Paths.get(args[++index]);
			} else if ("--discovery-report".equals(argument)
				&& index + 1 < args.length) {
				selectedReport = Paths.get(args[++index]);
			} else if ("--legacy-discovery-report".equals(argument)
				&& index + 1 < args.length) {
				legacyReport = Paths.get(args[++index]);
			} else if ("--item-visual-mappings".equals(argument)
				&& index + 1 < args.length) {
				itemVisualMappings = Paths.get(args[++index]);
			} else if ("--retire-legacy-landscape".equals(argument)
				&& !retirementRequested) {
				retirementRequested = true;
			} else if ("--display-name".equals(argument) && index + 1 < args.length) {
				displayName = args[++index];
			} else if ("--port".equals(argument) && index + 1 < args.length) {
				Integer parsed = parseIntOption("--port", args[++index]);
				if (parsed == null) return 2;
				port = parsed.intValue();
			} else if ("--confirm".equals(argument) && index + 1 < args.length) {
				confirmation = args[++index];
			} else {
				System.err.println("ERROR: Unknown or incomplete argument: " + argument);
				usage();
				return 2;
			}
		}
		if (installation == null || runtime == null || target == null
			|| selectedReport == null || legacyReport == null || displayName == null
			|| port == 0 || confirmation == null) {
			System.err.println("ERROR: create-migrated-project requires --installation-root, "
				+ "--runtime-root, --target-root, --discovery-report, "
				+ "--legacy-discovery-report, --display-name, --port, and --confirm CREATE.");
			usage();
			return 2;
		}
		try {
			WorldBuilderAdaptiveProjectLifecycle.ProjectResult created =
				new WorldBuilderAdaptiveProjectLifecycle().createMigrated(
					installation, runtime, target, selectedReport, legacyReport,
					displayName, port, confirmation, itemVisualMappings,
					retirementRequested);
			System.out.print(created.toJson());
			return 0;
		} catch (WorldBuilderContractException refusal) {
			return adaptiveRefusal(refusal);
		} catch (Exception failure) {
			System.err.println("ERROR: Migrated project creation failed before completion: "
				+ failure.getMessage());
			return 4;
		}
	}

	private static int listProjects(String[] args) {
		Path installation = singlePathOption(args, "--installation-root",
			"list-projects");
		if (installation == null) return 2;
		try {
			System.out.print(new WorldBuilderAdaptiveProjectLifecycle().list(installation));
			return 0;
		} catch (WorldBuilderContractException refusal) {
			return adaptiveRefusal(refusal);
		} catch (Exception failure) {
			System.err.println("ERROR: Could not list adaptive projects: "
				+ failure.getMessage());
			return 4;
		}
	}

	private static int selectProject(String[] args) {
		Path installation = null;
		String projectId = null;
		for (int index = 1; index < args.length; index++) {
			if ("--installation-root".equals(args[index]) && index + 1 < args.length) {
				installation = Paths.get(args[++index]);
			} else if ("--project-id".equals(args[index]) && index + 1 < args.length) {
				projectId = args[++index];
			} else {
				System.err.println("ERROR: Unknown or incomplete argument: " + args[index]);
				return 2;
			}
		}
		if (installation == null || projectId == null) {
			System.err.println("ERROR: select-project requires --installation-root and --project-id.");
			return 2;
		}
		try {
			System.out.print(new WorldBuilderAdaptiveProjectLifecycle()
				.select(installation, projectId).toJson());
			return 0;
		} catch (WorldBuilderContractException refusal) {
			return adaptiveRefusal(refusal);
		} catch (Exception failure) {
			System.err.println("ERROR: Could not select adaptive project: "
				+ failure.getMessage());
			return 4;
		}
	}

	private static int openProject(String[] args) {
		Path installation = null;
		Path target = null;
		boolean validateOnly = false;
		for (int index = 1; index < args.length; index++) {
			if ("--installation-root".equals(args[index]) && index + 1 < args.length) {
				installation = Paths.get(args[++index]);
			} else if ("--target-root".equals(args[index]) && index + 1 < args.length) {
				target = Paths.get(args[++index]);
			} else if ("--validate-only".equals(args[index])) {
				validateOnly = true;
			} else {
				System.err.println("ERROR: Unknown or incomplete argument: " + args[index]);
				return 2;
			}
		}
		if (installation == null) {
			System.err.println("ERROR: open-project requires --installation-root.");
			return 2;
		}
		try {
			WorldBuilderAdaptiveProjectLifecycle lifecycle =
				new WorldBuilderAdaptiveProjectLifecycle();
			WorldBuilderAdaptiveProjectLifecycle.ProjectResult opened = validateOnly
				? lifecycle.validateActive(installation, target)
				: lifecycle.openActive(installation, target);
			System.out.print(opened.toJson());
			return 0;
		} catch (WorldBuilderContractException refusal) {
			return adaptiveRefusal(refusal);
		} catch (Exception failure) {
			System.err.println("ERROR: Could not open adaptive project: "
				+ failure.getMessage());
			return 4;
		}
	}

	private static int saveProject(String[] args) {
		Path project = singlePathOption(args, "--project", "save-project");
		if (project == null) return 2;
		try {
			System.out.print(new WorldBuilderAdaptiveProjectLifecycle()
				.save(project).toJson());
			return 0;
		} catch (WorldBuilderContractException refusal) {
			return adaptiveRefusal(refusal);
		} catch (Exception failure) {
			System.err.println("ERROR: Could not save adaptive project: "
				+ failure.getMessage());
			return 4;
		}
	}

	private static int regionCopy(String[] args, boolean cutPreview) {
		Path project = null;
		Path selection = null;
		String name = null;
		for (int index = 1; index < args.length; index++) {
			if ("--project".equals(args[index]) && index + 1 < args.length
				&& project == null) project = Paths.get(args[++index]);
			else if ("--selection".equals(args[index]) && index + 1 < args.length
				&& selection == null) selection = Paths.get(args[++index]);
			else if ("--name".equals(args[index]) && index + 1 < args.length
				&& name == null) name = args[++index];
			else return argumentError(args[index]);
		}
		if (project == null || selection == null || name == null) {
			System.err.println("ERROR: " + (cutPreview ? "region-cut-preview" : "region-copy")
				+ " requires --project, --selection, and --name.");
			return 2;
		}
		try {
			WorldBuilderRegionSnapshotService service =
				new WorldBuilderRegionSnapshotService();
			System.out.print(cutPreview
				? service.cutPreview(project, selection, name)
				: service.copy(project, selection, name));
			return 0;
		} catch (WorldBuilderContractException refusal) {
			return adaptiveRefusal(refusal);
		} catch (Exception failure) {
			System.err.println("ERROR: Region snapshot operation failed: "
				+ failure.getMessage());
			return 4;
		}
	}

	private static int regionCutApply(String[] args) {
		Path project = null;
		String snapshot = null;
		String plan = null;
		String confirm = null;
		for (int index = 1; index < args.length; index++) {
			if ("--project".equals(args[index]) && index + 1 < args.length
				&& project == null) project = Paths.get(args[++index]);
			else if ("--snapshot".equals(args[index]) && index + 1 < args.length
				&& snapshot == null) snapshot = args[++index];
			else if ("--expected-plan".equals(args[index]) && index + 1 < args.length
				&& plan == null) plan = args[++index];
			else if ("--confirm".equals(args[index]) && index + 1 < args.length
				&& confirm == null) confirm = args[++index];
			else return argumentError(args[index]);
		}
		if (project == null || snapshot == null || plan == null || confirm == null) {
			System.err.println("ERROR: region-cut-apply requires --project, --snapshot, "
				+ "--expected-plan, and --confirm 'CUT <plan-sha256>'.");
			return 2;
		}
		try {
			System.out.print(new WorldBuilderRegionSnapshotService().applyCut(
				project, snapshot, plan, confirm));
			return 0;
		} catch (WorldBuilderContractException refusal) {
			return adaptiveRefusal(refusal);
		} catch (Exception failure) {
			System.err.println("ERROR: Region cut failed: " + failure.getMessage());
			return 4;
		}
	}

	private static int regionImportExport(String[] args, boolean importing) {
		Path project = null;
		Path bundleOrOutput = null;
		String snapshot = null;
		for (int index = 1; index < args.length; index++) {
			if ("--project".equals(args[index]) && index + 1 < args.length
				&& project == null) project = Paths.get(args[++index]);
			else if (importing && "--bundle".equals(args[index])
				&& index + 1 < args.length && bundleOrOutput == null) {
				bundleOrOutput = Paths.get(args[++index]);
			} else if (!importing && "--output".equals(args[index])
				&& index + 1 < args.length && bundleOrOutput == null) {
				bundleOrOutput = Paths.get(args[++index]);
			} else if (!importing && "--snapshot".equals(args[index])
				&& index + 1 < args.length && snapshot == null) snapshot = args[++index];
			else return argumentError(args[index]);
		}
		if (project == null || bundleOrOutput == null || (!importing && snapshot == null)) {
			System.err.println("ERROR: region-" + (importing ? "import" : "export")
				+ " requires --project and " + (importing ? "--bundle."
					: "--snapshot plus --output."));
			return 2;
		}
		try {
			WorldBuilderRegionSnapshotService service =
				new WorldBuilderRegionSnapshotService();
			System.out.print(importing ? service.importBundle(project, bundleOrOutput)
				: service.exportBundle(project, snapshot, bundleOrOutput));
			return 0;
		} catch (WorldBuilderContractException refusal) {
			return adaptiveRefusal(refusal);
		} catch (Exception failure) {
			System.err.println("ERROR: Region bundle operation failed: "
				+ failure.getMessage());
			return 4;
		}
	}

	private static int regionPaste(String[] args, boolean apply) {
		Path project = null;
		String snapshot = null;
		String plan = null;
		String confirm = null;
		Integer level = null, x = null, y = null;
		for (int index = 1; index < args.length; index++) {
			String argument = args[index];
			if ("--project".equals(argument) && index + 1 < args.length
				&& project == null) project = Paths.get(args[++index]);
			else if ("--snapshot".equals(argument) && index + 1 < args.length
				&& snapshot == null) snapshot = args[++index];
			else if ("--level".equals(argument) && index + 1 < args.length
				&& level == null) level = parseIntOption("--level", args[++index]);
			else if ("--x".equals(argument) && index + 1 < args.length && x == null)
				x = parseIntOption("--x", args[++index]);
			else if ("--y".equals(argument) && index + 1 < args.length && y == null)
				y = parseIntOption("--y", args[++index]);
			else if (apply && "--expected-plan".equals(argument)
				&& index + 1 < args.length && plan == null) plan = args[++index];
			else if (apply && "--confirm".equals(argument)
				&& index + 1 < args.length && confirm == null) confirm = args[++index];
			else return argumentError(argument);
			if (("--level".equals(argument) || "--x".equals(argument)
				|| "--y".equals(argument)) && (level == null && "--level".equals(argument)
				|| x == null && "--x".equals(argument)
				|| y == null && "--y".equals(argument))) return 2;
		}
		if (project == null || snapshot == null || level == null || x == null || y == null
			|| apply && (plan == null || confirm == null)) {
			System.err.println("ERROR: region-paste-" + (apply ? "apply" : "preview")
				+ " requires --project, --snapshot, --level, --x, and --y"
				+ (apply ? " plus --expected-plan and --confirm." : "."));
			return 2;
		}
		try {
			WorldBuilderRegionSnapshotService service =
				new WorldBuilderRegionSnapshotService();
			System.out.print(apply
				? service.applyPaste(project, snapshot, level.intValue(), x.intValue(),
					y.intValue(), plan, confirm)
				: service.pastePreview(project, snapshot, level.intValue(), x.intValue(),
					y.intValue()));
			return 0;
		} catch (WorldBuilderContractException refusal) {
			return adaptiveRefusal(refusal);
		} catch (Exception failure) {
			System.err.println("ERROR: Region paste failed: " + failure.getMessage());
			return 4;
		}
	}

	private static int regionPasteUndo(String[] args) {
		Path project = singlePathOption(args, "--project", "region-paste-undo");
		if (project == null) return 2;
		try {
			System.out.print(new WorldBuilderRegionSnapshotService()
				.undoLastPaste(project));
			return 0;
		} catch (WorldBuilderContractException refusal) {
			return adaptiveRefusal(refusal);
		} catch (Exception failure) {
			System.err.println("ERROR: Region Paste Undo failed: " + failure.getMessage());
			return 4;
		}
	}

	private static int argumentError(String argument) {
		System.err.println("ERROR: Unknown, duplicate, or incomplete argument: " + argument);
		return 2;
	}

	private static int runAdaptiveProject(String[] args) {
		Path project = singlePathOption(args, "--project", "run-adaptive-project");
		if (project == null) return 2;
		try {
			return new WorldBuilderProcessSupervisor().runAdaptiveProject(project);
		} catch (WorldBuilderContractException refusal) {
			return adaptiveRefusal(refusal);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			System.err.println("ERROR: Adaptive World Builder launch was interrupted.");
			return 130;
		} catch (Exception failure) {
			System.err.println("ERROR: Adaptive World Builder launch failed: "
				+ failure.getMessage());
			return 4;
		}
	}

	private static int exportAdaptive(String[] args) {
		Path project = singlePathOption(args, "--project", "export-adaptive");
		if (project == null) return 2;
		try {
			System.out.print(new WorldBuilderAdaptiveExporter().export(project).toJson());
			return 0;
		} catch (WorldBuilderContractException refusal) {
			return adaptiveRefusal(refusal);
		} catch (Exception failure) {
			System.err.println("ERROR: Adaptive project export failed before publication: "
				+ failure.getMessage());
			return 4;
		}
	}

	private static int exportActiveAdaptive(String[] args) {
		Path installation = singlePathOption(args, "--installation-root",
			"export-active-adaptive");
		if (installation == null) return 2;
		try {
			WorldBuilderAdaptiveProjectLifecycle.VerifiedProject active =
				WorldBuilderAdaptiveProjectLifecycle.verifyActiveProject(installation);
			System.out.print(new WorldBuilderAdaptiveExporter()
				.export(active.projectRoot).toJson());
			return 0;
		} catch (WorldBuilderContractException refusal) {
			return adaptiveRefusal(refusal);
		} catch (Exception failure) {
			System.err.println("ERROR: Active adaptive project export failed: "
				+ failure.getMessage());
			return 4;
		}
	}

	private static int importAdaptive(String[] args) {
		Path project = null;
		Path export = null;
		Path target = null;
		String confirmation = null;
		String expectedTransactionId = null;
		String expectedPlanFingerprint = null;
		boolean projectSeen = false;
		boolean exportSeen = false;
		boolean targetSeen = false;
		boolean confirmationSeen = false;
		boolean transactionSeen = false;
		boolean fingerprintSeen = false;
		for (int index = 1; index < args.length; index++) {
			String argument = args[index];
			if ("--project".equals(argument) && index + 1 < args.length
				&& !projectSeen) {
				projectSeen = true;
				project = Paths.get(args[++index]);
			} else if ("--export".equals(argument) && index + 1 < args.length
				&& !exportSeen) {
				exportSeen = true;
				export = Paths.get(args[++index]);
			} else if ("--target-root".equals(argument) && index + 1 < args.length
				&& !targetSeen) {
				targetSeen = true;
				target = Paths.get(args[++index]);
			} else if ("--confirm".equals(argument) && index + 1 < args.length
				&& !confirmationSeen) {
				confirmationSeen = true;
				confirmation = args[++index];
			} else if ("--transaction-id".equals(argument)
				&& index + 1 < args.length && !transactionSeen) {
				transactionSeen = true;
				expectedTransactionId = args[++index];
			} else if ("--plan-sha256".equals(argument)
				&& index + 1 < args.length && !fingerprintSeen) {
				fingerprintSeen = true;
				expectedPlanFingerprint = args[++index];
			} else {
				System.err.println("ERROR: Unknown, repeated, or incomplete argument: "
					+ argument);
				return 2;
			}
		}
		if (project == null || export == null) {
			System.err.println("ERROR: import-adaptive requires --project and --export. "
				+ "--target-root is required only for a target-backed project; optional "
				+ "reviewed-plan options apply an exact preview.");
			return 2;
		}
		if (!validReviewedPlanArguments(confirmation, expectedTransactionId,
			expectedPlanFingerprint, "IMPORT", "import-adaptive")) return 2;
		try {
			WorldBuilderAdaptiveImporter importer = new WorldBuilderAdaptiveImporter();
			WorldBuilderAdaptiveImporter.Preview preview =
				confirmation == null
					? importer.preview(project, export, target)
					: importer.preview(project, export, target, expectedTransactionId);
			System.err.print(preview.humanSummary());
			if (confirmation == null) {
				System.out.print(preview.toJson());
				return 0;
			}
			if (!expectedPlanFingerprint.equals(preview.planFingerprintSha256())) {
				return reviewedPlanMismatch("import-adaptive");
			}
			System.out.print(importer.apply(preview, confirmation).toJson());
			return 0;
		} catch (WorldBuilderContractException refusal) {
			return adaptiveRefusal(refusal);
		} catch (Exception failure) {
			System.err.println("ERROR: Adaptive import failed: " + failure.getMessage());
			return 4;
		}
	}

	private static int upgradeTargetRuntime(String[] args) {
		Path project = null;
		Path export = null;
		Path target = null;
		String confirmation = null;
		String expectedTransactionId = null;
		String expectedPlanFingerprint = null;
		boolean projectSeen = false;
		boolean exportSeen = false;
		boolean targetSeen = false;
		boolean confirmationSeen = false;
		boolean transactionSeen = false;
		boolean fingerprintSeen = false;
		for (int index = 1; index < args.length; index++) {
			String argument = args[index];
			if ("--project".equals(argument) && index + 1 < args.length
				&& !projectSeen) {
				projectSeen = true;
				project = Paths.get(args[++index]);
			} else if ("--export".equals(argument) && index + 1 < args.length
				&& !exportSeen) {
				exportSeen = true;
				export = Paths.get(args[++index]);
			} else if ("--target-root".equals(argument) && index + 1 < args.length
				&& !targetSeen) {
				targetSeen = true;
				target = Paths.get(args[++index]);
			} else if ("--confirm".equals(argument) && index + 1 < args.length
				&& !confirmationSeen) {
				confirmationSeen = true;
				confirmation = args[++index];
			} else if ("--transaction-id".equals(argument)
				&& index + 1 < args.length && !transactionSeen) {
				transactionSeen = true;
				expectedTransactionId = args[++index];
			} else if ("--plan-sha256".equals(argument)
				&& index + 1 < args.length && !fingerprintSeen) {
				fingerprintSeen = true;
				expectedPlanFingerprint = args[++index];
			} else {
				System.err.println("ERROR: Unknown, repeated, or incomplete argument: "
					+ argument);
				return 2;
			}
		}
		if (project == null || export == null || target == null) {
			System.err.println("ERROR: upgrade-target-runtime requires --project, "
				+ "--export, and --target-root.");
			return 2;
		}
		if (!validReviewedPlanArguments(confirmation, expectedTransactionId,
			expectedPlanFingerprint, "UPGRADE", "upgrade-target-runtime")) return 2;
		try {
			WorldBuilderAdaptiveImporter importer = new WorldBuilderAdaptiveImporter();
			WorldBuilderAdaptiveImporter.Preview preview = confirmation == null
				? importer.previewRuntimeUpgrade(project, export, target)
				: importer.previewRuntimeUpgrade(
					project, export, target, expectedTransactionId);
			System.err.print(preview.humanSummary());
			if (confirmation == null) {
				System.out.print(preview.toJson());
				return 0;
			}
			if (!expectedPlanFingerprint.equals(preview.planFingerprintSha256())) {
				return reviewedPlanMismatch("upgrade-target-runtime");
			}
			System.out.print(importer.applyRuntimeUpgrade(preview, confirmation).toJson());
			return 0;
		} catch (WorldBuilderContractException refusal) {
			return adaptiveRefusal(refusal);
		} catch (Exception failure) {
			System.err.println("ERROR: Target runtime upgrade failed: "
				+ failure.getMessage());
			return 4;
		}
	}

	private static int importActiveAdaptive(String[] args) {
		Path installation = singlePathOption(args, "--installation-root",
			"import-active-adaptive");
		if (installation == null) return 2;
		try {
			WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project =
				WorldBuilderAdaptiveProjectLifecycle.verifyActiveProject(installation);
			if ("standalone-empty".equals(project.origin)) {
				// The importer performs the stable NO_TARGET refusal without resolving parent.
				new WorldBuilderAdaptiveImporter().preview(project.projectRoot, null, null);
			}
			Path install = installation.toAbsolutePath().normalize();
			Path target = install.getParent();
			if (target == null) throw new java.io.IOException(
				"World Builder installation has no parent target directory");
			WorldBuilderAdaptiveExporter.ExportResult exported =
				new WorldBuilderAdaptiveExporter().export(project.projectRoot);
			WorldBuilderAdaptiveImporter importer = new WorldBuilderAdaptiveImporter();
			WorldBuilderAdaptiveImporter.Preview preview = importer.preview(
				project.projectRoot, exported.exportDirectory, target);
			System.err.print(preview.humanSummary());
			if (!confirmAdaptive("IMPORT", "Type IMPORT to install the exact preview, "
				+ "or press Enter to cancel: ")) {
				System.err.println("Import cancelled; no target file was changed.");
				return 0;
			}
			System.out.print(importer.apply(preview, "IMPORT").toJson());
			return 0;
		} catch (WorldBuilderContractException refusal) {
			return adaptiveRefusal(refusal);
		} catch (Exception failure) {
			System.err.println("ERROR: Active adaptive import failed: "
				+ failure.getMessage());
			return 4;
		}
	}

	private static int upgradeActiveTargetRuntime(String[] args) {
		Path installation = singlePathOption(args, "--installation-root",
			"upgrade-active-target-runtime");
		if (installation == null) return 2;
		try {
			WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project =
				WorldBuilderAdaptiveProjectLifecycle.verifyActiveProject(installation);
			if ("standalone-empty".equals(project.origin)) {
				new WorldBuilderAdaptiveImporter().previewRuntimeUpgrade(
					project.projectRoot, null, null);
			}
			Path install = installation.toAbsolutePath().normalize();
			Path target = install.getParent();
			if (target == null) throw new java.io.IOException(
				"World Builder installation has no parent target directory");
			WorldBuilderAdaptiveExporter.ExportResult exported =
				new WorldBuilderAdaptiveExporter().export(project.projectRoot);
			WorldBuilderAdaptiveImporter importer = new WorldBuilderAdaptiveImporter();
			WorldBuilderAdaptiveImporter.Preview preview = importer.previewRuntimeUpgrade(
				project.projectRoot, exported.exportDirectory, target);
			System.err.print(preview.humanSummary());
			if (!confirmAdaptive("UPGRADE", "Type UPGRADE to replace only the reviewed "
				+ "target runtime files, or press Enter to cancel: ")) {
				System.err.println("Runtime upgrade cancelled; no target file was changed.");
				return 0;
			}
			System.out.print(importer.applyRuntimeUpgrade(preview, "UPGRADE").toJson());
			return 0;
		} catch (WorldBuilderContractException refusal) {
			return adaptiveRefusal(refusal);
		} catch (Exception failure) {
			System.err.println("ERROR: Active target runtime upgrade failed: "
				+ failure.getMessage());
			return 4;
		}
	}

	private static int recoverAdaptive(String[] args) {
		Path project = null;
		Path target = null;
		String confirmation = null;
		String expectedTransactionId = null;
		String expectedPlanFingerprint = null;
		boolean projectSeen = false;
		boolean targetSeen = false;
		boolean confirmationSeen = false;
		boolean transactionSeen = false;
		boolean fingerprintSeen = false;
		for (int index = 1; index < args.length; index++) {
			String argument = args[index];
			if ("--project".equals(argument) && index + 1 < args.length
				&& !projectSeen) {
				projectSeen = true;
				project = Paths.get(args[++index]);
			} else if ("--target-root".equals(argument) && index + 1 < args.length
				&& !targetSeen) {
				targetSeen = true;
				target = Paths.get(args[++index]);
			} else if ("--confirm".equals(argument) && index + 1 < args.length
				&& !confirmationSeen) {
				confirmationSeen = true;
				confirmation = args[++index];
			} else if ("--transaction-id".equals(argument)
				&& index + 1 < args.length && !transactionSeen) {
				transactionSeen = true;
				expectedTransactionId = args[++index];
			} else if ("--plan-sha256".equals(argument)
				&& index + 1 < args.length && !fingerprintSeen) {
				fingerprintSeen = true;
				expectedPlanFingerprint = args[++index];
			} else {
				System.err.println("ERROR: Unknown, repeated, or incomplete argument: "
					+ argument);
				return 2;
			}
		}
		if (project == null) {
			System.err.println("ERROR: recover-adaptive requires --project. "
				+ "--target-root is required only for a target-backed project; optional "
				+ "reviewed-plan options apply an exact preview.");
			return 2;
		}
		if (!validReviewedPlanArguments(confirmation, expectedTransactionId,
			expectedPlanFingerprint, "RECOVER", "recover-adaptive")) return 2;
		try {
			WorldBuilderAdaptiveRecovery recovery = new WorldBuilderAdaptiveRecovery();
			WorldBuilderAdaptiveRecovery.Preview preview = confirmation == null
				? recovery.preview(project, target)
				: recovery.preview(project, target, expectedTransactionId);
			System.err.print(preview.humanSummary());
			if (confirmation == null) {
				System.out.print(preview.toJson());
				return 0;
			}
			if (!expectedPlanFingerprint.equals(preview.planFingerprintSha256())) {
				return reviewedPlanMismatch("recover-adaptive");
			}
			System.out.print(recovery.apply(preview, confirmation).toJson());
			return 0;
		} catch (WorldBuilderContractException refusal) {
			return adaptiveRefusal(refusal);
		} catch (Exception failure) {
			System.err.println("ERROR: Adaptive recovery failed: "
				+ failure.getMessage());
			return 4;
		}
	}

	private static int recoverActiveAdaptive(String[] args) {
		Path installation = singlePathOption(args, "--installation-root",
			"recover-active-adaptive");
		if (installation == null) return 2;
		try {
			WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project =
				WorldBuilderAdaptiveProjectLifecycle.verifyActiveProject(installation);
			if ("standalone-empty".equals(project.origin)) {
				new WorldBuilderAdaptiveRecovery().preview(project.projectRoot, null);
			}
			Path install = installation.toAbsolutePath().normalize();
			Path target = install.getParent();
			if (target == null) throw new java.io.IOException(
				"World Builder installation has no parent target directory");
			WorldBuilderAdaptiveRecovery recovery = new WorldBuilderAdaptiveRecovery();
			WorldBuilderAdaptiveRecovery.Preview preview =
				recovery.preview(project.projectRoot, target);
			System.err.print(preview.humanSummary());
			if (!confirmAdaptive("RECOVER", "Type RECOVER to restore the exact transaction "
				+ "before state, or press Enter to leave recovery pending: ")) {
				System.err.println("Recovery left pending; keep the target offline.");
				return 0;
			}
			System.out.print(recovery.apply(preview, "RECOVER").toJson());
			return 0;
		} catch (WorldBuilderContractException refusal) {
			return adaptiveRefusal(refusal);
		} catch (Exception failure) {
			System.err.println("ERROR: Active adaptive recovery failed: "
				+ failure.getMessage());
			return 4;
		}
	}

	private static int launchAdaptive(String[] args) {
		Path installation = null;
		Path runtime = null;
		Path target = null;
		Path itemVisualMappings = null;
		String configurationRole = null;
		String displayName = null;
		String confirmation = null;
		int port = 0;
		for (int index = 1; index < args.length; index++) {
			String argument = args[index];
			if ("--installation-root".equals(argument) && index + 1 < args.length) {
				installation = Paths.get(args[++index]);
			} else if ("--runtime-root".equals(argument) && index + 1 < args.length) {
				runtime = Paths.get(args[++index]);
			} else if ("--target-root".equals(argument) && index + 1 < args.length) {
				target = Paths.get(args[++index]);
			} else if ("--item-visual-mappings".equals(argument)
				&& index + 1 < args.length) {
				itemVisualMappings = Paths.get(args[++index]);
			} else if ("--configuration-role".equals(argument)
				&& index + 1 < args.length) {
				configurationRole = args[++index];
			} else if ("--display-name".equals(argument) && index + 1 < args.length) {
				displayName = args[++index];
			} else if ("--confirm".equals(argument) && index + 1 < args.length) {
				confirmation = args[++index];
			} else if ("--port".equals(argument) && index + 1 < args.length) {
				Integer parsed = parseIntOption("--port", args[++index]);
				if (parsed == null) return 2;
				port = parsed.intValue();
			} else {
				System.err.println("ERROR: Unknown or incomplete argument: " + argument);
				usage();
				return 2;
			}
		}
		if (installation == null || runtime == null || target == null || port == 0) {
			System.err.println("ERROR: launch-adaptive requires --installation-root, "
				+ "--runtime-root, --target-root, and --port.");
			usage();
			return 2;
		}

		Path temporaryReport = null;
		try {
			WorldBuilderAdaptiveProjectLifecycle lifecycle =
				new WorldBuilderAdaptiveProjectLifecycle();
			Path registry = installation.toAbsolutePath().normalize()
				.resolve(WorldBuilderAdaptiveProjectLifecycle.REGISTRY_FILE);
			WorldBuilderAdaptiveProjectLifecycle.ProjectResult project;
			if (Files.exists(registry, LinkOption.NOFOLLOW_LINKS)) {
				project = lifecycle.openActive(installation, target);
				System.out.print(project.toJson());
				return new WorldBuilderProcessSupervisor()
					.runAdaptiveProject(project.projectRoot);
			}

			WorldBuilderAdaptiveDiscoveryReport report =
				new WorldBuilderAdaptiveDiscovery().discover(target, configurationRole);
			System.out.print(report.toJson());
			System.err.println("Discovery summary: " + report.summary());
			if ("blocked".equals(report.status)) return 3;
			if (confirmation == null) {
				boolean approved = confirm("CREATE",
					"Review the discovery report above. Type CREATE to make one isolated "
						+ "project, or press Enter to cancel: ");
				if (!approved) {
					System.out.println("Project creation cancelled; no project or target "
						+ "data was changed.");
					return 0;
				}
				confirmation = "CREATE";
			}
			if (displayName == null) {
				displayName = "standalone".equals(report.status)
					? "Standalone Empty World" : "Imported Server Map";
			}
			Path install = installation.toAbsolutePath().normalize();
			if (!Files.isDirectory(install, LinkOption.NOFOLLOW_LINKS)
				|| Files.isSymbolicLink(install)) {
				throw new java.io.IOException(
					"World Builder installation root is missing or unsafe");
			}
			temporaryReport = Files.createTempFile(
				install, ".adaptive-discovery-", ".json");
			Files.write(temporaryReport,
				report.toJson().getBytes(StandardCharsets.UTF_8));
			project = lifecycle.create(installation, runtime, target, temporaryReport,
				displayName, port, confirmation, itemVisualMappings);
			Files.delete(temporaryReport);
			temporaryReport = null;
			System.out.print(project.toJson());
			return new WorldBuilderProcessSupervisor()
				.runAdaptiveProject(project.projectRoot);
		} catch (WorldBuilderContractException refusal) {
			return adaptiveRefusal(refusal);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			System.err.println("ERROR: Adaptive World Builder launch was interrupted.");
			return 130;
		} catch (Exception failure) {
			System.err.println("ERROR: Adaptive World Builder launch failed: "
				+ failure.getMessage());
			return 4;
		} finally {
			if (temporaryReport != null) {
				try {
					Files.deleteIfExists(temporaryReport);
				} catch (Exception ignored) {
					// A failed temporary-report cleanup does not hide the primary refusal.
				}
			}
		}
	}

	private static int desktopLaunch(String[] args) {
		Path installation = null;
		Path runtime = null;
		Path target = null;
		String configurationRole = null;
		int port = 0;
		for (int index = 1; index < args.length; index++) {
			String argument = args[index];
			if ("--installation-root".equals(argument) && index + 1 < args.length) {
				installation = Paths.get(args[++index]);
			} else if ("--runtime-root".equals(argument) && index + 1 < args.length) {
				runtime = Paths.get(args[++index]);
			} else if ("--target-root".equals(argument) && index + 1 < args.length) {
				target = Paths.get(args[++index]);
			} else if ("--configuration-role".equals(argument)
				&& index + 1 < args.length) {
				configurationRole = args[++index];
			} else if ("--port".equals(argument) && index + 1 < args.length) {
				Integer parsed = parseIntOption("--port", args[++index]);
				if (parsed == null) return 2;
				port = parsed.intValue();
			} else {
				System.err.println("ERROR: Unknown or incomplete argument: " + argument);
				usage();
				return 2;
			}
		}
		if (installation == null || runtime == null || target == null || port == 0) {
			System.err.println("ERROR: desktop-launch requires --installation-root, "
				+ "--runtime-root, --target-root, and --port.");
			usage();
			return 2;
		}
		return WorldBuilderDesktopLauncher.launch(
			new WorldBuilderDesktopLauncher.Options(installation, runtime, target,
				configurationRole, port));
	}

	private static int refuseActiveAdaptiveMutation(
		String[] args, String operation) {
		Path installation = singlePathOption(
			args, "--installation-root", operation + "-active-adaptive");
		if (installation == null) return 2;
		try {
			WorldBuilderAdaptiveProjectLifecycle.refuseActiveMutationBeforeTarget(
				installation, operation);
			System.err.println("ERROR: Adaptive " + operation
				+ " preflight unexpectedly returned without a refusal.");
			return 4;
		} catch (WorldBuilderDiscoveryException refusal) {
			System.err.println("ERROR: " + refusal.getMessage());
			return 3;
		} catch (Exception failure) {
			System.err.println("ERROR: Adaptive " + operation
				+ " preflight failed: " + failure.getMessage());
			return 4;
		}
	}

	private static Path singlePathOption(String[] args, String option,
		String command) {
		Path value = null;
		for (int index = 1; index < args.length; index++) {
			if (option.equals(args[index]) && index + 1 < args.length && value == null) {
				value = Paths.get(args[++index]);
			} else {
				System.err.println("ERROR: Unknown, repeated, or incomplete argument: "
					+ args[index]);
				return null;
			}
		}
		if (value == null) {
			System.err.println("ERROR: " + command + " requires " + option + ".");
		}
		return value;
	}

	private static int adaptiveRefusal(WorldBuilderContractException refusal) {
		System.err.println("ERROR [" + refusal.code() + "]: " + refusal.getMessage()
			+ (refusal.relativePath().isEmpty() ? ""
				: " Source: " + refusal.relativePath() + ".")
			+ " Next step: " + refusal.nextStep());
		return 3;
	}

	private static boolean validReviewedPlanArguments(String confirmation,
		String transactionId, String planFingerprint, String expectedConfirmation,
		String command) {
		if (confirmation == null) {
			if (transactionId != null || planFingerprint != null) {
				System.err.println("ERROR: " + command
					+ " accepts --transaction-id and --plan-sha256 only with --confirm "
					+ expectedConfirmation + ".");
				return false;
			}
			return true;
		}
		if (transactionId == null || planFingerprint == null) {
			System.err.println("ERROR: " + command + " --confirm "
				+ expectedConfirmation + " requires the exact --transaction-id and "
				+ "--plan-sha256 emitted by the reviewed preview.");
			return false;
		}
		try {
			if (!UUID.fromString(transactionId).toString().equals(transactionId)) {
				throw new IllegalArgumentException("non-canonical UUID");
			}
		} catch (IllegalArgumentException invalid) {
			System.err.println("ERROR: --transaction-id must be one canonical lowercase UUID.");
			return false;
		}
		if (!planFingerprint.matches("[0-9a-f]{64}")) {
			System.err.println("ERROR: --plan-sha256 must be one lowercase SHA-256 value.");
			return false;
		}
		return true;
	}

	private static int reviewedPlanMismatch(String command) {
		System.err.println("ERROR [" + WorldBuilderErrorCodes.TARGET_DRIFT + "]: "
			+ command + " refused because --plan-sha256 does not identify the exact "
			+ "independently recompiled plan. Request and review a fresh preview; "
			+ "no transaction artifact or target file was changed.");
		return 3;
	}

	private static int convertPacked(String[] args) {
		Path sourceRoot = null;
		Path discoveryReport = null;
		Path output = null;
		for (int index = 1; index < args.length; index++) {
			String argument = args[index];
			if ("--source-root".equals(argument) && index + 1 < args.length) {
				sourceRoot = Paths.get(args[++index]);
			} else if ("--discovery-report".equals(argument) && index + 1 < args.length) {
				discoveryReport = Paths.get(args[++index]);
			} else if ("--output".equals(argument) && index + 1 < args.length) {
				output = Paths.get(args[++index]);
			} else {
				System.err.println("ERROR: Unknown or incomplete argument: " + argument);
				usage();
				return 2;
			}
		}
		if (sourceRoot == null || discoveryReport == null || output == null) {
			System.err.println("ERROR: convert-packed requires --source-root, "
				+ "--discovery-report, and --output.");
			usage();
			return 2;
		}
		try {
			WorldBuilderPackedConverter.Result result =
				new WorldBuilderPackedConverter().convert(
					sourceRoot, discoveryReport, output);
			System.out.print(result.toJson());
			return 0;
		} catch (WorldBuilderContractException refusal) {
			System.err.println("ERROR [" + refusal.code() + "]: " + refusal.getMessage()
				+ (refusal.relativePath().isEmpty() ? ""
					: " Source: " + refusal.relativePath() + ".")
				+ (refusal.provenance().isEmpty() ? ""
					: " Provenance: " + refusal.provenance() + ".")
				+ " Next step: " + refusal.nextStep());
			return 3;
		} catch (Exception failure) {
			System.err.println("ERROR: Packed conversion failed before publication: "
				+ failure.getMessage());
			return 4;
		}
	}

	private static int discoverAdaptive(String[] args) {
		Path root = null;
		String configurationRole = null;
		for (int index = 1; index < args.length; index++) {
			String argument = args[index];
			if (("--target-root".equals(argument) || "--server-root".equals(argument))
				&& index + 1 < args.length) {
				if (root != null) {
					System.err.println("ERROR: Supply the target root exactly once.");
					return 2;
				}
				root = Paths.get(args[++index]);
			} else if ("--configuration-role".equals(argument)
				&& index + 1 < args.length) {
				configurationRole = args[++index];
			} else {
				System.err.println("ERROR: Unknown or incomplete argument: " + argument);
				usage();
				return 2;
			}
		}
		if (root == null) {
			System.err.println("ERROR: discover-adaptive requires --target-root.");
			usage();
			return 2;
		}
		try {
			WorldBuilderAdaptiveDiscoveryReport report =
				new WorldBuilderAdaptiveDiscovery().discover(root, configurationRole);
			System.out.print(report.toJson());
			System.err.println("Discovery summary: " + report.summary());
			return "blocked".equals(report.status) ? 3 : 0;
		} catch (WorldBuilderContractException internalRefusal) {
			String targetDisplay = root.toAbsolutePath().normalize().toString();
			System.err.println("ERROR: Could not produce a valid adaptive discovery report: "
				+ WorldBuilderAdaptiveDiscoveryReport.sanitizeDiagnostic(
					internalRefusal.getMessage(), targetDisplay));
			return 4;
		}
	}

	private static int validateCurrentRuntimeContract(String[] args) {
		String kind = null;
		Path document = null;
		for (int index = 1; index < args.length; index++) {
			if ("--kind".equals(args[index]) && index + 1 < args.length && kind == null) {
				kind = args[++index];
			} else if ("--document".equals(args[index]) && index + 1 < args.length
				&& document == null) {
				document = Paths.get(args[++index]);
			} else {
				System.err.println("ERROR: Unknown, repeated, or incomplete argument: " + args[index]);
				return 2;
			}
		}
		if (kind == null || document == null) {
			System.err.println("ERROR: validate-current-runtime-contract requires --kind and --document.");
			return 2;
		}
		try {
		{
			WorldBuilderCurrentRuntimeContracts.Document validated =
				WorldBuilderCurrentRuntimeContracts.read(
					WorldBuilderCurrentRuntimeContracts.Kind.named(kind), document);
			System.out.println(validated.canonicalSha256);
			System.out.print(validated.canonicalJson);
			return 0;
		}
		} catch (WorldBuilderContractException refusal) {
			return adaptiveRefusal(refusal);
		} catch (Exception failure) {
			System.err.println("ERROR: Current-runtime contract validation failed: "
				+ failure.getMessage());
			return 4;
		}
	}

	private static int classifyCurrentTarget(String[] args) {
		Path target = null;
		Path platform = null;
		Path variant = null;
		Path modules = null;
		Path adapter = null;
		Path projectCapability = null;
		for (int index = 1; index < args.length; index++) {
			if (index + 1 >= args.length) {
				System.err.println("ERROR: Incomplete argument: " + args[index]);
				return 2;
			}
			String option = args[index++];
			Path value = Paths.get(args[index]);
			if ("--target-root".equals(option) && target == null) target = value;
			else if ("--platform-release".equals(option) && platform == null) platform = value;
			else if ("--variant".equals(option) && variant == null) variant = value;
			else if ("--module-set".equals(option) && modules == null) modules = value;
			else if ("--input-adapter".equals(option) && adapter == null) adapter = value;
			else if ("--project-capability".equals(option) && projectCapability == null) {
				projectCapability = value;
			} else {
				System.err.println("ERROR: Unknown or repeated argument: " + option);
				return 2;
			}
		}
		if (target == null || platform == null || variant == null || modules == null
			|| adapter == null || projectCapability == null) {
			System.err.println("ERROR: classify-current-target requires --target-root, "
				+ "--platform-release, --variant, --module-set, --input-adapter, "
				+ "and --project-capability.");
			return 2;
		}
		try {
		{
			WorldBuilderCurrentRuntimeContracts.Classification result =
				WorldBuilderCurrentRuntimeContracts.classify(target, platform, variant,
					modules, adapter, projectCapability);
			System.out.print(result.toJson());
			return "BLOCKED_UNSAFE".equals(result.status())
				|| "PORT_REQUIRED".equals(result.status()) ? 3 : 0;
		}
		} catch (WorldBuilderContractException refusal) {
			return adaptiveRefusal(refusal);
		} catch (Exception failure) {
			System.err.println("ERROR: Current target classification failed: "
				+ failure.getMessage());
			return 4;
		}
	}

	private static int discoverLegacyLandscape(String[] args) {
		Path root = null;
		String configurationRole = null;
		for (int index = 1; index < args.length; index++) {
			String argument = args[index];
			if ("--target-root".equals(argument) && index + 1 < args.length) {
				root = Paths.get(args[++index]);
			} else if ("--configuration-role".equals(argument)
				&& index + 1 < args.length) {
				configurationRole = args[++index];
			} else {
				System.err.println("ERROR: Unknown or incomplete argument: " + argument);
				usage();
				return 2;
			}
		}
		if (root == null) {
			System.err.println("ERROR: discover-legacy-landscape requires --target-root.");
			return 2;
		}
		try {
			WorldBuilderAdaptiveDiscoveryReport report =
				new WorldBuilderLegacyLandscapeDiscovery().discover(
					root, configurationRole);
			System.out.print(report.toJson());
			return 0;
		} catch (WorldBuilderContractException refusal) {
			return adaptiveRefusal(refusal);
		} catch (Exception failure) {
			System.err.println("ERROR: Legacy landscape discovery failed: "
				+ failure.getMessage());
			return 4;
		}
	}

	private static int createLevel(String[] args) {
		Path workspace = null;
		Integer level = null;
		Integer anchorX = null;
		Integer anchorY = null;
		String name = null;
		String role = null;
		for (int index = 1; index < args.length; index++) {
			String argument = args[index];
			if ("--workspace".equals(argument) && index + 1 < args.length) {
				workspace = Paths.get(args[++index]);
			} else if ("--level".equals(argument) && index + 1 < args.length) {
				level = parseIntOption("--level", args[++index]);
				if (level == null) return 2;
			} else if ("--anchor-x".equals(argument) && index + 1 < args.length) {
				anchorX = parseIntOption("--anchor-x", args[++index]);
				if (anchorX == null) return 2;
			} else if ("--anchor-y".equals(argument) && index + 1 < args.length) {
				anchorY = parseIntOption("--anchor-y", args[++index]);
				if (anchorY == null) return 2;
			} else if ("--name".equals(argument) && index + 1 < args.length) {
				name = args[++index];
			} else if ("--role".equals(argument) && index + 1 < args.length) {
				role = args[++index];
			} else {
				System.err.println(
					"ERROR: Unknown or incomplete argument: " + argument);
				usage();
				return 2;
			}
		}
		if (workspace == null || level == null
			|| anchorX == null || anchorY == null) {
			System.err.println(
				"ERROR: create-level requires --workspace, --level, "
					+ "--anchor-x, and --anchor-y.");
			usage();
			return 2;
		}
		if (name == null) {
			name = WorldBuilderLayeredDraftWriter.defaultName(level.intValue());
		}
		if (role == null) {
			role = WorldBuilderLayeredDraftWriter.defaultRole(level.intValue());
		}
		try {
			WorldBuilderLayeredDraftWriter.CreateLevelResult result =
				new WorldBuilderLayeredDraftWriter().createLevel(
					workspace,
					level.intValue(),
					anchorX.intValue(),
					anchorY.intValue(),
					name,
					role);
			System.out.print(result.toJson());
			return 0;
		} catch (WorldBuilderDiscoveryException refusal) {
			System.err.println("ERROR: " + refusal.getMessage());
			return 3;
		} catch (Exception failure) {
			System.err.println(
				"ERROR: Could not create layered Builder level: "
					+ failure.getMessage());
			return 4;
		}
	}

	private static Integer parseIntOption(String option, String value) {
		try {
			return Integer.valueOf(value);
		} catch (NumberFormatException failure) {
			System.err.println(
				"ERROR: " + option + " must be a signed 32-bit integer.");
			return null;
		}
	}

	private static int runPrepared(String[] args, boolean launchArguments) {
		Path workspace = null;
		int port = 0;
		for (int index = 1; index < args.length; index++) {
			String argument = args[index];
			if ("--workspace".equals(argument) && index + 1 < args.length) {
				workspace = Paths.get(args[++index]);
			} else if ("--port".equals(argument) && index + 1 < args.length) {
				try {
					port = Integer.parseInt(args[++index]);
				} catch (NumberFormatException failure) {
					System.err.println("ERROR: --port must be numeric.");
					return 2;
				}
			} else if (launchArguments && isPreparationOption(argument) && index + 1 < args.length) {
				index++;
			} else {
				System.err.println("ERROR: Unknown or incomplete argument: " + argument);
				usage();
				return 2;
			}
		}
		if (workspace == null || (launchArguments && port == 0)) {
			System.err.println("ERROR: run requires --workspace; launch also requires --port.");
			usage();
			return 2;
		}
		try {
			if (port == 0) {
				port = WorldBuilderProcessSupervisor.readPreparedPort(workspace);
			}
			System.out.println("Starting isolated World Builder. Logs: "
				+ workspace.toAbsolutePath().normalize().resolve("logs"));
			int result = new WorldBuilderProcessSupervisor().runPrepared(workspace, port);
			if (result == 0) {
				System.out.println("World Builder closed cleanly.");
			} else {
				System.err.println("ERROR: World Builder stopped with exit code " + result + ".");
			}
			return result;
		} catch (WorldBuilderDiscoveryException refusal) {
			System.err.println("ERROR: " + refusal.getMessage());
			return 3;
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			System.err.println("ERROR: World Builder launcher was interrupted.");
			return 130;
		} catch (Exception failure) {
			System.err.println("ERROR: World Builder launch failed: " + failure.getMessage());
			return 4;
		}
	}

	private static int exportImport(String[] args) {
		Path workspace = null;
		Path target = null;
		String builderVersion = null;
		String sourceCommit = null;
		for (int index = 1; index < args.length; index++) {
			String argument = args[index];
			if ("--workspace".equals(argument) && index + 1 < args.length) {
				workspace = Paths.get(args[++index]);
			} else if ("--target-root".equals(argument) && index + 1 < args.length) {
				target = Paths.get(args[++index]);
			} else if ("--builder-version".equals(argument) && index + 1 < args.length) {
				builderVersion = args[++index];
			} else if ("--source-commit".equals(argument) && index + 1 < args.length) {
				sourceCommit = args[++index];
			} else {
				System.err.println("ERROR: Unknown or incomplete argument: " + argument);
				usage();
				return 2;
			}
		}
		if (workspace == null || target == null || builderVersion == null || sourceCommit == null) {
			System.err.println("ERROR: export-import requires --workspace, --target-root, "
				+ "--builder-version, and --source-commit.");
			usage();
			return 2;
		}
		try {
			WorldBuilderExporter.ExportResult exported = new WorldBuilderExporter().export(
				workspace, builderVersion, sourceCommit);
			System.out.print(exported.toJson());
			if (exported.exportDirectory == null) {
				System.out.println("No saved map changes are available to import.");
				return 0;
			}
			WorldBuilderImporter importer = new WorldBuilderImporter();
			System.out.println("Import preview:");
			System.out.print(importer.preview(workspace, exported.exportDirectory, target).toJson());
			if (!confirm("IMPORT", "Type IMPORT to install these map changes, or press Enter to cancel: ")) {
				System.out.println("Import cancelled; the target private server was not changed.");
				return 0;
			}
			System.out.print(importer.apply(workspace, exported.exportDirectory, target).toJson());
			return 0;
		} catch (WorldBuilderDiscoveryException refusal) {
			System.err.println("ERROR: " + refusal.getMessage());
			return 3;
		} catch (Exception failure) {
			System.err.println("ERROR: Could not export and import Builder changes: "
				+ failure.getMessage());
			return 4;
		}
	}

	private static int undoLatestImport(String[] args) {
		Path workspace = null;
		Path target = null;
		for (int index = 1; index < args.length; index++) {
			String argument = args[index];
			if ("--workspace".equals(argument) && index + 1 < args.length) {
				workspace = Paths.get(args[++index]);
			} else if ("--target-root".equals(argument) && index + 1 < args.length) {
				target = Paths.get(args[++index]);
			} else {
				System.err.println("ERROR: Unknown or incomplete argument: " + argument);
				usage();
				return 2;
			}
		}
		if (workspace == null || target == null) {
			System.err.println(
				"ERROR: undo-latest-import requires --workspace and --target-root.");
			usage();
			return 2;
		}
		try {
			WorldBuilderImporter importer = new WorldBuilderImporter();
			System.out.println("Undo preview:");
			System.out.print(importer.previewRollback(workspace, target).toJson());
			if (!confirm("UNDO", "Type UNDO to restore the previous map files, or press Enter to cancel: ")) {
				System.out.println("Undo cancelled; the target private server was not changed.");
				return 0;
			}
			System.out.print(importer.rollback(workspace, target).toJson());
			return 0;
		} catch (WorldBuilderDiscoveryException refusal) {
			System.err.println("ERROR: " + refusal.getMessage());
			return 3;
		} catch (Exception failure) {
			System.err.println("ERROR: Could not undo the latest Builder import: "
				+ failure.getMessage());
			return 4;
		}
	}

	private static boolean confirm(String expected, String prompt) throws Exception {
		System.out.print(prompt);
		System.out.flush();
		String response = new BufferedReader(new InputStreamReader(System.in, "UTF-8")).readLine();
		return expected.equals(response == null ? "" : response.trim());
	}

	private static boolean confirmAdaptive(String expected, String prompt)
		throws Exception {
		System.err.print(prompt);
		System.err.flush();
		String response = new BufferedReader(new InputStreamReader(
			System.in, StandardCharsets.UTF_8.name())).readLine();
		return expected.equals(response == null ? "" : response);
	}

	private static int export(String[] args) {
		Path workspace = null; String builderVersion = null; String sourceCommit = null;
		for (int index = 1; index < args.length; index++) {
			String argument = args[index];
			if ("--workspace".equals(argument) && index + 1 < args.length) workspace = Paths.get(args[++index]);
			else if ("--builder-version".equals(argument) && index + 1 < args.length) builderVersion = args[++index];
			else if ("--source-commit".equals(argument) && index + 1 < args.length) sourceCommit = args[++index];
			else { System.err.println("ERROR: Unknown or incomplete argument: " + argument); usage(); return 2; }
		}
		if (workspace == null || builderVersion == null || sourceCommit == null) {
			System.err.println("ERROR: export requires --workspace, --builder-version, and --source-commit.");
			usage(); return 2;
		}
		try {
			System.out.print(new WorldBuilderExporter().export(workspace, builderVersion, sourceCommit).toJson());
			return 0;
		} catch (WorldBuilderDiscoveryException refusal) {
			System.err.println("ERROR: " + refusal.getMessage()); return 3;
		} catch (Exception failure) {
			System.err.println("ERROR: Could not export Builder project: " + failure.getMessage()); return 4;
		}
	}

	private static int importChanges(String[] args) {
		Path workspace = null;
		Path export = null;
		Path target = null;
		boolean dryRun = false;
		boolean apply = false;
		for (int index = 1; index < args.length; index++) {
			String argument = args[index];
			if ("--workspace".equals(argument) && index + 1 < args.length) {
				workspace = Paths.get(args[++index]);
			} else if ("--export".equals(argument) && index + 1 < args.length) {
				export = Paths.get(args[++index]);
			} else if ("--target-root".equals(argument) && index + 1 < args.length) {
				target = Paths.get(args[++index]);
			} else if ("--dry-run".equals(argument)) {
				dryRun = true;
			} else if ("--apply".equals(argument)) {
				apply = true;
			} else {
				System.err.println("ERROR: Unknown or incomplete argument: " + argument);
				usage();
				return 2;
			}
		}
		if (workspace == null || export == null || target == null || dryRun == apply) {
			System.err.println("ERROR: import requires --workspace, --export, --target-root, "
				+ "and exactly one of --dry-run or --apply.");
			usage();
			return 2;
		}
		try {
			WorldBuilderImporter importer = new WorldBuilderImporter();
			System.out.print(dryRun
				? importer.preview(workspace, export, target).toJson()
				: importer.apply(workspace, export, target).toJson());
			return 0;
		} catch (WorldBuilderDiscoveryException refusal) {
			System.err.println("ERROR: " + refusal.getMessage());
			return 3;
		} catch (Exception failure) {
			System.err.println("ERROR: Could not process Builder import: " + failure.getMessage());
			return 4;
		}
	}

	private static int undoImport(String[] args) {
		Path workspace = null;
		Path target = null;
		boolean dryRun = false;
		boolean apply = false;
		for (int index = 1; index < args.length; index++) {
			String argument = args[index];
			if ("--workspace".equals(argument) && index + 1 < args.length) {
				workspace = Paths.get(args[++index]);
			} else if ("--target-root".equals(argument) && index + 1 < args.length) {
				target = Paths.get(args[++index]);
			} else if ("--dry-run".equals(argument)) {
				dryRun = true;
			} else if ("--apply".equals(argument)) {
				apply = true;
			} else {
				System.err.println("ERROR: Unknown or incomplete argument: " + argument);
				usage();
				return 2;
			}
		}
		if (workspace == null || target == null || dryRun == apply) {
			System.err.println("ERROR: undo-import requires --workspace, --target-root, "
				+ "and exactly one of --dry-run or --apply.");
			usage();
			return 2;
		}
		try {
			WorldBuilderImporter importer = new WorldBuilderImporter();
			System.out.print(dryRun
				? importer.previewRollback(workspace, target).toJson()
				: importer.rollback(workspace, target).toJson());
			return 0;
		} catch (WorldBuilderDiscoveryException refusal) {
			System.err.println("ERROR: " + refusal.getMessage());
			return 3;
		} catch (Exception failure) {
			System.err.println("ERROR: Could not undo Builder import: " + failure.getMessage());
			return 4;
		}
	}

	private static boolean isPreparationOption(String argument) {
		return "--server-root".equals(argument)
			|| "--runtime-root".equals(argument)
			|| "--config".equals(argument)
			|| "--runtime-config".equals(argument)
			|| "--layered-package".equals(argument)
			|| "--layered-profile".equals(argument);
	}

	private static int prepare(String[] args) {
		Path targetRoot = null;
		Path runtimeRoot = null;
		Path workspace = null;
		String config = null;
		String runtimeConfig = null;
		Path layeredPackagePath = null;
		String layeredProfile = null;
		int port = 0;
		for (int index = 1; index < args.length; index++) {
			String argument = args[index];
			if ("--server-root".equals(argument) && index + 1 < args.length) {
				targetRoot = Paths.get(args[++index]);
			} else if ("--runtime-root".equals(argument) && index + 1 < args.length) {
				runtimeRoot = Paths.get(args[++index]);
			} else if ("--workspace".equals(argument) && index + 1 < args.length) {
				workspace = Paths.get(args[++index]);
			} else if ("--config".equals(argument) && index + 1 < args.length) {
				config = args[++index];
			} else if ("--runtime-config".equals(argument) && index + 1 < args.length) {
				runtimeConfig = args[++index];
			} else if ("--layered-package".equals(argument) && index + 1 < args.length) {
				layeredPackagePath = Paths.get(args[++index]);
			} else if ("--layered-profile".equals(argument) && index + 1 < args.length) {
				layeredProfile = args[++index];
			} else if ("--port".equals(argument) && index + 1 < args.length) {
				try {
					port = Integer.parseInt(args[++index]);
				} catch (NumberFormatException failure) {
					System.err.println("ERROR: --port must be numeric.");
					return 2;
				}
			} else {
				System.err.println("ERROR: Unknown or incomplete argument: " + argument);
				usage();
				return 2;
			}
		}
		if (targetRoot == null || runtimeRoot == null || workspace == null || port == 0) {
			System.err.println("ERROR: prepare requires --server-root, --runtime-root, --workspace, and --port.");
			usage();
			return 2;
		}

		try {
			WorldBuilderDiscovery discovery = new WorldBuilderDiscovery();
			WorldBuilderDiscoveryResult runtime = discovery.discover(runtimeRoot, runtimeConfig, null);
			WorldBuilderDiscoveryResult source = discovery.discover(
				targetRoot, config, runtime.contentFingerprintSha256);
			if ((layeredPackagePath == null) != (layeredProfile == null)) {
				throw new WorldBuilderDiscoveryException(
					"--layered-package and --layered-profile must be supplied together.");
			}
			WorldBuilderLayeredPackage layered = layeredPackagePath == null
				? null
				: WorldBuilderLayeredPackage.discover(
					layeredPackagePath, layeredProfile);
			WorldBuilderRuntimePreparer.PreparedRuntime prepared =
				new WorldBuilderRuntimePreparer().prepare(
					targetRoot, runtimeRoot, workspace, port, source, runtime, layered);
			System.out.print(prepared.toJson());
			return 0;
		} catch (WorldBuilderDiscoveryException refusal) {
			System.err.println("ERROR: " + refusal.getMessage());
			return 3;
		} catch (Exception failure) {
			System.err.println("ERROR: Could not prepare isolated Builder runtime: " + failure.getMessage());
			return 4;
		}
	}

	private static void usage() {
		System.err.println("Usage:\n  WorldBuilderCli discover-adaptive --target-root <path>"
			+ " [--configuration-role <role>]"
			+ "\n  WorldBuilderCli validate-current-runtime-contract --kind <kind>"
			+ " --document <manifest.json>"
			+ "\n  WorldBuilderCli classify-current-target --target-root <server-root>"
			+ " --platform-release <manifest.json> --variant <manifest.json>"
			+ " --module-set <manifest.json> --input-adapter <manifest.json>"
			+ " --project-capability <manifest.json>"
			+ "\n  WorldBuilderCli discover-legacy-landscape --target-root <path>"
			+ " [--configuration-role <role>]"
			+ "\n  WorldBuilderCli discover-item-provider --installation-root <World Builder 2>"
			+ " --source-root <server-or-provider-parent>"
			+ "\n  WorldBuilderCli import-item-provider --installation-root <World Builder 2>"
			+ " --source-root <server-or-provider-parent>"
			+ " (--item-visuals <item-visuals.json> | --definitions <json-or-folder>)"
			+ " [--authentic-archive <file>] [--custom-archive <file>]"
			+ " [--spritepacks <folder>] [--external-items <folder>]"
			+ "\n  WorldBuilderCli export-item-provider-diagnostic"
			+ " --installation-root <World Builder 2> --source-root <server-root>"
			+ "\n  WorldBuilderCli reset-item-provider-cache"
			+ " --installation-root <World Builder 2> --source-root <server-root>"
			+ " --confirm \"RESET PROVIDER CACHE\""
			+ "\n  WorldBuilderCli convert-packed --source-root <immutable-copy>"
			+ " --discovery-report <report.json> --output <new-directory>"
			+ "\n  WorldBuilderCli create-project --installation-root <World Builder 2>"
			+ " --runtime-root <builder-runtime> [--target-root <server-root>]"
			+ " --discovery-report <report.json> --display-name <name> --port <port>"
			+ " [--item-visual-mappings <mapping.json>]"
			+ " [--development-terrain-seed] --confirm CREATE"
			+ "\n  WorldBuilderCli create-migrated-project"
			+ " --installation-root <World Builder 2> --runtime-root <builder-runtime>"
			+ " --target-root <server-root> --discovery-report <selected-layered.json>"
			+ " --legacy-discovery-report <legacy-packed.json> --display-name <name>"
			+ " --port <port> [--item-visual-mappings <mapping.json>]"
			+ " [--retire-legacy-landscape] --confirm CREATE"
			+ "\n  WorldBuilderCli list-projects --installation-root <World Builder 2>"
			+ "\n  WorldBuilderCli select-project --installation-root <World Builder 2>"
			+ " --project-id <uuid>"
			+ "\n  WorldBuilderCli open-project --installation-root <World Builder 2>"
			+ " [--target-root <server-root>] [--validate-only]"
			+ "\n  WorldBuilderCli save-project --project <projects/uuid>"
			+ "\n  WorldBuilderCli region-copy --project <projects/uuid>"
			+ " --selection <region-selection-v1.json> --name <name>"
			+ "\n  WorldBuilderCli region-cut-preview --project <projects/uuid>"
			+ " --selection <region-selection-v1.json> --name <name>"
			+ "\n  WorldBuilderCli region-cut-apply --project <projects/uuid>"
			+ " --snapshot <sha256> --expected-plan <sha256> --confirm 'CUT <sha256>'"
			+ "\n  WorldBuilderCli region-import --project <projects/uuid> --bundle <file.wbr>"
			+ "\n  WorldBuilderCli region-export --project <projects/uuid>"
			+ " --snapshot <sha256> --output <new-file.wbr>"
			+ "\n  WorldBuilderCli region-paste-preview --project <projects/uuid>"
			+ " --snapshot <sha256> --level <level> --x <x> --y <y>"
			+ "\n  WorldBuilderCli region-paste-apply --project <projects/uuid>"
			+ " --snapshot <sha256> --level <level> --x <x> --y <y>"
			+ " --expected-plan <sha256> --confirm 'PASTE|OVERWRITE <sha256>'"
			+ "\n  WorldBuilderCli region-paste-undo --project <projects/uuid>"
			+ "\n  WorldBuilderCli run-adaptive-project --project <projects/uuid>"
			+ "\n  WorldBuilderCli export-adaptive --project <projects/uuid>"
			+ "\n  WorldBuilderCli export-active-adaptive"
			+ " --installation-root <World Builder 2>"
			+ "\n  WorldBuilderCli import-adaptive --project <projects/uuid>"
			+ " --export <export-directory> [--target-root <server-root>]"
			+ " [--confirm IMPORT --transaction-id <preview-uuid>"
			+ " --plan-sha256 <preview-sha256>]"
			+ "\n  WorldBuilderCli upgrade-target-runtime --project <projects/uuid>"
			+ " --export <export-directory> --target-root <server-root>"
			+ " [--confirm UPGRADE --transaction-id <preview-uuid>"
			+ " --plan-sha256 <preview-sha256>]"
			+ "\n  WorldBuilderCli recover-adaptive --project <projects/uuid>"
			+ " [--target-root <server-root>] [--confirm RECOVER"
			+ " --transaction-id <preview-uuid> --plan-sha256 <preview-sha256>]"
			+ "\n  WorldBuilderCli launch-adaptive --installation-root <World Builder 2>"
			+ " --runtime-root <builder-runtime> --target-root <parent> --port <port>"
			+ " [--configuration-role <role>] [--display-name <name>] [--confirm CREATE]"
			+ " [--item-visual-mappings <mapping.json>]"
			+ "\n  WorldBuilderCli desktop-launch --installation-root <World Builder 2>"
			+ " --runtime-root <builder-runtime> --target-root <parent> --port <port>"
			+ " [--configuration-role <role>]"
			+ "\n  WorldBuilderCli import-active-adaptive --installation-root <World Builder 2>"
			+ "\n  WorldBuilderCli upgrade-active-target-runtime"
			+ " --installation-root <World Builder 2>"
			+ "\n  WorldBuilderCli recover-active-adaptive"
			+ " --installation-root <World Builder 2>"
			+ "\n  WorldBuilderCli discover --server-root <path>"
			+ " [--config <supported-myworld.conf-path>]"
			+ " [--expected-content-sha256 <sha256>]"
			+ "\n  WorldBuilderCli prepare --server-root <path> --runtime-root <path>"
			+ " --workspace <path> --port <port>"
			+ " [--config <source-config>] [--runtime-config <runtime-config>]"
			+ " [--layered-package <package> --layered-profile spoiled-milk-replacement]"
			+ "\n  WorldBuilderCli launch <same arguments as prepare>"
			+ "\n  WorldBuilderCli run --workspace <prepared-path> [--port <port>]");
		System.err.println("  WorldBuilderCli export --workspace <prepared-path>"
			+ " --builder-version <version> --source-commit <40-hex>");
		System.err.println("  WorldBuilderCli import --workspace <prepared-path>"
			+ " --export <export-directory> --target-root <private-server-root>"
			+ " (--dry-run | --apply)");
		System.err.println("  WorldBuilderCli undo-import --workspace <prepared-path>"
			+ " --target-root <private-server-root> (--dry-run | --apply)");
		System.err.println("  WorldBuilderCli export-import --workspace <prepared-path>"
			+ " --target-root <private-server-root> --builder-version <version>"
			+ " --source-commit <40-hex>");
		System.err.println("  WorldBuilderCli undo-latest-import --workspace <prepared-path>"
			+ " --target-root <private-server-root>");
		System.err.println("  WorldBuilderCli create-level --workspace <prepared-path>"
			+ " --level <signed-level> --anchor-x <x> --anchor-y <y>"
			+ " [--name <name>] [--role <role>]");
	}
}
