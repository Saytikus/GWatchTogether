# Known issues

This register contains only findings supported by the accepted POC-0 record. Unknown details are explicitly left for reproduction rather than inferred.

## P0-05-F11-ROTATE-001 — stopped diagnostic appenders retained during repeated rotation

- **Status / severity:** Open, non-blocking follow-up; P2. Recorded against P0-05 and todo #133. P0-05 local foundation acceptance does not assert leak-free rotation or soak readiness.
- **Observed behavior:** Repeated diagnostic-file rotation retains stopped Logback appenders. See the implementation at `core/src/jvmMain/kotlin/com/saytikus/gwatchtogether/infrastructure/diagnostics/LogbackDiagnosticEvents.kt` (`rotateIfNeeded`). No quantified retained count or measured resource impact is recorded here.
- **Reproduction:** Exercise repeated size- or age-triggered rotation using the diagnostics configuration/clock and inspect the logger's attached appender set after each rotation. The original accepted record does not specify fixture parameters, exact invocation or observed count; capture those during the P0-23 regression work rather than inventing them now.
- **Risk:** Retained stopped appenders accumulate as repeated rotations occur. The accepted record flags this for long-running/rotation stress; it does not quantify memory, descriptor or throughput impact. Do not claim those impacts measured until reproduced.
- **Workaround:** Avoid relying on this foundation for claims of long-running rotation/soak safety. Existing logging remains bounded by its configured file/retention behavior, but that does not resolve appender retention. No operational workaround is established for repeated rotation.
- **Owner:** GWatchTogether diagnostics implementation owner (project maintainer); no individual is assigned in the source record.
- **Target:** P0-23 local integration/stress, with a focused fix and regression before P0-27 closure if reproduced/confirmed. P0-23 includes stress; P0-27 requires confirmed issues to have regression evidence and affected runs repeated.
- **Evidence limits:** The P0-05 record identifies the finding and references todo #133; no standalone issue tracker artifact, reproduction output or resource measurement is present in the available repository record.

## Future finding template

Copy this section per finding; keep unknown facts marked `TBD` until observed.

### `<stable-id>` — `<short observed behavior>`

- **Status / severity:** `Open | Blocked | Fixed | Closed`; `P0 | P1 | P2 | P3`; source and date.
- **Observed behavior:** What actually happened; distinguish observation from hypothesis.
- **Reproduction:** Exact safe setup, steps, input/fixture identity, frequency and actual result. Do not include secrets or raw runtime logs.
- **Risk:** Affected capability and demonstrated impact; label unmeasured impact as unknown.
- **Workaround:** Verified mitigation or `None known`; do not imply a workaround.
- **Owner:** Role/team or explicitly unassigned.
- **Target:** P0 step/release gate and closure condition; use `TBD` if not accepted.
- **Evidence limits:** Actual safe artifact reference(s), or why evidence is absent; never invent a path or result.
