# Bounded probe evidence validation v1

Implements the validation slice of the proposed schema in draft PR #10:
https://github.com/Nemack7713/ETroute/pull/10

Requires API 26+, like RuntimePack preflight. The internal validator accepts envelope
bytes, exact payload bytes, a genuine consumed registry receipt, and trusted probe
observations from the future coordinator. No caller is wired into execution or storage.

Before parsing, the registry records exactly one evidence attempt by receipt reference
identity. A forged copy, foreign registry receipt or repeated submission rejects.
Malformed, oversized and failed submissions also burn the attempt. This is separate
from claim consumption, which occurred before execution. Bounded registry tombstones
remain retained. This is not a durable cross-process replay journal.

Envelope ceiling: 16 KiB. Payload ceiling: the trusted claimed output budget (itself
at most 16 MiB). Object nesting ceiling: four. Fields per object: sixteen. Strings/keys:
256 UTF-16 code units. Cooperative parse/hash deadline: 100 ms via monotonic nanoTime.
The deadline is checked between bounded operations; it is not a hard thread interruption
or a guaranteed device latency. UTF-8 decoding rejects malformed input. Gson 2.11's
strict streaming reader rejects comments, trailing documents, arrays and duplicate keys.
Exact per-object field sets and types enforce the v1 schema without reflection.

V1 deliberately accepts only null payloadRef and supplied bounded bytes. It opens no
paths. SHA-256 covers those exact bytes; envelope ordering does not affect that hash.
Do not substitute JSON reserialization or hash an envelope containing its own digest.
Timestamp must be a valid UTC calendar timestamp and exactly match the trusted observation;
recency/clock policy remains the future coordinator's responsibility.

Scope hashes, nonce, capability ID, build identity, ABI/API and limits must match the
real claim/observation. Actual duration, output counters, result, exit, signal, native
stage and cleanup must match trusted observations. Device binding is checked between
the observation and claimed scope. A JSON consumed flag alone is never authoritative.

Only completed, exit zero, no signal, native stage zero, within wall budget and confirmed
cleanup can be successful evidence. Failed/cancelled/timed-out/unknown observations and
cleanup uncertainty remain structurally valid failure records, not admission evidence.
Unconfirmed termination or retained artifacts produces retainSession=true. Wall-clock
budget overruns can be retained as unsuccessful records; output exceeding the claimed
retention cap rejects. The dropped-byte counter is informational and does not establish
a disk/output-generation cap. Parsing does not perform or prove cleanup.

Validated means eligible for later bounded storage, not persisted, signed, admitted or
authorized. Rejections return bounded reason codes only. A future persistence coordinator
must retain an appropriate bounded failure record and reconcile cleanup uncertainty.
Actual native probes, trusted observations, schema fixture parity automation, durable
replay storage, canonical scope/inventory hashing and physical-device acceptance remain
integration work. No Chaquopy, PRoot, fallback or Python execution is introduced.

Twelve JVM tests cover success/replay, bounds before parse, UTF-8/duplicate keys/trailing
input, depth/string limits, strict schema/calendar rejection, envelope ordering, scope and
observation mismatch, payload integrity, forged receipts, uncertain cleanup, invalid successful
outcomes, deadlines and concurrent submissions. These join the existing JUnit 4 CI task.
