package com.openrsc.worldbuilder;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Generic read-only discovery orchestration with bounded drift restarts. */
final class WorldBuilderAdaptiveDiscovery {
	private static final int MAX_DISCOVERY_ATTEMPTS = 2;

	interface Observer {
		void betweenVerificationPasses(Path targetRoot, int attempt) throws Exception;
	}

	private static final Observer NO_OP_OBSERVER = new Observer() {
		@Override
		public void betweenVerificationPasses(Path targetRoot, int attempt) {
			// Production discovery never changes target state between verification passes.
		}
	};

	private final WorldBuilderLayoutAdapterRegistry registry;
	private final Observer observer;

	WorldBuilderAdaptiveDiscovery() {
		this(WorldBuilderLayoutAdapterRegistry.standard(), NO_OP_OBSERVER);
	}

	WorldBuilderAdaptiveDiscovery(
		WorldBuilderLayoutAdapterRegistry registry, Observer observer) {
		this.registry = registry;
		this.observer = observer == null ? NO_OP_OBSERVER : observer;
	}

	WorldBuilderAdaptiveDiscoveryReport discover(Path requestedRoot, String requestedRole)
		throws WorldBuilderContractException {
		String display = requestedRoot == null ? ""
			: requestedRoot.toAbsolutePath().normalize().toString();
		WorldBuilderReadOnlyTarget target;
		try {
			target = WorldBuilderReadOnlyTarget.open(requestedRoot);
		} catch (WorldBuilderContractException refusal) {
			return WorldBuilderAdaptiveDiscoveryReport.blocked(display, registry.ids(),
				WorldBuilderAdaptiveDiscoveryReport.absentStateReference(), null, null,
				"", "unknown", refusal,
				Collections.<WorldBuilderAdapterInspection.Check>emptyList());
		}
		display = target.root.toString();
		Pass second = null;
		for (int attempt = 0; attempt < MAX_DISCOVERY_ATTEMPTS; attempt++) {
			Pass first = inspectOnce(target, requestedRole);
			try {
				observer.betweenVerificationPasses(target.root, attempt);
			} catch (Exception callbackFailure) {
				WorldBuilderContractException drift = WorldBuilderReadOnlyTarget.problem(
					WorldBuilderErrorCodes.DISCOVERY_DRIFT, "target-root",
					"Discovery verification was interrupted: " + callbackFailure.getMessage(),
					"Stop target changes and retry discovery.", callbackFailure);
				return first.blockedReport(display, registry.ids(), drift);
			}
			second = inspectOnce(target, requestedRole);
			if (first.stableKey().equals(second.stableKey())) {
				return second.toReport(display, registry.ids());
			}
		}
		WorldBuilderContractException drift = WorldBuilderReadOnlyTarget.problem(
			WorldBuilderErrorCodes.DISCOVERY_DRIFT,
			second == null ? "target-root" : second.primaryEvidencePath(),
			"Target configuration, capability, map, definitions, assets, or runtime evidence "
				+ "changed during both bounded verification attempts.",
			"Stop the server/update process from changing files, then run discovery again.");
		return second == null
			? WorldBuilderAdaptiveDiscoveryReport.blocked(display, registry.ids(),
				WorldBuilderAdaptiveDiscoveryReport.absentStateReference(), null, null,
				"", "unknown", drift,
				Collections.<WorldBuilderAdapterInspection.Check>emptyList())
			: second.blockedReport(display, registry.ids(), drift);
	}

	private Pass inspectOnce(WorldBuilderReadOnlyTarget target, String requestedRole) {
		WorldBuilderReadOnlyTarget.FileState descriptor = null;
		WorldBuilderTargetCapability capability = null;
		try {
			if (target.exists(WorldBuilderTargetCapability.RELATIVE_PATH)) {
				descriptor = target.requiredState(
					"target-capability", WorldBuilderTargetCapability.RELATIVE_PATH);
				capability = WorldBuilderTargetCapability.read(target);
				WorldBuilderLayoutAdapter adapter = registry.named(capability.adapterId);
				if (adapter == null) {
					return Pass.blocked(descriptor, capability, null, capability.adapterId,
						"unknown", problem(WorldBuilderErrorCodes.UNSUPPORTED_ADAPTER,
							WorldBuilderTargetCapability.RELATIVE_PATH,
							"Capability selects unregistered adapter " + capability.adapterId + ".",
							"Use one of the compiled adapters: " + registry.ids() + "."),
						Collections.<WorldBuilderLayoutAdapter.ProbeResult>emptyList());
				}
				return inspectAdapter(
					target, descriptor, capability, adapter, requestedRole,
					Collections.<WorldBuilderLayoutAdapter.ProbeResult>emptyList());
			}

			List<WorldBuilderLayoutAdapter> recognizable =
				new ArrayList<WorldBuilderLayoutAdapter>();
			List<WorldBuilderLayoutAdapter> supported =
				new ArrayList<WorldBuilderLayoutAdapter>();
			List<WorldBuilderLayoutAdapter.ProbeResult> probes =
				new ArrayList<WorldBuilderLayoutAdapter.ProbeResult>();
			List<String> profileIds = new ArrayList<String>();
			for (WorldBuilderLayoutAdapter adapter : registry.adapters()) {
				WorldBuilderLayoutAdapter.ProbeResult probe = adapter.probe(target);
				if (profileIds.contains(probe.profileId)) {
					return Pass.blocked(null, null, null, adapter.id(), "unknown",
						problem(WorldBuilderErrorCodes.CAPABILITY_MISMATCH, "target-root",
							"More than one compiled adapter returned format profile identity "
								+ probe.profileId + ".",
							"Give every compiled project-neutral structural profile a unique versioned identity."),
						probes);
				}
				profileIds.add(probe.profileId);
				probes.add(probe);
				if (probe.state != WorldBuilderLayoutAdapter.Probe.NO_EVIDENCE) {
					recognizable.add(adapter);
				}
				if (probe.state == WorldBuilderLayoutAdapter.Probe.SUPPORTED) {
					supported.add(adapter);
				}
			}
			if (recognizable.isEmpty()) return Pass.standalone(probes);
			if (recognizable.size() > 1 || supported.size() > 1) {
				return Pass.blocked(null, null, null, "", "unknown",
					problem(WorldBuilderErrorCodes.AMBIGUOUS_CONFIGURATION, "target-root",
						"More than one bounded adapter probe found recognizable target evidence: "
							+ adapterIds(recognizable) + ".",
						"Add one truthful descriptor selecting the active layout; discovery will not guess."),
					probes);
			}
			if (supported.isEmpty()) {
				return Pass.blocked(null, null, null, recognizable.get(0).id(), "unknown",
					problem(WorldBuilderErrorCodes.UNSUPPORTED_ADAPTER, "target-root",
						"Recognizable server evidence is incomplete or requires a capability descriptor for "
							+ recognizable.get(0).id() + ".",
						"Restore the adapter's exact probe layout or add a truthful descriptor."),
					probes);
			}
			return inspectAdapter(target, null, null, supported.get(0), requestedRole, probes);
		} catch (WorldBuilderContractException refusal) {
			String adapterId = capability == null ? "" : capability.adapterId;
			String representation = capability == null || capability.sourceRepresentations.size() != 1
				? "unknown" : capability.sourceRepresentations.get(0);
			return Pass.blocked(descriptor, capability, null, adapterId,
				representation, refusal,
				Collections.<WorldBuilderLayoutAdapter.ProbeResult>emptyList());
		}
	}

	private Pass inspectAdapter(
		WorldBuilderReadOnlyTarget target,
		WorldBuilderReadOnlyTarget.FileState descriptor,
		WorldBuilderTargetCapability capability,
		WorldBuilderLayoutAdapter adapter,
		String requestedRole,
		List<WorldBuilderLayoutAdapter.ProbeResult> probes) {
		try {
			return Pass.compatible(descriptor, capability,
				adapter.inspect(target, capability, requestedRole), probes);
		} catch (WorldBuilderContractException refusal) {
			String representation = capability == null
				? "packed" : capability.sourceRepresentations.size() == 1
					? capability.sourceRepresentations.get(0) : "unknown";
			return Pass.blocked(descriptor, capability, null, adapter.id(),
				representation, refusal, probes);
		}
	}

	private static List<String> adapterIds(List<WorldBuilderLayoutAdapter> adapters) {
		List<String> ids = new ArrayList<String>();
		for (WorldBuilderLayoutAdapter adapter : adapters) ids.add(adapter.id());
		return ids;
	}

	private static WorldBuilderContractException problem(
		String code, String path, String message, String nextStep) {
		return WorldBuilderReadOnlyTarget.problem(code, path, message, nextStep);
	}

	private static final class Pass {
		final String state;
		final WorldBuilderReadOnlyTarget.FileState descriptor;
		final WorldBuilderTargetCapability capability;
		final WorldBuilderAdapterInspection inspection;
		final String adapterId;
		final String representation;
		final WorldBuilderContractException refusal;
		final List<WorldBuilderLayoutAdapter.ProbeResult> probes;

		private Pass(String state,
			WorldBuilderReadOnlyTarget.FileState descriptor,
			WorldBuilderTargetCapability capability,
			WorldBuilderAdapterInspection inspection,
			String adapterId,
			String representation,
			WorldBuilderContractException refusal,
			List<WorldBuilderLayoutAdapter.ProbeResult> probes) {
			this.state = state;
			this.descriptor = descriptor;
			this.capability = capability;
			this.inspection = inspection;
			this.adapterId = adapterId;
			this.representation = representation;
			this.refusal = refusal;
			this.probes = Collections.unmodifiableList(
				new ArrayList<WorldBuilderLayoutAdapter.ProbeResult>(probes));
		}

		static Pass standalone(List<WorldBuilderLayoutAdapter.ProbeResult> probes) {
			return new Pass("standalone", null, null, null, "", "none", null, probes);
		}

		static Pass compatible(
			WorldBuilderReadOnlyTarget.FileState descriptor,
			WorldBuilderTargetCapability capability,
			WorldBuilderAdapterInspection inspection,
			List<WorldBuilderLayoutAdapter.ProbeResult> probes) {
			return new Pass("compatible", descriptor, capability, inspection,
				inspection.adapterId, inspection.representation, null, probes);
		}

		static Pass blocked(
			WorldBuilderReadOnlyTarget.FileState descriptor,
			WorldBuilderTargetCapability capability,
			WorldBuilderAdapterInspection inspection,
			String adapterId,
			String representation,
			WorldBuilderContractException refusal,
			List<WorldBuilderLayoutAdapter.ProbeResult> probes) {
			return new Pass("blocked", descriptor, capability, inspection,
				adapterId, representation, refusal, probes);
		}

		String stableKey() {
			StringBuilder value = new StringBuilder(8192);
			value.append(state).append('\u0000').append(adapterId).append('\u0000')
				.append(representation);
			if (descriptor != null) value.append('\u0001').append(descriptor.stableKey());
			if (capability != null) value.append('\u0002').append(capability.adapterId)
				.append('\u0000').append(capability.capabilityId)
				.append('\u0000').append(capability.evidenceSha256);
			if (inspection != null) value.append('\u0003').append(inspection.stableKey());
			if (refusal != null) value.append('\u0004').append(refusal.code())
				.append('\u0000').append(refusal.relativePath()).append('\u0000')
				.append(refusal.getMessage()).append('\u0000').append(refusal.nextStep());
			for (WorldBuilderLayoutAdapter.ProbeResult probe : probes) {
				value.append('\u0005').append(probe.stableKey());
			}
			return value.toString();
		}

		List<WorldBuilderAdapterInspection.Check> profileChecks() {
			List<WorldBuilderAdapterInspection.Check> checks =
				new ArrayList<WorldBuilderAdapterInspection.Check>();
			for (WorldBuilderLayoutAdapter.ProbeResult probe : probes) {
				checks.add(probe.toCheck());
			}
			return checks;
		}

		String primaryEvidencePath() {
			if (inspection != null) return inspection.selected.relativePath;
			if (descriptor != null) return descriptor.relativePath;
			if (refusal != null && !refusal.relativePath().isEmpty()) {
				return refusal.relativePath();
			}
			return "target-root";
		}

		WorldBuilderAdaptiveDiscoveryReport toReport(
			String targetDisplay, List<String> adapterIds)
			throws WorldBuilderContractException {
			if ("standalone".equals(state)) {
				return WorldBuilderAdaptiveDiscoveryReport.standalone(
					targetDisplay, adapterIds, profileChecks());
			}
			if ("compatible".equals(state)) {
				return WorldBuilderAdaptiveDiscoveryReport.compatible(targetDisplay, adapterIds,
					descriptor == null
						? WorldBuilderAdaptiveDiscoveryReport.absentStateReference()
						: WorldBuilderAdaptiveDiscoveryReport.descriptorReference(descriptor),
					inspection, profileChecks());
			}
			return blockedReport(targetDisplay, adapterIds, refusal);
		}

		WorldBuilderAdaptiveDiscoveryReport blockedReport(
			String targetDisplay, List<String> adapterIds,
			WorldBuilderContractException reason) throws WorldBuilderContractException {
			return WorldBuilderAdaptiveDiscoveryReport.blocked(targetDisplay, adapterIds,
				descriptor == null
					? WorldBuilderAdaptiveDiscoveryReport.absentStateReference()
					: WorldBuilderAdaptiveDiscoveryReport.descriptorReference(descriptor),
				capability, inspection, adapterId, representation, reason,
				profileChecks());
		}
	}
}
