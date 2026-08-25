package com.openrsc.worldbuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Compiled, read-only adapter boundary for one bounded server layout. */
interface WorldBuilderLayoutAdapter {
	String id();

	/** Inspect only this adapter's exact probe roots; never recurse through the target root. */
	ProbeResult probe(WorldBuilderReadOnlyTarget target) throws WorldBuilderContractException;

	WorldBuilderAdapterInspection inspect(
		WorldBuilderReadOnlyTarget target,
		WorldBuilderTargetCapability capability,
		String requestedConfigurationRole) throws WorldBuilderContractException;

	enum Probe {
		NO_EVIDENCE,
		RECOGNIZABLE,
		SUPPORTED
	}

	/**
	 * Versioned, bounded evidence for one project-neutral structural profile probe.
	 * Anchor presence is deliberately separate from parsing: probes establish
	 * authority candidates, while inspect() remains the exact parser/validator.
	 */
	final class ProbeResult {
		static final int CONTRACT_VERSION = 1;

		final String profileId;
		final Probe state;
		final List<Anchor> anchors;

		ProbeResult(String profileId, Probe state, List<Anchor> anchors) {
			if (profileId == null || profileId.isEmpty() || state == null) {
				throw new IllegalArgumentException("Profile probe identity and state are required.");
			}
			this.profileId = profileId;
			this.state = state;
			List<Anchor> sorted = new ArrayList<Anchor>(anchors);
			Collections.sort(sorted);
			this.anchors = Collections.unmodifiableList(sorted);
		}

		String stableKey() {
			StringBuilder value = new StringBuilder(1024);
			value.append(CONTRACT_VERSION).append('\u0000').append(profileId)
				.append('\u0000').append(state.name());
			for (Anchor anchor : anchors) value.append('\u0001').append(anchor.stableKey());
			return value.toString();
		}

		WorldBuilderAdapterInspection.Check toCheck() {
			String status = state == Probe.NO_EVIDENCE ? "not-applicable"
				: state == Probe.RECOGNIZABLE ? "failed" : "passed";
			StringBuilder observed = new StringBuilder(1024);
			observed.append("profileContractVersion=").append(CONTRACT_VERSION)
				.append(", state=").append(state.name().toLowerCase(java.util.Locale.ROOT))
				.append(", anchors=[");
			for (int index = 0; index < anchors.size(); index++) {
				if (index > 0) observed.append(", ");
				Anchor anchor = anchors.get(index);
				observed.append(anchor.role).append(':').append(anchor.relativePath)
					.append('=').append(anchor.present ? "present" : "absent")
					.append(anchor.requiredForSupport ? "(required)" : "(signal)");
			}
			observed.append(']');
			return new WorldBuilderAdapterInspection.Check(
				"format-profile-probe:" + profileId, status,
				"Version 1 bounded structural anchors select exactly one supported profile; "
					+ "recognizable incomplete evidence must not become standalone mode.",
				observed.toString());
		}

		static final class Anchor implements Comparable<Anchor> {
			final String role;
			final String relativePath;
			final boolean present;
			final boolean requiredForSupport;

			Anchor(String role, String relativePath, boolean present,
				boolean requiredForSupport) {
				this.role = role;
				this.relativePath = relativePath;
				this.present = present;
				this.requiredForSupport = requiredForSupport;
			}

			String stableKey() {
				return relativePath + "\u0000" + role + "\u0000" + present
					+ "\u0000" + requiredForSupport;
			}

			@Override
			public int compareTo(Anchor other) {
				int result = relativePath.compareTo(other.relativePath);
				return result != 0 ? result : role.compareTo(other.role);
			}
		}
	}
}

/** Successful, deterministic evidence returned by an adapter inspection pass. */
final class WorldBuilderAdapterInspection {
	final String adapterId;
	final String capabilityId;
	final String capabilityEvidenceRelativePath;
	final String capabilityEvidenceSha256;
	final String representation;
	final List<ConfigurationCandidate> candidates;
	final ConfigurationCandidate selected;
	final List<WorldBuilderReadOnlyTarget.FileState> files;
	final List<Check> checks;

	WorldBuilderAdapterInspection(
		String adapterId,
		String capabilityId,
		String capabilityEvidenceRelativePath,
		String capabilityEvidenceSha256,
		String representation,
		List<ConfigurationCandidate> candidates,
		ConfigurationCandidate selected,
		List<WorldBuilderReadOnlyTarget.FileState> files,
		List<Check> checks) {
		this.adapterId = adapterId;
		this.capabilityId = capabilityId;
		this.capabilityEvidenceRelativePath = capabilityEvidenceRelativePath;
		this.capabilityEvidenceSha256 = capabilityEvidenceSha256;
		this.representation = representation;
		List<ConfigurationCandidate> sortedCandidates =
			new ArrayList<ConfigurationCandidate>(candidates);
		Collections.sort(sortedCandidates);
		this.candidates = Collections.unmodifiableList(sortedCandidates);
		this.selected = selected;
		List<WorldBuilderReadOnlyTarget.FileState> sortedFiles =
			new ArrayList<WorldBuilderReadOnlyTarget.FileState>(files);
		Collections.sort(sortedFiles);
		this.files = Collections.unmodifiableList(sortedFiles);
		List<Check> sortedChecks = new ArrayList<Check>(checks);
		Collections.sort(sortedChecks);
		this.checks = Collections.unmodifiableList(sortedChecks);
	}

	String stableKey() {
		StringBuilder value = new StringBuilder(8192);
		value.append(adapterId).append('\u0000').append(capabilityId).append('\u0000')
			.append(capabilityEvidenceRelativePath).append('\u0000')
			.append(capabilityEvidenceSha256).append('\u0000').append(representation);
		for (ConfigurationCandidate candidate : candidates) {
			value.append('\u0001').append(candidate.stableKey());
		}
		value.append('\u0001').append(selected.stableKey());
		for (WorldBuilderReadOnlyTarget.FileState file : files) {
			value.append('\u0002').append(file.stableKey());
		}
		for (Check check : checks) value.append('\u0003').append(check.stableKey());
		return value.toString();
	}

	static final class ConfigurationCandidate implements Comparable<ConfigurationCandidate> {
		final String role;
		final String relativePath;
		final String sha256;

		ConfigurationCandidate(String role, String relativePath, String sha256) {
			this.role = role;
			this.relativePath = relativePath;
			this.sha256 = sha256;
		}

		Map<String,Object> toJson() {
			Map<String,Object> value = new LinkedHashMap<String,Object>();
			value.put("role", role);
			value.put("relativePath", relativePath);
			value.put("sha256", sha256);
			return value;
		}

		String stableKey() {
			return relativePath + "\u0000" + role + "\u0000" + sha256;
		}

		@Override
		public int compareTo(ConfigurationCandidate other) {
			int result = relativePath.compareTo(other.relativePath);
			return result != 0 ? result : role.compareTo(other.role);
		}
	}

	static final class Check implements Comparable<Check> {
		final String checkId;
		final String status;
		final String expected;
		final String observed;

		Check(String checkId, String status, String expected, String observed) {
			this.checkId = checkId;
			this.status = status;
			this.expected = bounded(expected);
			this.observed = bounded(observed);
		}

		Map<String,Object> toJson() {
			Map<String,Object> value = new LinkedHashMap<String,Object>();
			value.put("checkId", checkId);
			value.put("status", status);
			value.put("expected", expected);
			value.put("observed", observed);
			return value;
		}

		String stableKey() {
			return checkId + "\u0000" + status + "\u0000" + expected + "\u0000" + observed;
		}

		@Override
		public int compareTo(Check other) {
			return checkId.compareTo(other.checkId);
		}

		private static String bounded(String value) {
			if (value == null) return "";
			StringBuilder result = new StringBuilder(Math.min(value.length(), 4096));
			for (int index = 0; index < value.length() && result.length() < 4096; index++) {
				char character = value.charAt(index);
				result.append(character < 0x20 && character != '\n' && character != '\t'
					? ' ' : character);
			}
			return result.toString();
		}
	}
}
