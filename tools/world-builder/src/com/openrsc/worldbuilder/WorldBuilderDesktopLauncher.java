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
	private final Ui scriptedUi;
	private final ProjectRunner scriptedRunner;

	WorldBuilderDesktopLauncher(Ui ui, ProjectRunner runner) {
		this.scriptedUi = ui;
		this.scriptedRunner = runner;
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
				suggested = "New Empty World";
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
			String displayName = scriptedUi.requestDisplayName(suggested);
			if (displayName == null || displayName.trim().isEmpty()) return 0;
			if (!scriptedUi.confirmCreation(
				action == Action.NEW_EMPTY ? "Create New Empty World"
					: "Create Isolated Project from Server Map",
				preview.summary)) return 0;
			WorldBuilderAdaptiveProjectLifecycle.ProjectResult created =
				model.create(preview, displayName.trim());
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
		private final JButton open = new JButton("Open Existing Project");
		private final JButton empty = new JButton("New Empty World");
		private final JButton installedSource = new JButton("Use Detected Server Map");
		private final JButton chooseSource = new JButton("Select Another Supported Source…");
		private final JButton chooseProject = new JButton("Browse Existing Projects…");
		private final JButton refresh = new JButton("Refresh Projects");
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
			file.add(menu("Open Existing Project", new Runnable() {
				@Override public void run() { openSelected(); }
			}));
			file.add(menu("New Empty World", new Runnable() {
				@Override public void run() { createEmpty(); }
			}));
			file.add(menu("Use Detected Server Map", new Runnable() {
				@Override public void run() { inspectInstalledSource(); }
			}));
			file.add(menu("Select Another Supported Source…", new Runnable() {
				@Override public void run() { chooseSource(); }
			}));
			file.add(new JSeparator());
			file.add(menu("Exit", new Runnable() {
				@Override public void run() { closeRequested(); }
			}));
			JMenuBar menuBar = new JMenuBar();
			menuBar.add(file);
			frame.setJMenuBar(menuBar);

			JPanel heading = new JPanel();
			heading.setLayout(new BoxLayout(heading, BoxLayout.Y_AXIS));
			heading.setBorder(BorderFactory.createEmptyBorder(18, 20, 12, 20));
			JLabel title = new JLabel("World Builder 2");
			title.setFont(title.getFont().deriveFont(Font.BOLD, 25f));
			heading.add(title);
			heading.add(Box.createVerticalStrut(5));
			heading.add(new JLabel("Choose a project to edit, create an empty world, "
				+ "or safely copy a supported server map into a new project."));
			heading.add(Box.createVerticalStrut(7));
			JLabel install = new JLabel("Application folder: " + model.installation());
			install.setForeground(new Color(80, 80, 80));
			heading.add(install);
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
			empty.addActionListener(event -> createEmpty());
			installedSource.addActionListener(event -> inspectInstalledSource());
			chooseSource.addActionListener(event -> chooseSource());
			chooseProject.addActionListener(event -> chooseExistingProject());
			refresh.addActionListener(event -> refreshProjects(null));

			JPanel actions = new JPanel(new GridBagLayout());
			actions.setBorder(BorderFactory.createEmptyBorder(0, 20, 12, 20));
			GridBagConstraints action = new GridBagConstraints();
			action.insets = new Insets(4, 4, 4, 4);
			action.fill = GridBagConstraints.HORIZONTAL;
			action.weightx = 1;
			action.gridx = 0; action.gridy = 0;
			actions.add(open, action);
			action.gridx = 1;
			actions.add(empty, action);
			action.gridx = 2;
			actions.add(installedSource, action);
			action.gridx = 0; action.gridy = 1;
			actions.add(chooseSource, action);
			action.gridx = 1;
			actions.add(chooseProject, action);
			action.gridx = 2;
			actions.add(refresh, action);

			JPanel bottom = new JPanel(new BorderLayout());
			bottom.add(actions, BorderLayout.CENTER);
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
					else details.setText("No projects are registered in this installation.\n\n"
						+ "Create a new empty world, or choose a supported server/map source.\n"
						+ "Nothing in a server is overwritten during project creation or editing.");
					status.setText(entries.isEmpty() ? "Ready — no projects yet"
						: "Ready — " + entries.size() + " project"
							+ (entries.size() == 1 ? "" : "s"));
				}
			});
		}

		private void showSelectedDetails() {
			WorldBuilderLauncherModel.ProjectEntry entry = projectList.getSelectedValue();
			open.setEnabled(!busy && entry != null);
			if (entry == null) return;
			details.setText("Name: " + entry.displayName
				+ "\nProject ID: " + entry.projectId
				+ "\nType: " + originLabel(entry.origin)
				+ "\nCompatibility state: " + entry.state
				+ "\nSelected: " + (entry.active ? "Yes" : "No")
				+ "\nProject folder: " + entry.projectRoot
				+ "\n\nOpening validates the complete project before starting its private "
				+ "client and server. Editing remains isolated here until you explicitly "
				+ "run the separate Import Map Changes action.");
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

		private void createEmpty() {
			if (busy) return;
			final JTextField name = new JTextField("New Empty World", 28);
			Object[] message = {
				"Create a new standalone empty world", Box.createVerticalStrut(5),
				"This creates an isolated project. It does not read or change a server map.",
				"Project name:", name
			};
			if (JOptionPane.showConfirmDialog(frame, message, "New Empty World",
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
			if (busy) return;
			if (model.defaultTarget() == null) {
				showError("This application has no parent server/source folder. "
					+ "Choose another source folder instead.", null);
				return;
			}
			inspectSource(model.defaultTarget());
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
			inspectSource(selected);
		}

		private void inspectSource(final Path source) {
			runTask("Inspecting source read-only…",
				new Task<WorldBuilderLauncherModel.DiscoveryPreview>() {
					@Override public WorldBuilderLauncherModel.DiscoveryPreview run()
						throws Exception { return model.inspectSource(source); }
				}, new Success<WorldBuilderLauncherModel.DiscoveryPreview>() {
					@Override public void accept(WorldBuilderLauncherModel.DiscoveryPreview preview) {
						showSourcePreview(preview);
					}
				});
		}

		private void showSourcePreview(WorldBuilderLauncherModel.DiscoveryPreview preview) {
			WorldBuilderPortableProvider.Discovery providerDiscovery;
			try {
				providerDiscovery = model.inspectPortableProvider(preview.source);
			} catch (Exception unavailable) {
				providerDiscovery = null;
			}
			JTextArea report = readOnlyText();
			report.setRows(9);
			report.setText("Source folder: " + preview.source
				+ "\nCompatibility: " + preview.status
				+ "\nDetected format: " + formatLabel(preview.representation)
				+ "\n\n" + preview.summary
				+ "\n\nSupported packed maps are copied and converted; compatible layered "
				+ "maps are copied unchanged. Individual arbitrary map files are not "
				+ "guessed. The source remains unchanged.\n\nItem visual provider: "
				+ (providerDiscovery == null
					? "Provider discovery could not inspect this layout. Guided import remains available."
					: providerDiscovery.describe())
				+ "\n\nProvider content is copied into this World Builder installation. "
				+ "The selected server remains read-only and no target JAR is executed.");
			report.setCaretPosition(0);
			if (!preview.canCreateServerProject()) {
				JOptionPane.showMessageDialog(frame, new JScrollPane(report),
					"Source Is Not Ready", JOptionPane.WARNING_MESSAGE);
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
			Object[] message = {new JScrollPane(report), "Project name:", name,
				"Custom content provider:", providerStatus, choosePackage, chooseProvider};
			if (JOptionPane.showConfirmDialog(frame, message,
				"Create Isolated Project from Server Map",
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
			createPreviewedProject(preview, displayName, automaticMapping, guided[0]);
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
			runTask("Creating isolated project…",
				new Task<WorldBuilderAdaptiveProjectLifecycle.ProjectResult>() {
					@Override public WorldBuilderAdaptiveProjectLifecycle.ProjectResult run()
						throws Exception {
						Path mapping = itemVisualMappings;
						if (guidedProvider != null) {
							mapping = model.importPortableProvider(
								preview.source, guidedProvider).itemVisuals;
						}
						return model.create(preview, displayName, mapping);
					}
				}, new Success<WorldBuilderAdaptiveProjectLifecycle.ProjectResult>() {
					@Override public void accept(
						WorldBuilderAdaptiveProjectLifecycle.ProjectResult created) {
						String npcWarning =
							WorldBuilderNpcDefinitionProvider.projectWarningSummary(
								created.projectRoot);
						int choice = JOptionPane.showConfirmDialog(frame,
							"Project created safely at:\n" + created.projectRoot
								+ "\n\nThe source was not changed."
								+ (npcWarning == null ? "" : npcWarning)
								+ "\n\nOpen this project now?",
							"Project Ready", JOptionPane.YES_NO_OPTION,
							JOptionPane.INFORMATION_MESSAGE);
						if (choice == JOptionPane.YES_OPTION) {
							launchProject(created.projectId,
								"standalone-empty".equals(created.origin)
									? null : preview.source);
						} else refreshProjects(created.projectId);
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
			empty.setEnabled(!value);
			installedSource.setEnabled(!value);
			chooseSource.setEnabled(!value);
			chooseProject.setEnabled(!value);
			refresh.setEnabled(!value);
			open.setEnabled(!value && projectList.getSelectedValue() != null);
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
