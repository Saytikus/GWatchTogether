# Native media harness — P0-06 LiveKit Server proof

## Current gate state

**P0-06 is ACCEPTED NARROW (2026-09-25)** following independent review (OK with notes): pinned native Windows x64
LiveKit Server v1.13.7 and the bounded loopback sidecar/lifecycle proof passed review; the reviewer reports 16/16 tests
passed (9 deterministic supervisor, 7 Windows integration). The upstream CLI still reports occupied-port and
malformed-config startup errors while exiting with status `0`; a launcher must not trust exit status as readiness.
P0-07 is IN_PROGRESS — BLOCKED-at-ICE. This gate does not establish packaged executable launch, P0-11, GUI/product
readiness, client/media,
LAN/WAN or general capacity readiness. The pinned Windows build also reports CPU monitoring unsupported and capacity
management disabled; this blocks future capacity/resource claims, not this bounded loopback gate.

The user-local detailed proof record is in ignored `docs/internal/implementation/p0-06-livekit-windows-proof.md`;
that internal directory is not Git-backed. The reviewable harness and this summary are Git-visible. The external proof
artifacts are under `C:\tmp\gwatchtogether-p0-06-proof-20260925\` and
`C:\tmp\gwatchtogether-p0-06-harness-run-20260925-03\` on the proof machine; these paths are evidence references,
not portable repository inputs.

## Pinned build evidence

- LiveKit Server tag `v1.13.7`, commit `8d11efdfcd4220092b6ac7b8a21af28526da5a6b`.
- Tagged source archive SHA-256: `c51fc8720c64e14d57105fd9bc69cfa2e39ce543bbe56bd16eb87c6b4fa60c55`.
- The source declares Apache-2.0; its exact `LICENSE` file SHA-256 is
  `3ddf9be5c28fe27dad143a5dc76eea25222ad1dd68934a047064e56ed2fa40c5`.
- Required Go version `1.26.0`, Windows amd64 archive SHA-256:
  `9bbe0fc64236b2b51f6255c05c4232532b8ecc0e6d2e00950bd3021d8a4d07d4`.
  This matched both the official checksum file at `https://dl.google.com/go/go1.26.0.windows-amd64.zip.sha256` and
  the official release JSON at `https://go.dev/dl/?mode=json&include=all` before extraction or execution. Go was
  extracted only into the proof's user-local temp directory; no system installation or global PATH change was made.
- Output was a Windows PE32+ x86-64 executable reporting `livekit-server version 1.13.7`; binary SHA-256:
  `a499e030f165cc15a825eb7d4afe8fc3560eaedeeb8cef3fb26e4778123cada4`.
- Pinned module metadata, Go sums and license-file evidence for 240 modules are in the external
  `module-license-inventory.json`. This is not a redistributable NOTICE inventory or legal compatibility review.

Build used Go's absolute path and isolated `GOROOT`, `GOMODCACHE`, `GOCACHE`, and `GOPATH`, with `GOENV=off`,
`GOTOOLCHAIN=local`, and `GOWORK=off`. The exact scoped build command was `go run github.com/magefile/mage` from the
pinned LiveKit source root; it exited `0`. Dependencies came from that tag's `go.mod`/`go.sum`. No Docker, WSL, Linux
server, package manager, additional toolchain or other system prerequisite was used.

## Bounded lifecycle regression harness

`windows/p0-06-livekit-lifecycle.ps1` is an executable native Windows test harness. Run it as an ordinary,
non-elevated user with a v1.13.7 binary built outside the checkout and a new evidence directory outside the checkout:

```powershell
.\native\media-harness\windows\p0-06-livekit-lifecycle.ps1 `
  -ServerExe 'C:\path\outside\checkout\livekit-server.exe' `
  -EvidenceDirectory 'C:\path\outside\checkout\p0-06-evidence'
```

It generates random test-only API credentials and an ephemeral config, deletes the config after the run, and checks
that credentials do not appear in retained logs. The API binds to `127.0.0.1`; RTC uses an explicit
`127.0.0.1/32` IP filter and loopback candidate so UDP never binds to external interfaces. The harness checks every
process-owned TCP/UDP endpoint and fails on non-loopback binding. It changes no firewall, router, TLS or public-port
settings. Evidence is local and contains only the binary hash, ephemeral port numbers, exit/error summaries and
privacy-checked server logs.

The run passed these bounded checks on the proof machine:

- Three start → HTTP `GET /` health `200` → stop cycles, with API and ICE loopback ports released each time.
- Occupied API port: the owner remains healthy; contender reports a bind error, exits `0`, releases its distinct UDP
  port and leaves no child process. The harness detects terminal failure from bounded readiness and ownership checks.
- Malformed config: parse error, exit `0`, no owned endpoint or child process. It is not inferred from the occupied
  port case; it was separately reproduced.
- Forced stop: no orphan child or owned API/ICE port remained. This is supervisor-initiated termination, not an unexpected process-exit test.

The harness returns a JSON summary with status `BLOCKED_UPSTREAM_FALSE_SUCCESS_EXIT`; the harness independently checks
error output, process liveness, bounded health and expected port ownership. It does not report a process exit as readiness.
The desktop JVM module contains a project-owned bounded sidecar supervisor and opt-in tests against the same pinned
artifact (`LiveKitSidecarSupervisorTest` and `LiveKitSidecarWindowsIntegrationTest`). That API requires process liveness,
HTTP health, PID-owned API/UDP endpoints and loopback-only bindings; it cleans up only its retained child `Process` and
ephemeral config. The integration test separately terminates the retained child outside the supervisor to verify
unexpected-exit observation. An explicit internal Desktop headless proof mode exercises the supervisor as a caller;
normal GUI launch does not start LiveKit. The independent reviewer accepted the narrow P0-06 proof, but this seam is not
P0-11's hosted-server service or product UI. Any future host service must consume the bounded lifecycle seam or fail
closed; if the upstream false-success risk remains HIGH, do not silently downgrade its P1 severity. Supervisor output is
discarded rather than retained; the test does not collect raw credentials or server logs. The stop test uses process
termination; graceful signal shutdown is unverified. A run also emitted `CPU monitoring unsupported on current platform.
Server capacity management will be disabled`. No media session, client, CPU-capacity, LAN/WAN, resource, packaging or
public deployment behavior is claimed.
