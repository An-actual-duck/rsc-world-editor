# Runtime provider adaptive login correction

This is a bounded external-provider correction request for the runtime pinned
by RSC World Editor. Implement it only in `rsc-world-editor-runtime`; it does
not authorize changing Spoiled Milk/Core-Framework, a live server, a release,
or the World Editor dependency lock.

## Rejected candidate

- World Editor commit: `b05b16fd744f410a7e95e601f5f8f8d42ea2ce6b`
- Runtime provider baseline: `56acea6c7a77f96ed50d394c314a1de264fcb3aa`
- Linux candidate SHA-256:
  `4fd6949addebd87dbd9920d80d2c3e7fdb64a602cc820e32acf54635371e5c80`
- Windows candidate SHA-256:
  `0f7a4ff742cbbb83213be204bdaed51f3f627753d8bd270f94aa3359a3cf6b17`
- Archive-inspection JSON SHA-256:
  `b27635328f62362628849c7f6ace95ca1ab60547389d108f38af601a470dfd41`

Archive inspection passed, but the candidate is rejected because native
standalone startup cannot authenticate the isolated Builder. It must not be
accepted, promoted, or reused as a release artifact.

## Reproduction and diagnosis

1. Extract the Linux candidate into an ordinary empty parent.
2. Start with updates disabled and choose `CREATE` for a standalone-empty
   project.
3. The server becomes ready and the OpenGL client stays alive. The earlier
   legacy-login-world crash is fixed.
4. The client retries automatic login and eventually reports
   `java.net.SocketTimeoutException: Read timed out` from `mudclient.login`.
5. The server never logs `Client version`, `Login details`, a processed login,
   a loaded player, `builderbind`, or native-terrain readiness.

The isolated Builder credential was generated successfully as a project-owned
20-byte regular file at mode `0600`; the credential contents are intentionally
not recorded. The server account provisioner reports that the Builder account
is ready. The client and server are launched against the same credential path.

For every retry, `RSCProtocolDecoder` records:

```text
Buffer readable bytes: 278 len: 1
Buffer readable bytes: 276 len: 0
```

`Network_Base.finishPacket()` writes a two-byte big-endian length. The custom
login body is 278 bytes, so the wire prefix begins with `0x01 0x16`, followed
by login opcode `0x00`. While `ConnectionAttachment.authenticClient` is still
unknown, `RSCProtocolDecoder.decode()` first interprets `0x01` as a complete
one-byte legacy packet length. It consumes `0x16` as that packet's opcode,
then consumes the real login opcode `0x00` as a zero-length frame and leaves
the actual login payload stranded. `LoginPacketHandler.processLogin()` is
never called, so it cannot send a login response.

## Required correction

Correct the provider's undecided-connection framing without changing the
World Editor repository or weakening any adaptive safety contract.

1. When a connection is undecided, recognize a complete, structurally valid
   two-byte custom `LOGIN`, `RELOGIN`, or registration frame before consuming
   bytes as a one-byte legacy frame.
2. A partial two-byte frame must retain its complete reader state and wait for
   more bytes. It must not consume a header, opcode, or payload speculatively.
3. A recognized custom frame must be emitted exactly once, with its opcode and
   complete payload intact, and must establish the inauthentic/custom-client
   classification expected by `LoginPacketHandler`.
4. Preserve authentic legacy-client detection, one-byte and two-byte legacy
   framing, session-ID requests, initial-config requests, ISAAC behavior, and
   normal non-Builder custom-client login.
5. Preserve strict adaptive behavior added by the previous correction:
   no legacy landscape archive may be opened or rendered, client-loop failure
   remains fatal, and native layered terrain remains the sole terrain
   authority after authenticated binding.
6. Do not solve this by shrinking the login payload, omitting client
   limitations, special-casing a candidate path, bundling a map, adding a
   placeholder landscape archive, weakening authentication, or accepting an
   incomplete frame.

## Required regression coverage

Add focused decoder tests that exercise actual buffers and reader indices:

- complete custom login frames with body lengths 255, 256, and the observed
  278 bytes;
- the observed leading bytes `0x01 0x16 0x00`;
- headers and payloads fragmented at every relevant boundary;
- multiple frames coalesced in one buffer;
- malformed, zero, truncated, and over-limit lengths failing closed without
  byte loss or duplicate delivery;
- initial-config and session-ID requests;
- representative authentic legacy framing and existing custom login sizes;
- one valid frame reaching the custom branch of `LoginPacketHandler`, receiving
  a response, and being processed only once.

Also add an adaptive runtime integration test using the real built client and
server artifacts. It must prove that automatic Builder login succeeds, the
player is loaded, authenticated adaptive `builderbind` is accepted, native
terrain readiness is reached, and no retry, socket timeout, legacy terrain
read, or fallback occurs. A server-only readiness smoke test is insufficient.

Run the provider's client/server builds, relevant protocol/login tests, all
adaptive runtime and package tests, archive audits, and a private stable-start
smoke test. Report any unavailable native or visual checks honestly.

## Handoff

Return one exact clean pushed commit on the runtime topic branch
`fix/undecided-custom-login-framing`
with:

- commit SHA and durable ref;
- changed files and rationale;
- exact tests and builds run;
- evidence for the 278-byte frame and end-to-end authenticated Builder launch;
- untested behavior and remaining risks; and
- confirmation that Spoiled Milk/Core-Framework, deployment, tags, releases,
  live servers, and the World Editor lock were not changed.

After the owner separately authorizes that exact provider SHA, the World Editor
manager will advance `runtime-provider.lock`, repeat parity and the full suite,
publish clean `main`, rebuild both restricted candidates, inspect them
externally, and retry owner-native validation.
