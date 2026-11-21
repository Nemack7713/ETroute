# Process-local probe authorization registry

Implementation slice of the proposed contract in draft PR #10:
https://github.com/Nemack7713/ETroute/pull/10

`ProbeAuthorizationRegistry` is internal to the Android library. Its trusted coordinator
must validate actual device inspection evidence and obtain explicit approval before
calling `issueApprovedScope`. Those gates are not implemented here. A fabricated
`ProbeScope` is not proof of inspection or approval. No production caller is wired.

Capabilities are opaque object identities bound to one registry and immutable scope.
An atomic synchronized claim checks expiry and current binding before consumption.
A mismatch revokes; a failed execution can never refund a consumed claim. A returned
receipt is evidence of registry consumption only, not a native execution permit.
The scope digest must eventually cover approved imports, effective resource limits
and the fixed script; canonical encoding and validation remain future integration work.

Use exactly one authority in one application process. Separate registries do not prevent
cross-registry reuse of a nonce. Replacement registries reject old objects; callers must
not reconstruct capabilities from persisted evidence. Closing a registry revokes outstanding
capabilities and permanently disables issuance. Consumed states/nonces remain as bounded
tombstones. At capacity, issuance fails closed; do not silently rotate to bypass this budget.

The implementation permits TTL up to five minutes and scope ceilings of five minutes
wall time / 16 MiB retained output. These are validation ceilings, not enforced execution
budgets or defaults. Clock is monotonic nanoTime; negative elapsed time fails closed.
There is no signer, durable journal, evidence JSON validator, device inspection runner,
NativeSupervisor permit bridge, Python launch or user-script authorization.

Seven JVM tests cover single use, concurrent claim, deadline/rollback, every binding field,
revocation/forged identifiers/replacement registry, invalidation and capacity/nonce retention.
The existing Android JVM CI task discovers these tests. Physical-device execution remains
unverified. The next slice is the bounded evidence parser and semantic validator, followed
by trusted coordinator integration; do not expose issueApprovedScope to untrusted requests.
