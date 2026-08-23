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
			result.add(new ProjectEntry(id, text(record.get("displayName")),
				text(record.get("origin")), text(record.get("state")),
				installation.resolve("projects").resolve(id).normalize(),
				id.equals(active)));
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

	DiscoveryPreview inspectDefaultTarget()
		throws IOException, WorldBuilderContractException {
		if (defaultTarget == null) {
			throw new IOException("This installation has no parent server/source folder.");
		}
		return inspectSource(defaultTarget);
	}

	DiscoveryPreview inspectSource(Path requestedSource)
		throws IOException, WorldBuilderContractException {
		Path source = requireDirectory(requestedSource, "server/map source folder");
		WorldBuilderAdaptiveDiscoveryReport report =
			new WorldBuilderAdaptiveDiscovery().discover(source, configurationRole);
		Map<String,Object> document;
		try {
			document = WorldBuilderJsonDocuments.readObject(
				report.toJson().getBytes(StandardCharsets.UTF_8), "discovery preview");
		} catch (WorldBuilderDiscoveryException invalid) {
			throw new IOException("Discovery produced an invalid preview.", invalid);
		}
		return new DiscoveryPreview(source, report, report.status,
			text(document.get("representation")), report.summary());
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
			"A new standalone empty world will be created. No server map will be read or changed.");
	}

	WorldBuilderAdaptiveProjectLifecycle.ProjectResult create(
		DiscoveryPreview preview, String displayName)
		throws IOException, WorldBuilderContractException {
		Path reportPath = Files.createTempFile(
			installation, ".desktop-discovery-", ".json");
		try {
			Files.write(reportPath, preview.report.toJson().getBytes(StandardCharsets.UTF_8),
				StandardOpenOption.TRUNCATE_EXISTING);
			Path target = "standalone".equals(preview.status) ? null : preview.source;
			return new WorldBuilderAdaptiveProjectLifecycle().create(
				installation, runtime, target, reportPath, displayName, port, "CREATE");
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
		return new WorldBuilderProcessSupervisor().runAdaptiveProject(project.projectRoot);
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

		ProjectEntry(String projectId, String displayName, String origin,
			String state, Path projectRoot, boolean active) {
			this.projectId = projectId;
			this.displayName = displayName;
			this.origin = origin;
			this.state = state;
			this.projectRoot = projectRoot;
			this.active = active;
		}

		@Override public String toString() {
			return (active ? "▶ " : "   ") + displayName;
		}
	}

	static final class DiscoveryPreview {
		final Path source;
		final WorldBuilderAdaptiveDiscoveryReport report;
		final String status;
		final String representation;
		final String summary;

		DiscoveryPreview(Path source, WorldBuilderAdaptiveDiscoveryReport report,
			String status, String representation, String summary) {
			this.source = source;
			this.report = report;
			this.status = status;
			this.representation = representation;
			this.summary = summary;
		}

		boolean canCreateServerProject() {
			return "compatible".equals(status);
		}
	}
}
