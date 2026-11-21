# Trusted probe coordinator candidate

Internal API-26+ composition of RuntimePack preflight, approval, one-use registry and
evidence validation. No production Android adapter, approval UI, native runner or caller
is installed. The default missing runner rejects authorization before invoking approval.
Unit tests use synthetic reports and an in-memory runner, not Python or a physical device.

The trusted composition root must inject RuntimePackPreflight.verify using the same
pinned configuration and actual app/device paths as Policy. An arbitrary callback report
is not proof of inspection. Policy's absolute executable must be canonical and must name
the executable that adapter actually verifies. The adapter must rebind current device/app
identity; policy fields alone are not a live platform identity measurement.

inspect obtains a passing report matching pack/version/Python/API/ABI and snapshots bounded
hash entries. It returns a process-local identity ticket. authorize invokes the explicit
approval callback with immutable request/scope, rechecks preflight afterward and issues
a capability only if binding is unchanged. dispatch reserves the ticket once, rechecks
preflight again, atomically consumes the capability, invokes the trusted runner and then
validates its envelope/payload against the consumed receipt and independently collected
observations. Approval denial, callback errors, stale bindings, expiry and exceptions are
terminal for that ticket. A runner exception never refunds a consumed claim.

The coordinator owns one authoritative registry and retains bounded ticket tombstones
(default 256). Concurrent dispatches have one winner. Callbacks run outside coordinator
locks. close prevents new issuance/claims; it does not stop already-dispatched work.
A future native Stop/lifecycle bridge must handle that work and cleanup. No runner error
is reported as confirmed cleanup, and no filesystem artifacts are deleted here.

Digest encoding v1: signed int32 big-endian value count (including domain), followed by
signed int32 big-endian UTF-8 byte length and exact bytes for each value. Inventory fields
are key/hash pairs ordered by Kotlin string ordering on the preflight report's names.
Inspection binds pack ID/version/Python/API/ABI/inventory digest. Scope binds nonce,
inspection/inventory/script digests, app/device identity, executable and wall/output limits.
Separate domains prevent cross-purpose substitution. This is a coordinator-specific
binding of the preflight report, not a signed portable manifest or a proof of file-to-exec
atomicity. A fixed approved script digest must cover its import allowlist until explicit
import/resource policy encoding is integrated. Full native effective-limit binding remains
required before installing a production runner. These digests do not establish a sandbox.

Runner must collect trustworthy observations and enforce allocation/generation/disk/time
caps before constructing Submission. A returned validated result is only eligible for
future bounded storage, never admission or user-script permission. Persistence, recovery,
durable replay journals, native cancellation and physical-device acceptance remain pending.
All callbacks are blocking; future Android callers must dispatch them off the UI thread.

Twelve JUnit 4 tests exercise the synthetic full pipeline, rejected identities/preflight,
missing runner, denial/errors, stale binding before issuance/dispatch, deterministic inventory
ordering, expiry, concurrent dispatch/runner failure, closure/forgery/capacity and foreign
observation requests. No Python, Chaquopy or fallback path is added.
