package com.openrsc.worldbuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Non-visual application model for the desktop launcher.  All project and
 * discovery mutations remain delegated to the existing validated lifecycle.
 */
final class WorldBuilderLauncherModel {
	private final Path installation;
	private final Path runtime;
	private final Path defaultTarget;
	private final int port;
	private final String configurationRole;

	WorldBuilderLauncherModel(Path installation, Path runtime, Path defaultTarget,
		int port, String configurationRole) throws IOException {
		this.installation = requireDirectory(installation, "World Builder installation");
		this.runtime = requireDirectory(runtime, "World Builder runtime");
		this.defaultTarget = defaultTarget == null ? null
			: requireDirectory(defaultTarget, "default server source");
		this.port = port;
		this.configurationRole = emptyToNull(configurationRole);
	}

	Path installation() {
		return installation;
	}

	Path defaultTarget() {
		return defaultTarget;
	}

	List<ProjectEntry> projects()
		throws IOException, WorldBuilderContractException, WorldBuilderDiscoveryException {
		Path registry = installation.resolve(
			WorldBuilderAdaptiveProjectLifecycle.REGISTRY_FILE);
		if (!Files.exists(registry, LinkOption.NOFOLLOW_LINKS)) {
			return Collections.emptyList();
		}
		String listed = new WorldBuilderAdaptiveProjectLifecycle().list(installation);
		Map<String,Object> document = WorldBuilderJsonDocuments.readObject(
			listed.getBytes(StandardCharsets.UTF_8), "project listing");
		String active = text(document.get("activeProjectId"));
		Object rawProjects = document.get("projects");
		if (!(rawProjects instanceof List)) {
			throw new WorldBuilderDiscoveryException(
				"Project listing did not contain a projects array.");
		}
		List<ProjectEntry> result = new ArrayList<ProjectEntry>();
		for (Object raw : (List<?>)rawProjects) {
			if (!(raw instanceof Map)) {
				throw new WorldBuilderDiscoveryException(
					"Project listing contained an invalid entry.");
			}
			@SuppressWarnings("unchecked") Map<String,Object> record =
				(Map<String,Object>)raw;
			String id = text(record.get("projectId"));
			Path projectRoot = installation.resolve("projects").resolve(id).normalize();
			new WorldBuilderProjectRevisionService().recover(projectRoot);
			ProjectProvenance provenance = projectProvenance(projectRoot);
			result.add(new ProjectEntry(id, text(record.get("displayName")),
				text(record.get("origin")), text(record.get("state")),
				projectRoot, id.equals(active), provenance.sourceDisplay,
				provenance.configurationPath, provenance.configurationSha256,
				provenance.workingFingerprint));
		}
		Collections.sort(result, new Comparator<ProjectEntry>() {
			@Override public int compare(ProjectEntry left, ProjectEntry right) {
				if (left.active != right.active) return left.active ? -1 : 1;
				int byName = left.displayName.compareToIgnoreCase(right.displayName);
				return byName != 0 ? byName : left.projectId.compareTo(right.projectId);
			}
		});
		return result;
	}

	private static ProjectProvenance projectProvenance(Path projectRoot)
		throws IOException, WorldBuilderDiscoveryException {
		Path path = projectRoot.resolve(WorldBuilderAdaptiveProjectLifecycle.PROJECT_FILE);
		if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(path)) {
			throw new IOException("Registered project manifest is missing or unsafe: "
				+ projectRoot.getFileName());
		}
		Map<String,Object> manifest = WorldBuilderJsonDocuments.readObject(path);
		Map<String,Object> target = object(manifest.get("target"));
		Map<String,Object> fingerprints = object(manifest.get("fingerprints"));
		return new ProjectProvenance(text(target.get("locatorDisplay")),
			text(target.get("selectedConfigurationRelativePath")),
			text(target.get("selectedConfigurationSha256")),
			text(fingerprints.get("workingSha256")));
	}

	DiscoveryPreview inspectDefaultTarget()
		throws IOException, WorldBuilderContractException {
		if (defaultTarget == null) {
			throw new IOException("This installation has no parent server/source folder.");
		}
		return inspectSource(defaultTarget);
	}

	DiscoveryPreview inspectSource(Path requestedSource)
		throws IOException, WorldBuilderContractException {
		return inspectSource(requestedSource, configurationRole);
	}

	DiscoveryPreview inspectSource(Path requestedSource, String selectedConfiguration)
		throws IOException, WorldBuilderContractException {
		Path source = requireDirectory(requestedSource, "server/map source folder");
		WorldBuilderAdaptiveDiscoveryReport report =
			new WorldBuilderAdaptiveDiscovery().discover(source,
				emptyToNull(selectedConfiguration));
		Map<String,Object> document;
		try {
			document = WorldBuilderJsonDocuments.readObject(
				report.toJson().getBytes(StandardCharsets.UTF_8), "discovery preview");
		} catch (WorldBuilderDiscoveryException invalid) {
			throw new IOException("Discovery produced an invalid preview.", invalid);
		}
		return new DiscoveryPreview(source, report, report.status,
			text(document.get("representation")), report.summary(),
			configurationChoices(document, source), issueCode(document),
			selectedConfigurationPath(document));
	}

	DiscoveryPreview inspectEmptyWorld()
		throws IOException, WorldBuilderContractException {
		WorldBuilderAdaptiveDiscoveryReport report =
			new WorldBuilderAdaptiveDiscovery().discover(installation, null);
		if (!"standalone".equals(report.status)) {
			throw new IOException("The application installation did not produce the "
				+ "required standalone-empty discovery contract.");
		}
		return new DiscoveryPreview(installation, report, report.status, "none",
			"A new standalone empty world will be created. No server map will be read or changed.",
			Collections.<ConfigurationChoice>emptyList(), "", "");
	}

	WorldBuilderLayeredBaseDiscovery.Discovery inspectLayeredBases(
		DiscoveryPreview preview) throws IOException {
		if (preview == null) throw new IOException("A server map preview was not supplied.");
		return new WorldBuilderLayeredBaseDiscovery().discover(
			preview.source, preview.selectedConfigurationPath);
	}

	LegacyMigrationPreview inspectLegacyMigration(DiscoveryPreview selected)
		throws IOException, WorldBuilderContractException {
		return inspectLegacyMigration(selected, null);
	}

	LegacyMigrationPreview inspectLegacyMigration(
		DiscoveryPreview selected, String requestedConfiguration)
		throws IOException, WorldBuilderContractException {
		if (selected == null || !selected.canCreateServerProject()) return null;
		if ("packed".equals(selected.representation)) {
			if (!WorldBuilderPackedMigrationChoice.applies(selected.report)) return null;
			WorldBuilderPackedMigrationChoice.create(selected.report, true);
			return new LegacyMigrationPreview(selected.report,
				Collections.<ConfigurationChoice>emptyList(), true);
		}
		if (!"layered".equals(selected.representation)) return null;
		WorldBuilderReadOnlyTarget target = WorldBuilderReadOnlyTarget.open(selected.source);
		boolean evidence = false;
		for (String root : WorldBuilderPackedSourceLayout.VIDEO_ROOTS) {
			evidence |= target.exists(root + "/Custom_Landscape.orsc");
		}
		for (String root : WorldBuilderPackedSourceLayout.DATA_ROOTS) {
			evidence |= target.exists(root + "/Custom_Landscape.orsc");
		}
		if (!evidence) return null;
		List<ConfigurationChoice> choices = legacyConfigurationChoices(target);
		if (requestedConfiguration == null && choices.size() > 1) {
			return new LegacyMigrationPreview(null, choices, false);
		}
		WorldBuilderAdaptiveDiscoveryReport legacy =
			new WorldBuilderLegacyLandscapeDiscovery().discover(
				selected.source, requestedConfiguration);
		WorldBuilderMapMigrationChoice.create(selected.report, legacy, true);
		return new LegacyMigrationPreview(legacy, choices, false);
	}

	private static List<ConfigurationChoice> legacyConfigurationChoices(
		WorldBuilderReadOnlyTarget target)
		throws IOException, WorldBuilderContractException {
		List<ConfigurationChoice> result = new ArrayList<ConfigurationChoice>();
		for (WorldBuilderAdapterInspection.ConfigurationCandidate candidate
			: WorldBuilderPackedSourceLayout.configurationCandidates(target)) {
			Path path = target.requiredFile(candidate.relativePath);
			result.add(new ConfigurationChoice(candidate.role, candidate.relativePath,
				candidate.sha256, Files.getLastModifiedTime(
					path, LinkOption.NOFOLLOW_LINKS).toMillis()));
		}
		Collections.sort(result);
		return Collections.unmodifiableList(result);
	}

	private static List<ConfigurationChoice> configurationChoices(
		Map<String,Object> document, Path source) throws IOException {
		Object raw = document.get("configurationCandidates");
		if (!(raw instanceof List)) return Collections.emptyList();
		List<ConfigurationChoice> result = new ArrayList<ConfigurationChoice>();
		for (Object entry : (List<?>)raw) {
			if (!(entry instanceof Map)) continue;
			@SuppressWarnings("unchecked") Map<String,Object> candidate =
				(Map<String,Object>)entry;
			String role = text(candidate.get("role"));
			String path = text(candidate.get("relativePath"));
			String sha256 = text(candidate.get("sha256"));
			if (!role.isEmpty() && !path.isEmpty()) {
				Path resolved = source.resolve(path).normalize();
				if (!resolved.startsWith(source)
					|| !Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)
					|| Files.isSymbolicLink(resolved)) {
					throw new IOException("Detected map configuration became unsafe: "
						+ path);
				}
				result.add(new ConfigurationChoice(role, path, sha256,
					Files.getLastModifiedTime(
						resolved, LinkOption.NOFOLLOW_LINKS).toMillis()));
			}
		}
		Collections.sort(result);
		return Collections.unmodifiableList(result);
	}

	private static String issueCode(Map<String,Object> document) {
		Object raw = document.get("issues");
		if (!(raw instanceof List) || ((List<?>)raw).isEmpty()
			|| !(((List<?>)raw).get(0) instanceof Map)) return "";
		@SuppressWarnings("unchecked") Map<String,Object> issue =
			(Map<String,Object>)((List<?>)raw).get(0);
		return text(issue.get("code"));
	}

	private static String selectedConfigurationPath(Map<String,Object> document) {
		return text(object(document.get("selectedConfiguration")).get("relativePath"));
	}

	WorldBuilderPortableProvider.Discovery inspectPortableProvider(Path source)
		throws IOException {
		return new WorldBuilderPortableProvider().discover(source, installation);
	}

	WorldBuilderPortableProvider.Discovery inspectPortableProvider(
		DiscoveryPreview preview) throws IOException {
		return new WorldBuilderPortableProvider().discover(preview.source,
			installation, preview.report.fingerprintSha256());
	}

	WorldBuilderPortableProvider.Provider importPortableProvider(Path source,
		WorldBuilderPortableProvider.GuidedSelection selection)
		throws IOException, WorldBuilderDiscoveryException {
		return new WorldBuilderPortableProvider().publishGuided(
			installation, source, selection);
	}

	WorldBuilderPortableProvider.Provider importPortableProvider(
		DiscoveryPreview preview, WorldBuilderPortableProvider.GuidedSelection selection)
		throws IOException, WorldBuilderDiscoveryException {
		return new WorldBuilderPortableProvider().publishGuided(
			installation, preview.source, selection,
			preview.report.fingerprintSha256());
	}

	Path exportPortableProviderDiagnostic(DiscoveryPreview preview) throws IOException {
		return new WorldBuilderPortableProvider().exportDiagnostic(
			installation, preview.source, preview.report.fingerprintSha256());
	}

	WorldBuilderPortableProvider.CacheReset resetPortableProviderCache(
		DiscoveryPreview preview) throws IOException, WorldBuilderDiscoveryException {
		return new WorldBuilderPortableProvider().resetCache(installation,
			preview.source, WorldBuilderPortableProvider.CACHE_RESET_CONFIRMATION);
	}

	WorldBuilderAdaptiveProjectLifecycle.ProjectResult create(
		DiscoveryPreview preview, String displayName)
		throws IOException, WorldBuilderContractException {
		return create(preview, displayName, null);
	}

	WorldBuilderAdaptiveProjectLifecycle.ProjectResult createMigrated(
		DiscoveryPreview selected, LegacyMigrationPreview migration,
		String displayName, Path itemVisualMappings)
		throws IOException, WorldBuilderContractException {
		return createMigrated(selected, migration, displayName, itemVisualMappings, null);
	}

	WorldBuilderAdaptiveProjectLifecycle.ProjectResult createMigrated(
		DiscoveryPreview selected, LegacyMigrationPreview migration,
		String displayName, Path itemVisualMappings, Path layeredBasePackage)
		throws IOException, WorldBuilderContractException {
		if (migration == null || migration.report == null) throw new IOException(
			"Legacy landscape incorporation was requested without a validated candidate.");
		if (migration.primaryPacked) {
			Path report = Files.createTempFile(
				installation, ".desktop-packed-discovery-", ".json");
			try {
				Files.write(report,
					selected.report.toJson().getBytes(StandardCharsets.UTF_8),
					StandardOpenOption.TRUNCATE_EXISTING);
				return new WorldBuilderAdaptiveProjectLifecycle().createPackedMigrated(
					installation, runtime, selected.source, report, displayName, port,
					"CREATE", itemVisualMappings, true, layeredBasePackage);
			} finally {
				Files.deleteIfExists(report);
			}
		}
		if (layeredBasePackage != null) throw new IOException(
			"A separately selected layered base applies only to a primary packed map.");
		Path selectedReport = Files.createTempFile(
			installation, ".desktop-selected-discovery-", ".json");
		Path legacyReport = Files.createTempFile(
			installation, ".desktop-legacy-discovery-", ".json");
		try {
			Files.write(selectedReport,
				selected.report.toJson().getBytes(StandardCharsets.UTF_8),
				StandardOpenOption.TRUNCATE_EXISTING);
			Files.write(legacyReport,
				migration.report.toJson().getBytes(StandardCharsets.UTF_8),
				StandardOpenOption.TRUNCATE_EXISTING);
			return new WorldBuilderAdaptiveProjectLifecycle().createMigrated(
				installation, runtime, selected.source, selectedReport, legacyReport,
				displayName, port, "CREATE", itemVisualMappings, true);
		} finally {
			Files.deleteIfExists(selectedReport);
			Files.deleteIfExists(legacyReport);
		}
	}

	WorldBuilderAdaptiveProjectLifecycle.ProjectResult create(
		DiscoveryPreview preview, String displayName, Path itemVisualMappings)
		throws IOException, WorldBuilderContractException {
		Path reportPath = Files.createTempFile(
			installation, ".desktop-discovery-", ".json");
		try {
			Files.write(reportPath, preview.report.toJson().getBytes(StandardCharsets.UTF_8),
				StandardOpenOption.TRUNCATE_EXISTING);
			Path target = "standalone".equals(preview.status) ? null : preview.source;
			return new WorldBuilderAdaptiveProjectLifecycle().create(
				installation, runtime, target, reportPath, displayName, port, "CREATE",
				itemVisualMappings);
		} finally {
			Files.deleteIfExists(reportPath);
		}
	}

	WorldBuilderAdaptiveProjectLifecycle.ProjectResult selectAndOpen(
		String projectId, Path possibleTarget)
		throws IOException, WorldBuilderContractException {
		WorldBuilderAdaptiveProjectLifecycle lifecycle =
			new WorldBuilderAdaptiveProjectLifecycle();
		lifecycle.select(installation, projectId);
		return lifecycle.openActive(installation, possibleTarget);
	}

	ProjectEntry projectFromChooser(Path selected)
		throws IOException, WorldBuilderContractException, WorldBuilderDiscoveryException {
		Path value = selected.toAbsolutePath().normalize();
		if (Files.isRegularFile(value, LinkOption.NOFOLLOW_LINKS)
			&& WorldBuilderAdaptiveProjectLifecycle.PROJECT_FILE.equals(
				value.getFileName().toString())) {
			value = value.getParent();
		}
		Path projectsRoot = installation.resolve(
			WorldBuilderAdaptiveProjectLifecycle.PROJECTS_DIRECTORY).normalize();
		if (!Files.isDirectory(value, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(value) || value.getParent() == null
			|| !value.getParent().equals(projectsRoot)) {
			throw new IOException("Choose a registered project folder inside "
				+ projectsRoot + ". External folders are not silently imported.");
		}
		for (ProjectEntry entry : projects()) {
			if (entry.projectRoot.equals(value)) return entry;
		}
		throw new IOException("That project folder is not registered in this "
			+ "World Builder installation.");
	}

	int run(WorldBuilderAdaptiveProjectLifecycle.ProjectResult project)
		throws IOException, WorldBuilderContractException,
			WorldBuilderDiscoveryException, InterruptedException {
		int exit = new WorldBuilderProcessSupervisor().runAdaptiveProject(project.projectRoot);
		if (exit == 0) {
			new WorldBuilderProjectRevisionService().create(project.projectRoot,
				"editing-session", "Saved editing session", true);
		}
		return exit;
	}

	RevisionListing projectRevisions(ProjectEntry entry)
		throws IOException, WorldBuilderContractException {
		if (entry == null) throw new IOException("Select one project to view backups.");
		List<WorldBuilderProjectRevisionService.Revision> revisions =
			new WorldBuilderProjectRevisionService().list(entry.projectRoot);
		List<ProjectRevisionEntry> result = new ArrayList<ProjectRevisionEntry>();
		java.util.Set<String> objects = new java.util.HashSet<String>();
		long stored = 0L;
		for (WorldBuilderProjectRevisionService.Revision revision : revisions) {
			result.add(revisionEntry(revision,
				revision.workingFingerprint.equals(entry.workingFingerprint)));
			for (WorldBuilderBoundedInventory.Record file : revision.files) {
				if (objects.add(file.sha256)) stored = Math.addExact(stored, file.size);
			}
		}
		return new RevisionListing(result, stored);
	}

	ProjectRevisionEntry createProjectBackup(ProjectEntry entry, String description)
		throws IOException, WorldBuilderContractException {
		if (entry == null) throw new IOException("Select one project to back up.");
		return revisionEntry(new WorldBuilderProjectRevisionService().create(
			entry.projectRoot, "explicit-backup", description, false), true);
	}

	String restoreProjectBackup(ProjectEntry entry, String revisionId)
		throws IOException, WorldBuilderContractException {
		if (entry == null) throw new IOException("Select one project to restore.");
		WorldBuilderProjectRevisionService.RestoreResult restored =
			new WorldBuilderProjectRevisionService().restore(
				entry.projectRoot, revisionId);
		if (!restored.changed) return "That backup is already the current project world.";
		return "Project backup loaded successfully.\n\nLoaded revision: "
			+ restored.restored.revisionId + "\nAutomatic pre-restore backup: "
			+ restored.safeguard.revisionId
			+ "\n\nNo server files were accessed or changed.";
	}

	Path exportProjectBackup(ProjectEntry entry, String revisionId)
		throws IOException, WorldBuilderContractException {
		if (entry == null) throw new IOException("Select one project backup to export.");
		return new WorldBuilderProjectRevisionService().export(
			entry.projectRoot, revisionId);
	}

	private static ProjectRevisionEntry revisionEntry(
		WorldBuilderProjectRevisionService.Revision revision, boolean current) {
		return new ProjectRevisionEntry(revision.revisionId, revision.createdAt,
			revision.reason, revision.description, revision.workingFingerprint,
			revision.fileCount, revision.totalBytes, current);
	}

	PreparedImport prepareServerImport(ProjectEntry entry)
		throws IOException, WorldBuilderContractException {
		if (entry == null) throw new IOException("Select one project before importing.");
		Path target = targetFor(entry);
		WorldBuilderAdaptiveExporter.ExportResult exported =
			new WorldBuilderAdaptiveExporter().export(entry.projectRoot);
		WorldBuilderAdaptiveImporter importer = new WorldBuilderAdaptiveImporter();
		WorldBuilderAdaptiveImporter.Preview preview = importer.preview(
			entry.projectRoot, exported.exportDirectory, target);
		return new PreparedImport(importer, preview, target);
	}

	Path exportCompleteMap(ProjectEntry entry)
		throws IOException, WorldBuilderContractException {
		if (entry == null) throw new IOException("Select one project before exporting.");
		return new WorldBuilderAdaptiveExporter().export(entry.projectRoot).exportDirectory;
	}

	String applyServerImport(PreparedImport prepared)
		throws IOException, WorldBuilderContractException {
		if (prepared == null) throw new IOException("Import preview was not supplied.");
		WorldBuilderAdaptiveImporter.ImportResult result =
			prepared.importer.apply(prepared.preview, "IMPORT");
		return "Map changes were imported successfully.\n\nTransaction: "
			+ result.transactionId + "\nReceipt: " + result.receiptPath;
	}

	PreparedUndo prepareServerUndo(ProjectEntry entry)
		throws IOException, WorldBuilderContractException {
		if (entry == null) throw new IOException("Select one project before undoing an import.");
		Path target = targetFor(entry);
		WorldBuilderAdaptiveUndo undo = new WorldBuilderAdaptiveUndo();
		return new PreparedUndo(undo, undo.preview(entry.projectRoot, target), target);
	}

	String applyServerUndo(PreparedUndo prepared)
		throws IOException, WorldBuilderContractException {
		if (prepared == null) throw new IOException("Undo preview was not supplied.");
		WorldBuilderAdaptiveUndo.UndoResult result =
			prepared.undo.apply(prepared.preview, "UNDO");
		return "The last map import was undone successfully.\n\nTransaction: "
			+ result.transactionId + "\nReceipt: " + result.receiptPath;
	}

	PreparedRecovery prepareServerRecovery(ProjectEntry entry)
		throws IOException, WorldBuilderContractException {
		if (entry == null) throw new IOException("Select one project before recovery.");
		Path target = targetFor(entry);
		WorldBuilderAdaptiveRecovery recovery = new WorldBuilderAdaptiveRecovery();
		return new PreparedRecovery(recovery,
			recovery.preview(entry.projectRoot, target), target);
	}

	String applyServerRecovery(PreparedRecovery prepared)
		throws IOException, WorldBuilderContractException {
		if (prepared == null) throw new IOException("Recovery preview was not supplied.");
		WorldBuilderAdaptiveRecovery.RecoveryResult result =
			prepared.recovery.apply(prepared.preview, "RECOVER");
		return "Interrupted map import recovery completed successfully.\n\nTransaction: "
			+ result.transactionId + "\nReceipt: " + result.receiptPath;
	}

	private Path targetFor(ProjectEntry entry) throws IOException {
		if (!entry.sourceDisplay.isEmpty()) {
			try {
				Path recorded = java.nio.file.Paths.get(entry.sourceDisplay);
				if (recorded.isAbsolute()) {
					return requireDirectory(recorded, "project's recorded server target");
				}
			} catch (java.nio.file.InvalidPathException invalid) {
				throw new IOException("The project's recorded server target is invalid.", invalid);
			}
		}
		if (defaultTarget != null) return defaultTarget;
		throw new IOException("This project has no available server target. Standalone "
			+ "projects can be edited and exported, but cannot overwrite a server.");
	}

	private static Path requireDirectory(Path requested, String label) throws IOException {
		if (requested == null) throw new IOException(label + " was not supplied.");
		Path normalized = requested.toAbsolutePath().normalize();
		if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(normalized)) {
			throw new IOException(label + " is missing or unsafe: " + normalized);
		}
		return normalized.toRealPath();
	}

	private static String text(Object value) {
		return value instanceof String ? (String)value : "";
	}

	private static Map<String,Object> object(Object value) {
		if (!(value instanceof Map)) return Collections.emptyMap();
		@SuppressWarnings("unchecked") Map<String,Object> result =
			(Map<String,Object>)value;
		return result;
	}

	private static String emptyToNull(String value) {
		return value == null || value.trim().isEmpty() ? null : value.trim();
	}

	static final class ProjectEntry {
		final String projectId;
		final String displayName;
		final String origin;
		final String state;
		final Path projectRoot;
		final boolean active;
		final String sourceDisplay;
		final String configurationPath;
		final String configurationSha256;
		final String workingFingerprint;

		ProjectEntry(String projectId, String displayName, String origin,
			String state, Path projectRoot, boolean active, String sourceDisplay,
			String configurationPath, String configurationSha256,
			String workingFingerprint) {
			this.projectId = projectId;
			this.displayName = displayName;
			this.origin = origin;
			this.state = state;
			this.projectRoot = projectRoot;
			this.active = active;
			this.sourceDisplay = sourceDisplay;
			this.configurationPath = configurationPath;
			this.configurationSha256 = configurationSha256;
			this.workingFingerprint = workingFingerprint;
		}

		@Override public String toString() {
			return (active ? "▶ " : "   ") + displayName;
		}
	}

	static final class RevisionListing {
		final List<ProjectRevisionEntry> revisions;
		final long storedObjectBytes;

		RevisionListing(List<ProjectRevisionEntry> revisions, long storedObjectBytes) {
			this.revisions = Collections.unmodifiableList(
				new ArrayList<ProjectRevisionEntry>(revisions));
			this.storedObjectBytes = storedObjectBytes;
		}
	}

	static final class ProjectRevisionEntry {
		final String revisionId;
		final String createdAt;
		final String reason;
		final String description;
		final String workingFingerprint;
		final long fileCount;
		final long totalBytes;
		final boolean current;

		ProjectRevisionEntry(String revisionId, String createdAt, String reason,
			String description, String workingFingerprint, long fileCount,
			long totalBytes, boolean current) {
			this.revisionId = revisionId;
			this.createdAt = createdAt;
			this.reason = reason;
			this.description = description;
			this.workingFingerprint = workingFingerprint;
			this.fileCount = fileCount;
			this.totalBytes = totalBytes;
			this.current = current;
		}

		@Override public String toString() {
			String label = reason.replace('-', ' ');
			return (current ? "Current — " : "") + createdAt + " — " + label
				+ (description.isEmpty() ? "" : " — " + description);
		}
	}

	static final class PreparedImport {
		final WorldBuilderAdaptiveImporter importer;
		final WorldBuilderAdaptiveImporter.Preview preview;
		final Path target;

		PreparedImport(WorldBuilderAdaptiveImporter importer,
			WorldBuilderAdaptiveImporter.Preview preview, Path target) {
			this.importer = importer;
			this.preview = preview;
			this.target = target;
		}

		String summary() {
			return preview.humanSummary() + "\nServer target: " + target;
		}
	}

	static final class PreparedUndo {
		final WorldBuilderAdaptiveUndo undo;
		final WorldBuilderAdaptiveUndo.Preview preview;
		final Path target;

		PreparedUndo(WorldBuilderAdaptiveUndo undo,
			WorldBuilderAdaptiveUndo.Preview preview, Path target) {
			this.undo = undo;
			this.preview = preview;
			this.target = target;
		}

		String summary() {
			return preview.humanSummary() + "\nServer target: " + target;
		}
	}

	static final class PreparedRecovery {
		final WorldBuilderAdaptiveRecovery recovery;
		final WorldBuilderAdaptiveRecovery.Preview preview;
		final Path target;

		PreparedRecovery(WorldBuilderAdaptiveRecovery recovery,
			WorldBuilderAdaptiveRecovery.Preview preview, Path target) {
			this.recovery = recovery;
			this.preview = preview;
			this.target = target;
		}

		String summary() {
			return preview.humanSummary() + "\nServer target: " + target;
		}
	}

	private static final class ProjectProvenance {
		final String sourceDisplay;
		final String configurationPath;
		final String configurationSha256;
		final String workingFingerprint;

		ProjectProvenance(String sourceDisplay, String configurationPath,
			String configurationSha256, String workingFingerprint) {
			this.sourceDisplay = sourceDisplay;
			this.configurationPath = configurationPath;
			this.configurationSha256 = configurationSha256;
			this.workingFingerprint = workingFingerprint;
		}
	}

	static final class DiscoveryPreview {
		final Path source;
		final WorldBuilderAdaptiveDiscoveryReport report;
		final String status;
		final String representation;
		final String summary;
		final List<ConfigurationChoice> configurationChoices;
		final String issueCode;
		final String selectedConfigurationPath;

		DiscoveryPreview(Path source, WorldBuilderAdaptiveDiscoveryReport report,
			String status, String representation, String summary,
			List<ConfigurationChoice> configurationChoices, String issueCode,
			String selectedConfigurationPath) {
			this.source = source;
			this.report = report;
			this.status = status;
			this.representation = representation;
			this.summary = summary;
			this.configurationChoices = configurationChoices;
			this.issueCode = issueCode;
			this.selectedConfigurationPath = selectedConfigurationPath;
		}

		boolean canCreateServerProject() {
			return "compatible".equals(status);
		}

		boolean needsConfigurationChoice() {
			return "blocked".equals(status)
				&& WorldBuilderErrorCodes.AMBIGUOUS_CONFIGURATION.equals(issueCode)
				&& configurationChoices.size() > 1;
		}
	}

	static final class LegacyMigrationPreview {
		final WorldBuilderAdaptiveDiscoveryReport report;
		final List<ConfigurationChoice> configurationChoices;
		final boolean primaryPacked;

		LegacyMigrationPreview(WorldBuilderAdaptiveDiscoveryReport report,
			List<ConfigurationChoice> configurationChoices, boolean primaryPacked) {
			this.report = report;
			this.configurationChoices = configurationChoices;
			this.primaryPacked = primaryPacked;
		}

		boolean needsConfigurationChoice() {
			return report == null && configurationChoices.size() > 1;
		}
	}

	static final class ConfigurationChoice implements Comparable<ConfigurationChoice> {
		final String role;
		final String relativePath;
		final String sha256;
		final long modifiedMillis;

		ConfigurationChoice(String role, String relativePath, String sha256,
			long modifiedMillis) {
			this.role = role;
			this.relativePath = relativePath;
			this.sha256 = sha256;
			this.modifiedMillis = modifiedMillis;
		}

		static ConfigurationChoice mostRecentlyModified(
			List<ConfigurationChoice> choices) {
			ConfigurationChoice result = null;
			for (ConfigurationChoice choice : choices) {
				if (result == null || choice.modifiedMillis > result.modifiedMillis
					|| choice.modifiedMillis == result.modifiedMillis
						&& choice.relativePath.compareTo(result.relativePath) < 0) {
					result = choice;
				}
			}
			return result;
		}

		@Override public int compareTo(ConfigurationChoice other) {
			int byPath = relativePath.compareTo(other.relativePath);
			return byPath != 0 ? byPath : role.compareTo(other.role);
		}

		@Override public String toString() {
			String hash = sha256.matches("[0-9a-f]{64}")
				? sha256.substring(0, 12) : "unavailable";
			return relativePath + "  —  modified "
				+ WorldBuilderDesktopLauncher.displayTime(modifiedMillis)
				+ "  —  " + hash;
		}
	}
}
