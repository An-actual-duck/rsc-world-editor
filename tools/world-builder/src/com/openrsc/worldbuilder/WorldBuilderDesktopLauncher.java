package com.openrsc.worldbuilder;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;

/** Persistent, dependency-free Swing shell for project creation and launch. */
final class WorldBuilderDesktopLauncher {
	private static final DateTimeFormatter DISPLAY_TIME =
		DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
			.withZone(ZoneId.systemDefault());
	private final Ui scriptedUi;
	private final ProjectRunner scriptedRunner;

	WorldBuilderDesktopLauncher(Ui ui, ProjectRunner runner) {
		this.scriptedUi = ui;
		this.scriptedRunner = runner;
	}

	static String displayTime(long millis) {
		return millis < 0L ? "unknown"
			: DISPLAY_TIME.format(Instant.ofEpochMilli(millis));
	}

	static int run(String[] args) {
		Options options;
		try {
			options = Options.parse(args);
		} catch (IllegalArgumentException invalid) {
			System.err.println("ERROR: " + invalid.getMessage());
			return 2;
		}
		return launch(options);
	}

	static int launch(Options options) {
		if (GraphicsEnvironment.isHeadless()) {
			System.err.println("ERROR: The World Builder desktop launcher needs a graphical "
				+ "desktop. For terminal recovery or automation, use launch-adaptive.");
			return 4;
		}
		try {
			final WorldBuilderLauncherModel model = new WorldBuilderLauncherModel(
				options.installation, options.runtime, options.target,
				options.port, options.configurationRole);
			final CountDownLatch closed = new CountDownLatch(1);
			final AtomicInteger result = new AtomicInteger(0);
			SwingUtilities.invokeAndWait(new Runnable() {
				@Override public void run() {
					try {
						UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
					} catch (Exception ignored) {
						// The cross-platform Swing appearance remains fully supported.
					}
					new Window(model, closed, result).show();
				}
			});
			closed.await();
			return result.get();
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			return 130;
		} catch (Exception failure) {
			System.err.println("ERROR: The desktop launcher could not start: "
				+ usefulMessage(failure));
			return 4;
		}
	}

	int run(Options options) {
		if (scriptedUi == null || scriptedRunner == null) {
			throw new IllegalStateException("A testable launcher requires UI and runner boundaries.");
		}
		try {
			WorldBuilderLauncherModel model = new WorldBuilderLauncherModel(
				options.installation, options.runtime, options.target,
				options.port, options.configurationRole);
			List<WorldBuilderLauncherModel.ProjectEntry> entries = model.projects();
			List<ProjectChoice> choices = choices(entries);
			WorldBuilderLauncherModel.DiscoveryPreview detected = null;
			String detectedSummary;
			boolean detectedSupported;
			try {
				detected = model.inspectDefaultTarget();
				detectedSummary = detected.summary;
				detectedSupported = detected.canCreateServerProject();
			} catch (Exception unavailable) {
				detectedSummary = "The adjacent server/map source could not be inspected: "
					+ usefulMessage(unavailable);
				detectedSupported = false;
			}
			Action action = scriptedUi.chooseAction(choices,
				detectedSummary, detectedSupported);
			if (action == null || action == Action.CANCEL) return 0;
			if (action == Action.OPEN_EXISTING) {
				ProjectChoice selected = scriptedUi.chooseProject(choices);
				if (selected == null) return 0;
				WorldBuilderAdaptiveProjectLifecycle.ProjectResult opened =
					model.selectAndOpen(selected.projectId, options.target);
				return scriptedRunner.run(opened.projectRoot);
			}
			WorldBuilderLauncherModel.DiscoveryPreview preview;
			String suggested;
			if (action == Action.NEW_EMPTY) {
				preview = model.inspectEmptyWorld();
				suggested = "New World";
			} else if (action == Action.DETECTED_SERVER) {
				preview = detected == null ? model.inspectDefaultTarget() : detected;
				suggested = "Imported Server Map";
			} else if (action == Action.SELECT_SOURCE) {
				Path selected = scriptedUi.chooseSource(options.target);
				if (selected == null) return 0;
				preview = model.inspectSource(selected);
				suggested = "Imported Server Map";
			} else {
				return 2;
			}
			if (action != Action.NEW_EMPTY && !preview.canCreateServerProject()) {
				scriptedUi.showError("Unsupported source", preview.summary);
				return 3;
			}
			WorldBuilderLauncherModel.LegacyMigrationPreview migration =
				action == Action.NEW_EMPTY ? null : model.inspectLegacyMigration(preview);
			if (migration != null && migration.needsConfigurationChoice()) {
				throw new IOException("More than one legacy map configuration was found; "
					+ "choose the exact Custom_Landscape map in the desktop launcher.");
			}
			boolean incorporateLegacy = migration != null
				&& scriptedUi.confirmLegacyLandscapeIncorporation();
			boolean keepLayeredAuthority = migration != null
				&& !migration.primaryPacked && !incorporateLegacy;
			String displayName = scriptedUi.requestDisplayName(suggested);
			if (displayName == null || displayName.trim().isEmpty()) return 0;
			if (!scriptedUi.confirmCreation(
				action == Action.NEW_EMPTY ? "Create New Project"
					: "Create Isolated Project from Server Map",
				preview.summary)) return 0;
			WorldBuilderAdaptiveProjectLifecycle.ProjectResult created = incorporateLegacy
				? model.createMigrated(preview, migration, displayName.trim(), null)
				: keepLayeredAuthority
					? model.createKeepLayered(preview, migration, displayName.trim(), null)
					: model.create(preview, displayName.trim());
			return scriptedRunner.run(created.projectRoot);
		} catch (Exception failure) {
			try {
				scriptedUi.showError("World Builder could not complete that action",
					usefulMessage(failure));
			} catch (RuntimeException propagated) {
				throw propagated;
			}
			return 4;
		}
	}

	private static List<ProjectChoice> choices(
		List<WorldBuilderLauncherModel.ProjectEntry> entries) {
		java.util.ArrayList<ProjectChoice> result =
			new java.util.ArrayList<ProjectChoice>();
		for (WorldBuilderLauncherModel.ProjectEntry entry : entries) {
			result.add(new ProjectChoice(entry.projectId, entry.displayName,
				entry.origin, entry.state, entry.projectRoot, entry.active));
		}
		return java.util.Collections.unmodifiableList(result);
	}

	static CloseDisposition closeDisposition(boolean editorRunning, boolean busy) {
		if (editorRunning) return CloseDisposition.WAIT_FOR_EDITOR;
		if (busy) return CloseDisposition.WAIT_FOR_TASK;
		return CloseDisposition.CLOSE;
	}

	static WorldBuilderPortableProvider.GuidedSelection completeProviderSelection(
		WorldBuilderPortableProvider.Discovery discovery) throws IOException {
		if (discovery == null
			|| discovery.status != WorldBuilderPortableProvider.Status.EXPLICIT
			|| discovery.selected == null) {
			throw new IOException("Choose a complete world-builder-provider folder. "
				+ "It must contain item-visuals.json or package-manifest-v1.json.");
		}
		return selectionFrom(discovery.selected);
	}

	private static WorldBuilderPortableProvider.GuidedSelection selectionFrom(
		WorldBuilderPortableProvider.Candidate candidate) {
		return new WorldBuilderPortableProvider.GuidedSelection(candidate.itemVisuals,
			candidate.definitions, candidate.authenticArchive, candidate.customArchive,
			candidate.spritepacks, candidate.externalItems);
	}

	enum CloseDisposition {
		CLOSE, WAIT_FOR_EDITOR, WAIT_FOR_TASK
	}

	enum Action {
		OPEN_EXISTING, NEW_EMPTY, DETECTED_SERVER, SELECT_SOURCE, CANCEL
	}

	interface Ui {
		Action chooseAction(List<ProjectChoice> projects, String summary,
			boolean supported);
		ProjectChoice chooseProject(List<ProjectChoice> projects);
		Path chooseSource(Path initial);
		String requestDisplayName(String suggested);
		boolean confirmCreation(String title, String summary);
		default boolean confirmLegacyLandscapeIncorporation() { return false; }
		void showError(String title, String message);
	}

	interface ProjectRunner {
		int run(Path project) throws Exception;
	}

	static final class ProjectChoice {
		final String projectId;
		final String displayName;
		final String origin;
		final String state;
		final Path projectRoot;
		final boolean active;

		ProjectChoice(String projectId, String displayName, String origin,
			String state, Path projectRoot, boolean active) {
			this.projectId = projectId;
			this.displayName = displayName;
			this.origin = origin;
			this.state = state;
			this.projectRoot = projectRoot;
			this.active = active;
		}
	}

	private static final class Window {
		private final WorldBuilderLauncherModel model;
		private final CountDownLatch closed;
		private final AtomicInteger result;
		private final LauncherSettings settings;
		private final JFrame frame = new JFrame("World Builder 2");
		private final DefaultListModel<WorldBuilderLauncherModel.ProjectEntry> projects =
			new DefaultListModel<WorldBuilderLauncherModel.ProjectEntry>();
		private final JList<WorldBuilderLauncherModel.ProjectEntry> projectList =
			new JList<WorldBuilderLauncherModel.ProjectEntry>(projects);
		private final JTextArea details = readOnlyText();
		private final JLabel status = new JLabel("Ready");
		private final JButton open = new JButton("Continue Working on Selected Project");
		private final JButton installedSource = new JButton("Detect Server Map");
		private final JButton importToServer =
			new JButton("Upgrade Server and Import Map");
		private final JButton restoreBackup = new JButton("Restore Project Backup");
		private volatile boolean busy;
		private volatile boolean editorRunning;

		Window(WorldBuilderLauncherModel model, CountDownLatch closed,
			AtomicInteger result) {
			this.model = model;
			this.closed = closed;
			this.result = result;
			this.settings = new LauncherSettings(model.installation());
			build();
			refreshProjects(null);
		}

		void show() {
			frame.setVisible(true);
		}

		private void build() {
			frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
			frame.setMinimumSize(new Dimension(860, 590));
			frame.setSize(940, 650);
			frame.setLocationByPlatform(true);
			frame.addWindowListener(new WindowAdapter() {
				@Override public void windowClosing(WindowEvent event) {
					closeRequested();
				}
			});

			JMenu file = new JMenu("File");
			file.add(menu("Continue Working on Selected Project", new Runnable() {
				@Override public void run() { openSelected(); }
			}));
			file.add(menu("Detect Server Map", new Runnable() {
				@Override public void run() { inspectInstalledSource(); }
			}));
			file.add(new JSeparator());
			file.add(menu("Export Selected Project Complete Map Package…", new Runnable() {
				@Override public void run() { exportSelectedProject(); }
			}));
			file.add(menu("Project Backups…", new Runnable() {
				@Override public void run() { openProjectBackups(); }
			}));
			file.add(menu("Import Selected Project Map Changes to Server…", new Runnable() {
				@Override public void run() { importSelectedProject(); }
			}));
			file.add(new JSeparator());
			file.add(menu("Exit", new Runnable() {
				@Override public void run() { closeRequested(); }
			}));
			JMenuBar menuBar = new JMenuBar();
			menuBar.add(file);
			JMenu recovery = new JMenu("Advanced / Recovery");
			recovery.add(menu("Detected Server Content Options…", new Runnable() {
				@Override public void run() { inspectInstalledSource(true); }
			}));
			recovery.add(menu("Select Another Supported Source…", new Runnable() {
				@Override public void run() { chooseSource(); }
			}));
			recovery.add(menu("Browse Existing Project Folders…", new Runnable() {
				@Override public void run() { chooseExistingProject(); }
			}));
			recovery.add(menu("Refresh Project List", new Runnable() {
				@Override public void run() { refreshProjects(null); }
			}));
			recovery.add(new JSeparator());
			recovery.add(menu("Recover Interrupted Server Map Import…", new Runnable() {
				@Override public void run() { recoverSelectedProjectImport(); }
			}));
			recovery.add(new JSeparator());
			recovery.add(menu("Export Detected Server Diagnostics", new Runnable() {
				@Override public void run() { exportDetectedProviderDiagnostic(); }
			}));
			recovery.add(menu("Reset Detected Server Provider Cache", new Runnable() {
				@Override public void run() { resetDetectedProviderCache(); }
			}));
			menuBar.add(recovery);
			frame.setJMenuBar(menuBar);

			JPanel heading = new JPanel();
			heading.setLayout(new BoxLayout(heading, BoxLayout.Y_AXIS));
			heading.setBorder(BorderFactory.createEmptyBorder(18, 20, 12, 20));
			JLabel title = new JLabel("World Builder 2");
			title.setFont(title.getFont().deriveFont(Font.BOLD, 25f));
			heading.add(title);
			heading.add(Box.createVerticalStrut(5));
			heading.add(new JLabel("Continue a project or detect the server map beside "
				+ "this application."));
			frame.add(heading, BorderLayout.NORTH);

			projectList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
			projectList.setCellRenderer(new ProjectRenderer());
			projectList.addListSelectionListener(event -> {
				if (!event.getValueIsAdjusting()) showSelectedDetails();
			});
			projectList.addMouseListener(new java.awt.event.MouseAdapter() {
				@Override public void mouseClicked(java.awt.event.MouseEvent event) {
					if (event.getClickCount() == 2) openSelected();
				}
			});

			JPanel center = new JPanel(new GridBagLayout());
			center.setBorder(BorderFactory.createEmptyBorder(4, 20, 14, 20));
			GridBagConstraints constraints = new GridBagConstraints();
			constraints.insets = new Insets(5, 5, 5, 5);
			constraints.fill = GridBagConstraints.BOTH;
			constraints.weightx = 0.54;
			constraints.weighty = 1;
			constraints.gridx = 0;
			constraints.gridy = 0;
			center.add(titled("Your Projects", new JScrollPane(projectList)), constraints);
			constraints.weightx = 0.46;
			constraints.gridx = 1;
			center.add(titled("Project Details", new JScrollPane(details)), constraints);
			frame.add(center, BorderLayout.CENTER);

			open.addActionListener(event -> openSelected());
			installedSource.addActionListener(event -> inspectInstalledSource());
			importToServer.addActionListener(event -> importSelectedProject());
			restoreBackup.addActionListener(event -> openProjectBackups());
			for (JButton primary : new JButton[] {installedSource, open}) {
				primary.setFont(primary.getFont().deriveFont(Font.BOLD));
				primary.setPreferredSize(new Dimension(245, 44));
			}
			installedSource.setToolTipText(
				"Find and safely copy the map from the server beside World Builder.");
			open.setToolTipText("Open the selected project and continue editing.");
			importToServer.setToolTipText(
				"Preview, back up, and safely install the selected project's map into its server.");
			restoreBackup.setToolTipText(
				"Load an earlier backup into the selected project without changing its server.");
			importToServer.setEnabled(false);
			restoreBackup.setEnabled(false);

			JPanel actions = new JPanel(new GridBagLayout());
			actions.setBorder(BorderFactory.createEmptyBorder(0, 20, 12, 20));
			GridBagConstraints action = new GridBagConstraints();
			action.insets = new Insets(4, 4, 4, 4);
			action.fill = GridBagConstraints.HORIZONTAL;
			action.weightx = 1;
			action.gridx = 0; action.gridy = 0;
			actions.add(installedSource, action);
			action.gridx = 1;
			actions.add(open, action);

			JPanel selectedProjectActions = new JPanel(new GridBagLayout());
			selectedProjectActions.setBorder(BorderFactory.createTitledBorder(
				"Selected Project Actions"));
			GridBagConstraints selectedAction = new GridBagConstraints();
			selectedAction.insets = new Insets(4, 8, 6, 8);
			selectedAction.fill = GridBagConstraints.HORIZONTAL;
			selectedAction.weightx = 1;
			selectedAction.gridx = 0;
			selectedAction.gridy = 0;
			selectedProjectActions.add(importToServer, selectedAction);
			selectedAction.gridx = 1;
			selectedProjectActions.add(restoreBackup, selectedAction);

			JPanel actionRows = new JPanel();
			actionRows.setLayout(new BoxLayout(actionRows, BoxLayout.Y_AXIS));
			actionRows.add(actions);
			actionRows.add(selectedProjectActions);

			JPanel bottom = new JPanel(new BorderLayout());
			bottom.add(actionRows, BorderLayout.CENTER);
			status.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(210, 210, 210)),
				BorderFactory.createEmptyBorder(8, 20, 9, 20)));
			bottom.add(status, BorderLayout.SOUTH);
			frame.add(bottom, BorderLayout.SOUTH);
		}

		private static JPanel titled(String title, Component contents) {
			JPanel panel = new JPanel(new BorderLayout());
			panel.setBorder(BorderFactory.createTitledBorder(title));
			panel.add(contents, BorderLayout.CENTER);
			return panel;
		}

		private JMenuItem menu(String label, final Runnable action) {
			JMenuItem item = new JMenuItem(label);
			item.addActionListener(event -> action.run());
			return item;
		}

		private void refreshProjects(final String selectId) {
			runTask("Checking projects…", new Task<List<WorldBuilderLauncherModel.ProjectEntry>>() {
				@Override public List<WorldBuilderLauncherModel.ProjectEntry> run()
					throws Exception { return model.projects(); }
			}, new Success<List<WorldBuilderLauncherModel.ProjectEntry>>() {
				@Override public void accept(List<WorldBuilderLauncherModel.ProjectEntry> entries) {
					projects.clear();
					int selected = -1;
					for (int index = 0; index < entries.size(); index++) {
						WorldBuilderLauncherModel.ProjectEntry entry = entries.get(index);
						projects.addElement(entry);
						if (selectId != null && selectId.equals(entry.projectId)
							|| selectId == null && entry.active) selected = index;
					}
					if (selected < 0 && !entries.isEmpty()) selected = 0;
					if (selected >= 0) projectList.setSelectedIndex(selected);
					else {
						details.setText("No projects are registered in this installation.\n\n"
							+ "Choose Detect Server Map to copy the adjacent server into an "
							+ "isolated project.\n"
							+ "Nothing in a server is overwritten during project creation or editing.");
						frame.getRootPane().setDefaultButton(installedSource);
					}
					status.setText(entries.isEmpty() ? "Ready — no projects yet"
						: "Ready — " + entries.size() + " project"
							+ (entries.size() == 1 ? "" : "s"));
				}
			});
		}

		private void showSelectedDetails() {
			WorldBuilderLauncherModel.ProjectEntry entry = projectList.getSelectedValue();
			open.setEnabled(!busy && entry != null);
			importToServer.setEnabled(!busy && entry != null);
			restoreBackup.setEnabled(!busy && entry != null);
			if (entry == null) return;
			frame.getRootPane().setDefaultButton(open);
			details.setText("Project: " + entry.displayName
				+ "\nWorld type: " + originLabel(entry.origin)
				+ "\nStatus: " + entry.state
				+ "\nCurrent project: " + (entry.active ? "Yes" : "No")
				+ (entry.sourceDisplay.isEmpty() ? ""
					: "\nImported from: " + entry.sourceDisplay)
				+ (entry.configurationPath.isEmpty() ? ""
					: "\nMap configuration: " + entry.configurationPath)
				+ (entry.configurationSha256.matches("[0-9a-f]{64}")
					? "\nConfiguration hash: "
						+ entry.configurationSha256.substring(0, 12) : "")
				+ "\n\nOpening validates the complete project before starting its private "
				+ "client and server. Editing remains isolated here until you explicitly "
				+ "run Upgrade Server and Import Map. That action backs up the target, "
				+ "installs the current managed runtime when needed, and activates the "
				+ "edited map in one transaction.\n\n"
				+ "This project is a fixed imported snapshot; it is never silently mixed "
				+ "with newer server files. If the server map has changed, use Detect "
				+ "Server Map to create a fresh isolated project.");
			details.setCaretPosition(0);
		}

		private void openSelected() {
			if (busy) return;
			final WorldBuilderLauncherModel.ProjectEntry entry = projectList.getSelectedValue();
			if (entry == null) {
				showError("Select a project to open.", null);
				return;
			}
			launchProject(entry.projectId, model.defaultTarget());
		}

		private void importSelectedProject() {
			final WorldBuilderLauncherModel.ProjectEntry entry =
				selectedForServerAction("import map changes");
			if (entry == null) return;
			runTask("Exporting the selected project and preparing an exact import preview…",
				new Task<WorldBuilderLauncherModel.PreparedImport>() {
					@Override public WorldBuilderLauncherModel.PreparedImport run()
						throws Exception { return model.prepareServerImport(entry); }
				}, new Success<WorldBuilderLauncherModel.PreparedImport>() {
					@Override public void accept(
						final WorldBuilderLauncherModel.PreparedImport prepared) {
						if (!confirmTransaction("Upgrade Server and Import Map",
							"Upgrade and Import", prepared.summary())) return;
						runTask("Backing up the server, applying its managed runtime, and "
							+ "importing the reviewed map…",
							new Task<String>() {
								@Override public String run() throws Exception {
									return model.applyServerImport(prepared);
								}
							}, transactionSuccess(entry.projectId,
								"Server Upgrade and Map Import Complete"));
					}
				});
		}

		private void exportSelectedProject() {
			final WorldBuilderLauncherModel.ProjectEntry entry =
				selectedForServerAction("export the complete map");
			if (entry == null) return;
			runTask("Validating and exporting the complete project map…",
				new Task<Path>() {
					@Override public Path run() throws Exception {
						return model.exportCompleteMap(entry);
					}
				}, new Success<Path>() {
					@Override public void accept(Path exported) {
						String message = "The complete immutable map package was exported to:\n\n"
							+ exported;
						details.setText(message);
						details.setCaretPosition(0);
						JOptionPane.showMessageDialog(frame, message,
							"Complete Map Exported", JOptionPane.INFORMATION_MESSAGE);
					}
				});
		}

		private void openProjectBackups() {
			final WorldBuilderLauncherModel.ProjectEntry entry =
				selectedForServerAction("manage project backups");
			if (entry == null) return;
			runTask("Verifying project backup history…",
				new Task<WorldBuilderLauncherModel.RevisionListing>() {
					@Override public WorldBuilderLauncherModel.RevisionListing run()
						throws Exception { return model.projectRevisions(entry); }
				}, new Success<WorldBuilderLauncherModel.RevisionListing>() {
					@Override public void accept(
						WorldBuilderLauncherModel.RevisionListing listing) {
						showProjectBackups(entry, listing);
					}
				});
		}

		private void showProjectBackups(final WorldBuilderLauncherModel.ProjectEntry entry,
			WorldBuilderLauncherModel.RevisionListing listing) {
			DefaultListModel<WorldBuilderLauncherModel.ProjectRevisionEntry> values =
				new DefaultListModel<WorldBuilderLauncherModel.ProjectRevisionEntry>();
			for (WorldBuilderLauncherModel.ProjectRevisionEntry revision :
				listing.revisions) values.addElement(revision);
			final JList<WorldBuilderLauncherModel.ProjectRevisionEntry> revisions =
				new JList<WorldBuilderLauncherModel.ProjectRevisionEntry>(values);
			revisions.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
			if (!values.isEmpty()) revisions.setSelectedIndex(0);
			JTextArea explanation = readOnlyText();
			explanation.setRows(5);
			explanation.setText(values.isEmpty()
				? "No project backups exist yet. Create Backup Now records the complete "
					+ "current world without accessing the server."
				: "Newest backups are shown first. Loading one automatically creates a "
					+ "backup of the current world before restoring it.\n\nStored unique data: "
					+ formatBytes(listing.storedObjectBytes) + " across "
					+ values.size() + " revision" + (values.size() == 1 ? "" : "s") + ".");
			JPanel panel = new JPanel(new BorderLayout(6, 6));
			panel.add(explanation, BorderLayout.NORTH);
			JScrollPane scroll = new JScrollPane(revisions);
			scroll.setPreferredSize(new Dimension(760, 260));
			panel.add(scroll, BorderLayout.CENTER);
			Object[] options = {"Load Backup…", "Create Backup Now…",
				"Export Backup…", "Close"};
			int selected = JOptionPane.showOptionDialog(frame, panel,
				"Project Backups — " + entry.displayName, JOptionPane.DEFAULT_OPTION,
				JOptionPane.PLAIN_MESSAGE, null, options, options[3]);
			WorldBuilderLauncherModel.ProjectRevisionEntry revision =
				revisions.getSelectedValue();
			if (selected == 0) {
				if (revision == null) {
					showError("There is no backup to load yet.", null);
					return;
				}
				loadProjectBackup(entry, revision);
			} else if (selected == 1) {
				createProjectBackup(entry);
			} else if (selected == 2) {
				if (revision == null) {
					showError("There is no backup to export yet.", null);
					return;
				}
				exportProjectBackup(entry, revision);
			}
		}

		private void createProjectBackup(
			final WorldBuilderLauncherModel.ProjectEntry entry) {
			String description = JOptionPane.showInputDialog(frame,
				"Optional description for this project backup:",
				"Create Backup Now", JOptionPane.PLAIN_MESSAGE);
			if (description == null) return;
			final String detail = description.trim();
			runTask("Creating an immutable project backup…",
				new Task<WorldBuilderLauncherModel.ProjectRevisionEntry>() {
					@Override public WorldBuilderLauncherModel.ProjectRevisionEntry run()
						throws Exception {
						return model.createProjectBackup(entry, detail);
					}
				}, new Success<WorldBuilderLauncherModel.ProjectRevisionEntry>() {
					@Override public void accept(
						WorldBuilderLauncherModel.ProjectRevisionEntry revision) {
						JOptionPane.showMessageDialog(frame,
							"Project backup created.\n\nRevision: " + revision.revisionId
								+ "\nNo server files were accessed or changed.",
							"Project Backup Created", JOptionPane.INFORMATION_MESSAGE);
						refreshProjects(entry.projectId);
					}
				});
		}

		private void loadProjectBackup(
			final WorldBuilderLauncherModel.ProjectEntry entry,
			final WorldBuilderLauncherModel.ProjectRevisionEntry revision) {
			String detail = "Load this project backup?\n\nCreated: " + revision.createdAt
				+ "\nReason: " + revision.reason.replace('-', ' ')
				+ (revision.description.isEmpty() ? ""
					: "\nDescription: " + revision.description)
				+ "\nFiles: " + revision.fileCount
				+ "\nLogical size: " + formatBytes(revision.totalBytes)
				+ "\n\nThe current project world is backed up first. The server is untouched.";
		if (JOptionPane.showConfirmDialog(frame, detail, "Load Project Backup",
			JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE)
			!= JOptionPane.OK_OPTION) return;
			runTask("Creating a safeguard and loading the selected project backup…",
				new Task<String>() {
					@Override public String run() throws Exception {
						return model.restoreProjectBackup(entry, revision.revisionId);
					}
				}, transactionSuccess(entry.projectId, "Project Backup Loaded"));
		}

		private void exportProjectBackup(
			final WorldBuilderLauncherModel.ProjectEntry entry,
			final WorldBuilderLauncherModel.ProjectRevisionEntry revision) {
			runTask("Exporting the selected immutable project backup…",
				new Task<Path>() {
					@Override public Path run() throws Exception {
						return model.exportProjectBackup(entry, revision.revisionId);
					}
				}, new Success<Path>() {
					@Override public void accept(Path exported) {
						JOptionPane.showMessageDialog(frame,
							"Project backup exported to:\n\n" + exported,
							"Project Backup Exported", JOptionPane.INFORMATION_MESSAGE);
					}
				});
		}

		private static String formatBytes(long bytes) {
			if (bytes < 1024L) return bytes + " B";
			double value = bytes;
			String[] units = {"B", "KiB", "MiB", "GiB"};
			int unit = 0;
			while (value >= 1024.0 && unit < units.length - 1) {
				value /= 1024.0;
				unit++;
			}
			return String.format(java.util.Locale.ROOT, "%.1f %s",
				Double.valueOf(value), units[unit]);
		}

		private void recoverSelectedProjectImport() {
			final WorldBuilderLauncherModel.ProjectEntry entry =
				selectedForServerAction("recover an interrupted server import");
			if (entry == null) return;
			runTask("Inspecting durable transaction evidence…",
				new Task<WorldBuilderLauncherModel.PreparedRecovery>() {
					@Override public WorldBuilderLauncherModel.PreparedRecovery run()
						throws Exception { return model.prepareServerRecovery(entry); }
				}, new Success<WorldBuilderLauncherModel.PreparedRecovery>() {
					@Override public void accept(
						final WorldBuilderLauncherModel.PreparedRecovery prepared) {
						if (!confirmTransaction("Recover Interrupted Server Map Import",
							"Recover Import", prepared.summary())) return;
						runTask("Restoring the exact verified transaction state…",
							new Task<String>() {
								@Override public String run() throws Exception {
									return model.applyServerRecovery(prepared);
								}
							}, transactionSuccess(entry.projectId,
								"Recovery Complete"));
					}
				});
		}

		private WorldBuilderLauncherModel.ProjectEntry selectedForServerAction(
			String action) {
			if (busy || editorRunning) {
				JOptionPane.showMessageDialog(frame,
					"Close the editor and wait for the current task before you " + action + ".",
					"Project Is In Use", JOptionPane.INFORMATION_MESSAGE);
				return null;
			}
			WorldBuilderLauncherModel.ProjectEntry entry = projectList.getSelectedValue();
			if (entry == null) showError("Select a project first.", null);
			return entry;
		}

		private boolean confirmTransaction(String title, String action, String summary) {
			JTextArea visible = readOnlyText();
			visible.setRows(18);
			visible.setColumns(72);
			visible.setText(summary + "\n\nThe server must remain offline. World Builder "
				+ "will refuse any drift and will keep verified recovery evidence.");
			visible.setCaretPosition(0);
			Object[] options = {action, "Cancel"};
			return JOptionPane.showOptionDialog(frame, new JScrollPane(visible), title,
				JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null,
				options, options[1]) == 0;
		}

		private Success<String> transactionSuccess(
			final String projectId, final String title) {
			return new Success<String>() {
				@Override public void accept(String message) {
					details.setText(message);
					details.setCaretPosition(0);
					JOptionPane.showMessageDialog(frame, message, title,
						JOptionPane.INFORMATION_MESSAGE);
					refreshProjects(projectId);
				}
			};
		}

		private void createEmpty() {
			if (busy) return;
			final JTextField name = new JTextField("New World", 28);
			Object[] message = {
				"Create a new standalone empty world", Box.createVerticalStrut(5),
				"This creates an isolated project. It does not read or change a server map.",
				"Project name:", name
			};
			if (JOptionPane.showConfirmDialog(frame, message, "Create New Project",
				JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
				!= JOptionPane.OK_OPTION) return;
			final String displayName = name.getText().trim();
			if (displayName.isEmpty()) {
				showError("Enter a project name.", null);
				return;
			}
			runTask("Preparing an empty-world preview…",
				new Task<WorldBuilderLauncherModel.DiscoveryPreview>() {
					@Override public WorldBuilderLauncherModel.DiscoveryPreview run()
						throws Exception { return model.inspectEmptyWorld(); }
				}, new Success<WorldBuilderLauncherModel.DiscoveryPreview>() {
					@Override public void accept(WorldBuilderLauncherModel.DiscoveryPreview preview) {
						createPreviewedProject(preview, displayName);
					}
				});
		}

		private void inspectInstalledSource() {
			inspectInstalledSource(false);
		}

		private void inspectInstalledSource(boolean advanced) {
			if (busy) return;
			if (model.defaultTarget() == null) {
				showError("This application has no parent server/source folder. "
					+ "Use Advanced / Recovery to choose another supported source.", null);
				return;
			}
			inspectSource(model.defaultTarget(), advanced);
		}

		private void chooseSource() {
			if (busy) return;
			JFileChooser chooser = new JFileChooser(settings.lastSource(model.defaultTarget()));
			chooser.setDialogTitle("Choose a supported server or map source folder");
			chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
			chooser.setAcceptAllFileFilterUsed(false);
			if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return;
			Path selected = chooser.getSelectedFile().toPath();
			settings.rememberSource(selected);
			inspectSource(selected, true);
		}

		private void inspectSource(final Path source, final boolean advanced) {
			inspectSource(source, null, advanced, false);
		}

		private void inspectSource(final Path source, final String configuration,
			final boolean advanced) {
			inspectSource(source, configuration, advanced, false);
		}

		private void inspectSource(final Path source, final String configuration,
			final boolean advanced, final boolean preferMostRecentlyModified) {
			runTask("Inspecting source read-only…",
				new Task<WorldBuilderLauncherModel.DiscoveryPreview>() {
					@Override public WorldBuilderLauncherModel.DiscoveryPreview run()
						throws Exception { return configuration == null
							? model.inspectSource(source)
							: model.inspectSource(source, configuration); }
				}, new Success<WorldBuilderLauncherModel.DiscoveryPreview>() {
					@Override public void accept(WorldBuilderLauncherModel.DiscoveryPreview preview) {
						if (preview.needsConfigurationChoice()) {
							WorldBuilderLauncherModel.ConfigurationChoice newest =
								WorldBuilderLauncherModel.ConfigurationChoice
									.mostRecentlyModified(preview.configurationChoices);
							Object[] actions = {"Use Most Recently Modified",
								"Choose from Detected…", "Cancel"};
							int action = JOptionPane.showOptionDialog(frame,
								"More than one server map configuration was found.\n\n"
									+ "Most recently modified:\n" + newest
									+ "\n\nModification time is a convenience hint; the exact "
									+ "selected configuration and its enabled overlays will "
									+ "be copied into a new isolated project.",
								"Choose Server Map", JOptionPane.DEFAULT_OPTION,
								JOptionPane.QUESTION_MESSAGE, null, actions, actions[0]);
							WorldBuilderLauncherModel.ConfigurationChoice selected = null;
							if (action == 0) selected = newest;
							if (action == 1) selected =
								(WorldBuilderLauncherModel.ConfigurationChoice)
								JOptionPane.showInputDialog(frame,
									"Choose the exact server map configuration to copy.\n"
										+ "Each entry shows path, modification time, and hash:",
									"Choose from Detected Maps",
									JOptionPane.QUESTION_MESSAGE, null,
									preview.configurationChoices.toArray(), newest);
							if (selected != null) {
								inspectSource(source, selected.role, advanced, action == 0);
							}
							return;
						}
						inspectLegacyMigrationThenShow(
							preview, advanced, null, preferMostRecentlyModified);
					}
				});
		}

		private void inspectLegacyMigrationThenShow(
			final WorldBuilderLauncherModel.DiscoveryPreview preview,
			final boolean advanced) {
			inspectLegacyMigrationThenShow(preview, advanced, null, false);
		}

		private void inspectLegacyMigrationThenShow(
			final WorldBuilderLauncherModel.DiscoveryPreview preview,
			final boolean advanced, final String legacyConfiguration) {
			inspectLegacyMigrationThenShow(preview, advanced, legacyConfiguration, false);
		}

		private void inspectLegacyMigrationThenShow(
			final WorldBuilderLauncherModel.DiscoveryPreview preview,
			final boolean advanced, final String legacyConfiguration,
			final boolean preferMostRecentlyModified) {
			if (!preview.canCreateServerProject()) {
				showSourcePreview(preview, advanced, null, preferMostRecentlyModified);
				return;
			}
			runTask("Checking for legacy map changes…",
				new Task<WorldBuilderLauncherModel.LegacyMigrationPreview>() {
					@Override public WorldBuilderLauncherModel.LegacyMigrationPreview run()
						throws Exception { return legacyConfiguration == null
							? model.inspectLegacyMigration(preview)
							: model.inspectLegacyMigration(preview, legacyConfiguration); }
				}, new Success<WorldBuilderLauncherModel.LegacyMigrationPreview>() {
					@Override public void accept(
						WorldBuilderLauncherModel.LegacyMigrationPreview migration) {
						if (migration != null && migration.needsConfigurationChoice()) {
							WorldBuilderLauncherModel.ConfigurationChoice newest =
								WorldBuilderLauncherModel.ConfigurationChoice
									.mostRecentlyModified(migration.configurationChoices);
							if (preferMostRecentlyModified) {
								inspectLegacyMigrationThenShow(
									preview, advanced, newest.role, true);
								return;
							}
							Object[] actions = {"Use Most Recently Modified",
								"Choose from Detected…", "Cancel"};
							int action = JOptionPane.showOptionDialog(frame,
								"Custom_Landscape is associated with more than one supported "
									+ "server map configuration.\n\nMost recently modified:\n"
									+ newest + "\n\nChoose which exact configuration supplies "
									+ "the legacy terrain and enabled placement overlays.",
								"Choose Custom_Landscape Map", JOptionPane.DEFAULT_OPTION,
								JOptionPane.QUESTION_MESSAGE, null, actions, actions[0]);
							WorldBuilderLauncherModel.ConfigurationChoice selected = null;
							if (action == 0) selected = newest;
							if (action == 1) selected =
								(WorldBuilderLauncherModel.ConfigurationChoice)
									JOptionPane.showInputDialog(frame,
										"Choose the exact legacy map configuration:",
										"Choose from Detected Legacy Maps",
										JOptionPane.QUESTION_MESSAGE, null,
										migration.configurationChoices.toArray(), newest);
							if (selected != null) inspectLegacyMigrationThenShow(
								preview, advanced, selected.role, action == 0);
							return;
						}
						showSourcePreview(preview, advanced, migration,
							preferMostRecentlyModified);
					}
				});
		}

		private void exportDetectedProviderDiagnostic() {
			if (busy) return;
			runTask("Exporting provider diagnostics…", new Task<Path>() {
				@Override public Path run() throws Exception {
					WorldBuilderLauncherModel.DiscoveryPreview preview =
						model.inspectDefaultTarget();
					return model.exportPortableProviderDiagnostic(preview);
				}
			}, new Success<Path>() {
				@Override public void accept(Path exported) {
					JOptionPane.showMessageDialog(frame,
						"A portable provider diagnostic was saved at:\n" + exported
							+ "\n\nIt contains no absolute source or provider paths.",
						"Diagnostics Exported", JOptionPane.INFORMATION_MESSAGE);
				}
			});
		}

		private void resetDetectedProviderCache() {
			if (busy) return;
			int choice = JOptionPane.showConfirmDialog(frame,
				"Reset the cached provider association for the detected server?\n\n"
					+ "Existing projects and immutable provider folders will not be deleted. "
					+ "The current catalog is backed up before any change. The next new "
					+ "project will rebuild provider content from current server evidence.",
				"Reset Provider Cache", JOptionPane.OK_CANCEL_OPTION,
				JOptionPane.WARNING_MESSAGE);
			if (choice != JOptionPane.OK_OPTION) return;
			runTask("Resetting provider cache safely…",
				new Task<WorldBuilderPortableProvider.CacheReset>() {
					@Override public WorldBuilderPortableProvider.CacheReset run()
						throws Exception {
						WorldBuilderLauncherModel.DiscoveryPreview preview =
							model.inspectDefaultTarget();
						return model.resetPortableProviderCache(preview);
					}
				}, new Success<WorldBuilderPortableProvider.CacheReset>() {
					@Override public void accept(WorldBuilderPortableProvider.CacheReset reset) {
						String backup = reset.backup == null ? ""
							: "\n\nRecovery backup: " + reset.backup;
						JOptionPane.showMessageDialog(frame, reset.summary + backup,
							"Provider Cache Recovery", JOptionPane.INFORMATION_MESSAGE);
					}
				});
		}

		private void showSourcePreview(WorldBuilderLauncherModel.DiscoveryPreview preview,
			boolean advanced,
			WorldBuilderLauncherModel.LegacyMigrationPreview detectedMigration,
			boolean preferMostRecentlyModified) {
			final WorldBuilderLauncherModel.LegacyMigrationPreview migration;
			final Path layeredBasePackage;
			final boolean keepLayeredAuthority;
			if (detectedMigration != null) {
				if (detectedMigration.primaryPacked) {
					keepLayeredAuthority = false;
					WorldBuilderLayeredBaseDiscovery.Discovery bases;
					try {
						bases = model.inspectLayeredBases(preview);
					} catch (Exception failure) {
						showError("The active layered map could not be detected safely.", failure);
						return;
					}
					WorldBuilderLayeredBaseDiscovery.Candidate selectedBase = bases.automatic();
					if (selectedBase == null && bases.candidates.size() > 1) {
						selectedBase = preferMostRecentlyModified ? bases.candidates.get(0)
							: (WorldBuilderLayeredBaseDiscovery.Candidate)
								JOptionPane.showInputDialog(frame,
									"More than one active layered map was recorded for this server.\n"
										+ "Choose the map that the detected Custom_Landscape changes belong to:",
									"Choose Detected Layered Map", JOptionPane.QUESTION_MESSAGE,
									null, bases.candidates.toArray(), bases.candidates.get(0));
						if (selectedBase == null) return;
					}
					if (selectedBase == null) {
						Object[] options = {"Use Legacy Map Only", "Cancel"};
						int answer = JOptionPane.showOptionDialog(frame,
							"Custom_Landscape was detected, but no verified server launch record or "
								+ "content-addressed local layered-map installation could be found.\n\n"
								+ "No folder selection is required or accepted. Install or launch the "
								+ "server's layered map normally so it can be detected automatically, "
								+ "or use the four-plane legacy map by itself.",
							"Active Layered Map Not Detected", JOptionPane.DEFAULT_OPTION,
							JOptionPane.WARNING_MESSAGE, null, options, options[1]);
						if (answer != 0) return;
						layeredBasePackage = null;
					} else {
						Object[] options = {"Yes", "No"};
						int answer = JOptionPane.showOptionDialog(frame,
							"Custom_Landscape file detected. Would you like to incorporate it?\n\n"
								+ "Yes applies its legacy sectors over the automatically detected "
								+ "active layered map while preserving layers such as -2 and +10. "
								+ "No uses the four-plane legacy map by itself.\n\nThe source remains unchanged.",
							"Custom_Landscape Detected", JOptionPane.DEFAULT_OPTION,
							JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
						if (answer < 0) return;
						layeredBasePackage = answer == 0 ? selectedBase.packageRoot : null;
					}
					migration = detectedMigration;
				} else {
					WorldBuilderLegacyLandscapeAssessment assessment =
						detectedMigration.assessment;
					if (assessment == null) {
						showError("Custom_Landscape could not be compared with the layered map.", null);
						return;
					}
					if (assessment.status
						== WorldBuilderLegacyLandscapeAssessment.Status.EQUIVALENT) {
						migration = detectedMigration;
						keepLayeredAuthority = true;
					} else {
						Object[] options = {"Keep Current Layered Map", "Apply Legacy Sectors", "Cancel"};
						String guidance = assessment.status
							== WorldBuilderLegacyLandscapeAssessment.Status.CONFLICTING
							? "Every legacy sector coordinate is represented, but byte comparison "
								+ "cannot determine which conflicting copy is newer. Keeping the current "
								+ "layered authority avoids replacing selected terrain and is the safer default."
							: "Some legacy sectors are absent from the layered map. Review the "
								+ "counts carefully before choosing which terrain authority to keep.";
						int answer = JOptionPane.showOptionDialog(frame,
							"Custom_Landscape was compared with the selected layered map.\n\n"
								+ assessment.summary() + "\n\n" + guidance
								+ "\n\nThis decision is recorded once. Import will back up and retire "
								+ "the exact legacy files only after installing archive-free client startup.",
							"Choose Terrain Authority", JOptionPane.DEFAULT_OPTION,
							JOptionPane.WARNING_MESSAGE, null, options, options[0]);
						if (answer < 0 || answer == 2) return;
						migration = detectedMigration;
						keepLayeredAuthority = answer == 0;
					}
					layeredBasePackage = null;
				}
			} else {
				migration = null;
				layeredBasePackage = null;
				keepLayeredAuthority = false;
			}
			WorldBuilderPortableProvider.Discovery providerDiscovery;
			try {
				providerDiscovery = model.inspectPortableProvider(preview);
			} catch (Exception unavailable) {
				providerDiscovery = null;
			}
			JTextArea report = readOnlyText();
			report.setRows(advanced ? 9 : 7);
			String sourceLabel = advanced ? preview.source.toString()
				: preview.source.getFileName() == null ? "Detected adjacent server"
					: preview.source.getFileName().toString();
			report.setText((advanced ? "Source folder: " : "Detected server: ") + sourceLabel
				+ "\nCompatibility: " + preview.status
				+ "\nDetected format: " + formatLabel(preview.representation)
				+ "\n\n" + preview.summary
				+ "\n\nCustom content: " + customContentSummary(providerDiscovery)
				+ "\n\nThe map and required content are copied into an isolated project. "
				+ "The server remains unchanged and no target JAR is executed."
				+ (migration == null ? ""
					: keepLayeredAuthority
						? "\n\nCustom_Landscape: Keep the selected layered authority and record the legacy map as superseded."
					: "\n\nCustom_Landscape: Incorporate into the isolated project."
						+ (layeredBasePackage == null ? ""
							: "\nLayered base: " + layeredBasePackage))
				+ (advanced && providerDiscovery != null
					? "\n\nAdvanced discovery details:\n" + providerDiscovery.describe() : ""));
			report.setCaretPosition(0);
			if (!preview.canCreateServerProject()) {
				JOptionPane.showMessageDialog(frame, new JScrollPane(report),
					"Source Is Not Ready", JOptionPane.WARNING_MESSAGE);
				return;
			}
			if (!advanced && providerDiscovery != null
				&& providerDiscovery.status == WorldBuilderPortableProvider.Status.AMBIGUOUS) {
				JOptionPane.showMessageDialog(frame,
					new Object[] {new JScrollPane(report),
						"More than one custom-content layout was found. Use Advanced / Recovery "
							+ "→ Detected Server Content Options to choose the exact layout."},
					"A Content Choice Is Needed", JOptionPane.WARNING_MESSAGE);
				return;
			}
			JTextField name = new JTextField("Imported Server Map", 28);
			final JTextField providerStatus = new JTextField(36);
			providerStatus.setEditable(false);
			final Path automaticMapping = providerDiscovery != null
				&& providerDiscovery.selected != null
				? providerDiscovery.selected.itemVisuals : null;
			final WorldBuilderPortableProvider.GuidedSelection[] guided = {
				providerDiscovery != null
					&& providerDiscovery.status == WorldBuilderPortableProvider.Status.RECOGNIZED
					&& providerDiscovery.selected != null
					&& (providerDiscovery.selected.itemVisuals != null
						|| providerDiscovery.selected.definitions != null)
					? selectionFrom(providerDiscovery.selected) : null
			};
			providerStatus.setText(automaticMapping != null
				? "Automatic provider: " + automaticMapping
				: guided[0] != null ? "Recognized layout will be imported locally"
				: "No provider selected");
			JButton choosePackage = new JButton("Choose complete provider package…");
			choosePackage.addActionListener(new java.awt.event.ActionListener() {
				@Override public void actionPerformed(java.awt.event.ActionEvent event) {
					JFileChooser chooser = new JFileChooser(preview.source.toFile());
					chooser.setDialogTitle("Choose the world-builder-provider folder");
					chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
					chooser.setAcceptAllFileFilterUsed(false);
					if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return;
					Path selected = chooser.getSelectedFile().toPath();
					try {
						guided[0] = completeProviderSelection(
							model.inspectPortableProvider(selected));
						providerStatus.setText("Complete provider ready: "
							+ selected.toAbsolutePath().normalize());
					} catch (Exception failure) {
						showError("That folder is not a complete provider package.", failure);
					}
				}
			});
			JButton chooseProvider = new JButton("Advanced provider import…");
			chooseProvider.addActionListener(new java.awt.event.ActionListener() {
				@Override public void actionPerformed(java.awt.event.ActionEvent event) {
					WorldBuilderPortableProvider.GuidedSelection selected =
						guidedProviderSelection(preview.source, guided[0]);
					if (selected != null) {
						guided[0] = selected;
						providerStatus.setText("Advanced provider selections ready for local import");
					}
				}
			});
			Object[] message = advanced
				? new Object[] {new JScrollPane(report), "Project name:", name,
					"Custom content provider:", providerStatus, choosePackage, chooseProvider}
				: new Object[] {new JScrollPane(report), "Project name:", name};
			if (JOptionPane.showConfirmDialog(frame, message,
				advanced ? "Advanced Server Map Import" : "Create Project from Detected Server",
				JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
				!= JOptionPane.OK_OPTION) return;
			String displayName = name.getText().trim();
			if (displayName.isEmpty()) {
				showError("Enter a project name.", null);
				return;
			}
			if (providerDiscovery != null
				&& providerDiscovery.status == WorldBuilderPortableProvider.Status.AMBIGUOUS
				&& guided[0] == null) {
				showError("More than one content layout was found. Choose a complete provider "
					+ "package or use Advanced provider import before continuing.", null);
				return;
			}
			createPreviewedProject(preview, displayName, automaticMapping, guided[0],
				migration, layeredBasePackage, keepLayeredAuthority);
		}

		private static String customContentSummary(
			WorldBuilderPortableProvider.Discovery discovery) {
			if (discovery == null) return "Automatic inspection was unavailable. Creation "
				+ "will continue only if the detected definitions and assets are complete.";
			if (discovery.status == WorldBuilderPortableProvider.Status.AMBIGUOUS) {
				return "More than one genuinely different content layout was found; "
					+ "an explicit choice is required.";
			}
			switch (discovery.cacheStatus) {
				case BYPASSED:
					return "A complete server-provided content package is ready.";
				case HIT:
					return "The unchanged local content cache is ready.";
				case STALE:
					return "Server content changed. Fresh content will be prepared for this "
						+ "new project; existing projects remain unchanged.";
				case CORRUPT:
					return "A cache problem was detected and the affected cache will not be "
						+ "reused. Recovery controls are available under Advanced / Recovery.";
				case AMBIGUOUS:
					return "More than one valid content layout was found; an advanced choice is required.";
				case MISS:
				default:
					return discovery.status == WorldBuilderPortableProvider.Status.RECOGNIZED
						? "A compatible server content layout was detected and will be copied locally."
						: "No separate custom-content package was required or detected.";
			}
		}

		private WorldBuilderPortableProvider.GuidedSelection guidedProviderSelection(
			Path source, WorldBuilderPortableProvider.GuidedSelection initial) {
			final JTextField mapping = pathField(initial == null ? null : initial.itemVisuals);
			final JTextField definitions = pathField(initial == null ? null : initial.definitions);
			final JTextField authentic = pathField(initial == null ? null : initial.authenticArchive);
			final JTextField custom = pathField(initial == null ? null : initial.customArchive);
			final JTextField spritepacks = pathField(initial == null ? null : initial.spritepacks);
			final JTextField external = pathField(initial == null ? null : initial.externalItems);
			JPanel panel = new JPanel(new GridBagLayout());
			panel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
			addSelectionRow(panel, 0, "Existing item-visuals JSON (optional)", mapping,
				fileChooser(mapping, source, "Choose item-visual provider JSON", JFileChooser.FILES_ONLY));
			addSelectionRow(panel, 1, "Item definitions JSON or folder", definitions,
				fileChooser(definitions, source, "Choose item definitions", JFileChooser.FILES_AND_DIRECTORIES));
			addSelectionRow(panel, 2, "Authentic sprite archive", authentic,
				fileChooser(authentic, source, "Choose Authentic_Sprites.orsc", JFileChooser.FILES_ONLY));
			addSelectionRow(panel, 3, "Custom sprite archive", custom,
				fileChooser(custom, source, "Choose Custom_Sprites.osar", JFileChooser.FILES_ONLY));
			addSelectionRow(panel, 4, "Spritepacks folder", spritepacks,
				fileChooser(spritepacks, source, "Choose spritepacks folder", JFileChooser.DIRECTORIES_ONLY));
			addSelectionRow(panel, 5, "External item PNG folder", external,
				fileChooser(external, source, "Choose external item assets", JFileChooser.DIRECTORIES_ONLY));
			JTextArea explanation = readOnlyText();
			explanation.setRows(4);
			explanation.setText("Choose an existing neutral item-visual manifest, or choose "
				+ "item definitions so World Builder can preserve every item ID and name with "
				+ "a placeholder until its exact visual is available. Selected files are copied "
				+ "into a local provider; the source is never changed.");
			GridBagConstraints help = new GridBagConstraints();
			help.gridx = 0; help.gridy = 6; help.gridwidth = 3;
			help.weightx = 1; help.fill = GridBagConstraints.HORIZONTAL;
			help.insets = new Insets(8, 2, 2, 2);
			panel.add(explanation, help);
			if (JOptionPane.showConfirmDialog(frame, new JScrollPane(panel),
				"Advanced Provider Import", JOptionPane.OK_CANCEL_OPTION,
				JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return null;
			Path mappingPath = fieldPath(mapping);
			Path definitionsPath = fieldPath(definitions);
			if (mappingPath == null && definitionsPath == null) {
				showError("Choose either an item-visuals JSON file or item definitions.", null);
				return null;
			}
			return new WorldBuilderPortableProvider.GuidedSelection(mappingPath,
				definitionsPath, fieldPath(authentic), fieldPath(custom),
				fieldPath(spritepacks), fieldPath(external));
		}

		private static JTextField pathField(Path value) {
			JTextField field = new JTextField(34);
			if (value != null) field.setText(value.toString());
			return field;
		}

		private JButton fileChooser(final JTextField field, final Path source,
			final String title, final int mode) {
			JButton choose = new JButton("Choose…");
			choose.addActionListener(event -> {
				Path current = fieldPath(field);
				JFileChooser chooser = new JFileChooser(
					(current == null ? source : current).toFile());
				chooser.setDialogTitle(title);
				chooser.setFileSelectionMode(mode);
				if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
					field.setText(chooser.getSelectedFile().getAbsolutePath());
				}
			});
			return choose;
		}

		private static void addSelectionRow(JPanel panel, int row, String label,
			JTextField field, JButton chooser) {
			GridBagConstraints left = new GridBagConstraints();
			left.gridx = 0; left.gridy = row; left.anchor = GridBagConstraints.WEST;
			left.insets = new Insets(3, 3, 3, 8);
			panel.add(new JLabel(label), left);
			GridBagConstraints center = new GridBagConstraints();
			center.gridx = 1; center.gridy = row; center.weightx = 1;
			center.fill = GridBagConstraints.HORIZONTAL;
			center.insets = new Insets(3, 3, 3, 3);
			panel.add(field, center);
			GridBagConstraints right = new GridBagConstraints();
			right.gridx = 2; right.gridy = row; right.insets = new Insets(3, 3, 3, 3);
			panel.add(chooser, right);
		}

		private static Path fieldPath(JTextField field) {
			String value = field.getText().trim();
			return value.isEmpty() ? null : Paths.get(value).toAbsolutePath().normalize();
		}

		private void createPreviewedProject(
			final WorldBuilderLauncherModel.DiscoveryPreview preview,
			final String displayName) {
			createPreviewedProject(preview, displayName, null);
		}

		private void createPreviewedProject(
			final WorldBuilderLauncherModel.DiscoveryPreview preview,
			final String displayName, final Path itemVisualMappings) {
			createPreviewedProject(preview, displayName, itemVisualMappings, null);
		}

		private void createPreviewedProject(
			final WorldBuilderLauncherModel.DiscoveryPreview preview,
			final String displayName, final Path itemVisualMappings,
			final WorldBuilderPortableProvider.GuidedSelection guidedProvider) {
			createPreviewedProject(preview, displayName, itemVisualMappings,
				guidedProvider, null);
		}

		private void createPreviewedProject(
			final WorldBuilderLauncherModel.DiscoveryPreview preview,
			final String displayName, final Path itemVisualMappings,
			final WorldBuilderPortableProvider.GuidedSelection guidedProvider,
			final WorldBuilderLauncherModel.LegacyMigrationPreview migration) {
			createPreviewedProject(preview, displayName, itemVisualMappings,
				guidedProvider, migration, null);
		}

		private void createPreviewedProject(
			final WorldBuilderLauncherModel.DiscoveryPreview preview,
			final String displayName, final Path itemVisualMappings,
			final WorldBuilderPortableProvider.GuidedSelection guidedProvider,
			final WorldBuilderLauncherModel.LegacyMigrationPreview migration,
			final Path layeredBasePackage) {
			createPreviewedProject(preview, displayName, itemVisualMappings,
				guidedProvider, migration, layeredBasePackage, false);
		}

		private void createPreviewedProject(
			final WorldBuilderLauncherModel.DiscoveryPreview preview,
			final String displayName, final Path itemVisualMappings,
			final WorldBuilderPortableProvider.GuidedSelection guidedProvider,
			final WorldBuilderLauncherModel.LegacyMigrationPreview migration,
			final Path layeredBasePackage,
			final boolean keepLayeredAuthority) {
			runTask("Creating isolated project…",
				new Task<WorldBuilderAdaptiveProjectLifecycle.ProjectResult>() {
					@Override public WorldBuilderAdaptiveProjectLifecycle.ProjectResult run()
						throws Exception {
						Path mapping = itemVisualMappings;
						if (guidedProvider != null) {
							mapping = model.importPortableProvider(
								preview, guidedProvider).itemVisuals;
						}
						return migration == null
							? model.create(preview, displayName, mapping)
							: keepLayeredAuthority
								? model.createKeepLayered(
									preview, migration, displayName, mapping)
								: model.createMigrated(preview, migration, displayName, mapping,
									layeredBasePackage);
					}
				}, new Success<WorldBuilderAdaptiveProjectLifecycle.ProjectResult>() {
					@Override public void accept(
						WorldBuilderAdaptiveProjectLifecycle.ProjectResult created) {
						String reconciliationWarning =
							WorldBuilderNpcDefinitionReconciliation.projectWarningSummary(
								created.projectRoot);
						String npcWarning =
							WorldBuilderNpcDefinitionProvider.projectWarningSummary(
								created.projectRoot);
						String sceneryWarning =
							WorldBuilderSceneryModelProvider.projectWarningSummary(
								created.projectRoot);
						String materialWarning =
							WorldBuilderTerrainMaterialProvider.projectWarningSummary(
								created.projectRoot);
						String warnings = (reconciliationWarning == null
							? "" : reconciliationWarning)
							+ (npcWarning == null ? "" : npcWarning)
							+ (sceneryWarning == null ? "" : sceneryWarning)
							+ (materialWarning == null ? "" : materialWarning);
						status.setText(warnings.isEmpty()
							? "Project created; opening the editor…"
							: "Project created with content warnings; opening the editor…");
						if (reconciliationWarning != null) {
							JTextArea notice = readOnlyText();
							notice.setRows(8);
							notice.setColumns(58);
							notice.setText(reconciliationWarning);
							notice.setCaretPosition(0);
							JOptionPane.showMessageDialog(frame, new JScrollPane(notice),
								"NPC IDs Reconciled", JOptionPane.WARNING_MESSAGE);
						}
						launchProject(created.projectId,
							"standalone-empty".equals(created.origin)
								? null : preview.source);
					}
				});
		}

		private void chooseExistingProject() {
			if (busy) return;
			JFileChooser chooser = new JFileChooser(
				model.installation().resolve("projects").toFile());
			chooser.setDialogTitle("Choose a registered World Builder project folder");
			chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
			if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return;
			try {
				WorldBuilderLauncherModel.ProjectEntry entry =
					model.projectFromChooser(chooser.getSelectedFile().toPath());
				for (int index = 0; index < projects.size(); index++) {
					if (entry.projectId.equals(projects.get(index).projectId)) {
						projectList.setSelectedIndex(index);
						projectList.ensureIndexIsVisible(index);
						break;
					}
				}
				launchProject(entry.projectId, model.defaultTarget());
			} catch (Exception failure) {
				showError("That folder cannot be opened as a project.", failure);
			}
		}

		private void launchProject(final String projectId, final Path possibleTarget) {
			editorRunning = true;
			runTask("Validating project and starting its private editor…",
				new Task<Integer>() {
					@Override public Integer run() throws Exception {
						WorldBuilderAdaptiveProjectLifecycle.ProjectResult opened =
							model.selectAndOpen(projectId, possibleTarget);
						int exit = model.run(opened);
						if (exit != 0) throw new IOException(
							"The packaged editor closed with exit code " + exit
								+ ". Review the project logs folder for details.");
						return Integer.valueOf(exit);
					}
				}, new Success<Integer>() {
					@Override public void accept(Integer ignored) {
						editorRunning = false;
						refreshProjects(projectId);
						JOptionPane.showMessageDialog(frame,
							"The editor closed cleanly and the project was saved.",
							"World Builder Closed", JOptionPane.INFORMATION_MESSAGE);
					}
				});
		}

		private <T> void runTask(final String message, final Task<T> task,
			final Success<T> success) {
			if (busy) return;
			setBusy(true, message);
			(new SwingWorker<T,Void>() {
				@Override protected T doInBackground() throws Exception {
					return task.run();
				}

				@Override protected void done() {
					try {
						T value = get();
						setBusy(false, "Ready");
						success.accept(value);
					} catch (Exception failure) {
						editorRunning = false;
						setBusy(false, "Action failed — details remain visible");
						showError("World Builder could not complete that action.", failure);
					}
				}
			}).execute();
		}

		private void setBusy(boolean value, String message) {
			busy = value;
			projectList.setEnabled(!value);
			installedSource.setEnabled(!value);
			boolean selected = projectList.getSelectedValue() != null;
			open.setEnabled(!value && selected);
			importToServer.setEnabled(!value && selected);
			restoreBackup.setEnabled(!value && selected);
			status.setText(message);
		}

		private void showError(String heading, Throwable failure) {
			Throwable cause = failure;
			while (cause != null && cause.getCause() != null
				&& (cause instanceof java.util.concurrent.ExecutionException)) {
				cause = cause.getCause();
			}
			String explanation = cause == null ? heading
				: heading + "\n\n" + usefulMessage(cause);
			if (cause instanceof WorldBuilderContractException) {
				WorldBuilderContractException contract =
					(WorldBuilderContractException)cause;
				explanation += "\n\nCode: " + contract.code()
					+ (contract.relativePath().isEmpty() ? ""
						: "\nSource: " + contract.relativePath())
					+ "\nNext step: " + contract.nextStep();
			}
			details.setText(explanation);
			details.setCaretPosition(0);
			JTextArea visible = readOnlyText();
			visible.setRows(9);
			visible.setColumns(58);
			visible.setText(explanation);
			visible.setCaretPosition(0);
			JOptionPane.showMessageDialog(frame, new JScrollPane(visible),
				"World Builder Error", JOptionPane.ERROR_MESSAGE);
		}

		private void closeRequested() {
			CloseDisposition disposition = closeDisposition(editorRunning, busy);
			if (disposition == CloseDisposition.WAIT_FOR_EDITOR) {
				JOptionPane.showMessageDialog(frame,
					"The editor is still running. Close the editor normally first. "
						+ "World Builder will then stop its private server, save the project, "
						+ "and complete final validation before this launcher can close.",
					"Close the Editor First", JOptionPane.INFORMATION_MESSAGE);
				return;
			}
			if (disposition == CloseDisposition.WAIT_FOR_TASK) {
				JOptionPane.showMessageDialog(frame,
					"A project safety check or copy is still finishing. Wait for it to "
						+ "complete before closing; World Builder will not abandon a project "
						+ "transaction halfway through.",
					"World Builder Is Busy", JOptionPane.INFORMATION_MESSAGE);
				return;
			}
			frame.dispose();
			closed.countDown();
		}
	}

	private static final class ProjectRenderer extends DefaultListCellRenderer {
		@Override public Component getListCellRendererComponent(JList<?> list,
			Object value, int index, boolean selected, boolean focus) {
			JLabel label = (JLabel)super.getListCellRendererComponent(
				list, value, index, selected, focus);
			if (value instanceof WorldBuilderLauncherModel.ProjectEntry) {
				WorldBuilderLauncherModel.ProjectEntry entry =
					(WorldBuilderLauncherModel.ProjectEntry)value;
				label.setText((entry.active ? "▶  " : "    ") + entry.displayName
					+ "  —  " + originLabel(entry.origin));
				label.setBorder(BorderFactory.createEmptyBorder(7, 7, 7, 7));
			}
			return label;
		}
	}

	private static final class LauncherSettings {
		private final Path settingsDirectory;
		private final Path file;

		LauncherSettings(Path installation) {
			settingsDirectory = installation.resolve("settings");
			file = settingsDirectory.resolve("desktop-launcher.properties");
		}

		java.io.File lastSource(Path fallback) {
			Properties values = load();
			String saved = values.getProperty("lastSource", "");
			if (!saved.isEmpty()) {
				Path path = Paths.get(saved).toAbsolutePath().normalize();
				if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
					&& !Files.isSymbolicLink(path)) return path.toFile();
			}
			return fallback == null ? settingsDirectory.getParent().toFile()
				: fallback.toFile();
		}

		void rememberSource(Path source) {
			try {
				if (Files.exists(settingsDirectory, LinkOption.NOFOLLOW_LINKS)) {
					if (!Files.isDirectory(settingsDirectory, LinkOption.NOFOLLOW_LINKS)
						|| Files.isSymbolicLink(settingsDirectory)) return;
				} else Files.createDirectory(settingsDirectory);
				if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)
					&& (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
						|| Files.isSymbolicLink(file))) return;
				Properties values = load();
				values.setProperty("lastSource", source.toAbsolutePath().normalize().toString());
				Path temporary = Files.createTempFile(
					settingsDirectory, ".launcher-settings-", ".tmp");
				try {
					try (OutputStream output = Files.newOutputStream(temporary,
						StandardOpenOption.TRUNCATE_EXISTING)) {
						values.store(output, "World Builder 2 local launcher settings");
					}
					try {
						Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE,
							StandardCopyOption.REPLACE_EXISTING);
					} catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
						Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
					}
				} finally {
					Files.deleteIfExists(temporary);
				}
			} catch (IOException ignored) {
				// Remembering a chooser path is optional and never blocks project work.
			}
		}

		private Properties load() {
			Properties values = new Properties();
			try {
				if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
					&& !Files.isSymbolicLink(file) && Files.size(file) <= 64 * 1024) {
					try (InputStream input = Files.newInputStream(file,
						StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
						values.load(input);
					}
				}
			} catch (IOException ignored) {
				// Invalid optional settings are ignored without touching project state.
			}
			return values;
		}
	}

	private interface Task<T> {
		T run() throws Exception;
	}

	private interface Success<T> {
		void accept(T value);
	}

	private static JTextArea readOnlyText() {
		JTextArea text = new JTextArea();
		text.setEditable(false);
		text.setLineWrap(true);
		text.setWrapStyleWord(true);
		text.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		text.setBackground(UIManager.getColor("Panel.background"));
		return text;
	}

	private static String originLabel(String origin) {
		if ("standalone-empty".equals(origin)) return "Standalone empty world";
		if ("target-packed".equals(origin)) return "Copied and converted packed map";
		if ("target-layered".equals(origin)) return "Copied layered server map";
		return origin.isEmpty() ? "Unknown" : origin;
	}

	private static String formatLabel(String representation) {
		if ("packed".equals(representation)) return "Supported packed map";
		if ("layered".equals(representation)) return "Compatible layered map";
		if ("none".equals(representation)) return "No server map detected";
		return representation.isEmpty() ? "Unknown" : representation;
	}

	private static String usefulMessage(Throwable failure) {
		String message = failure.getMessage();
		return message == null || message.trim().isEmpty()
			? failure.getClass().getSimpleName() : message.trim();
	}

	static final class Options {
		Path installation;
		Path runtime;
		Path target;
		String configurationRole;
		int port;

		Options(Path installation, Path runtime, Path target,
			String configurationRole, int port) {
			this.installation = installation;
			this.runtime = runtime;
			this.target = target;
			this.configurationRole = configurationRole;
			this.port = port;
		}

		private Options() {
		}

		static Options parse(String[] args) {
			Options result = new Options();
			for (int index = 1; index < args.length; index++) {
				String argument = args[index];
				if ("--installation-root".equals(argument) && index + 1 < args.length) {
					result.installation = Paths.get(args[++index]);
				} else if ("--runtime-root".equals(argument) && index + 1 < args.length) {
					result.runtime = Paths.get(args[++index]);
				} else if ("--target-root".equals(argument) && index + 1 < args.length) {
					result.target = Paths.get(args[++index]);
				} else if ("--configuration-role".equals(argument)
					&& index + 1 < args.length) {
					result.configurationRole = args[++index];
				} else if ("--port".equals(argument) && index + 1 < args.length) {
					try {
						result.port = Integer.parseInt(args[++index]);
					} catch (NumberFormatException invalid) {
						throw new IllegalArgumentException(
							"--port must be an integer between 1 and 65534.");
					}
				} else throw new IllegalArgumentException(
					"Unknown or incomplete desktop-launcher argument: " + argument);
			}
			if (result.installation == null || result.runtime == null
				|| result.target == null || result.port < 1 || result.port >= 65535) {
				throw new IllegalArgumentException("desktop-launcher requires "
					+ "--installation-root, --runtime-root, --target-root, and a port "
					+ "between 1 and 65534.");
			}
			return result;
		}
	}
}
