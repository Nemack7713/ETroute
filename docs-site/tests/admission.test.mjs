import test from 'node:test';
import assert from 'node:assert/strict';
import {checks, evaluateAdmission} from '../src/components/admissionModel.mjs';

test('all 64 combinations stop at the first missing check', () => {
  for (let mask = 0; mask < 64; mask++) {
    const values = Object.fromEntries(checks.map((check, i) => [check.id, Boolean(mask & (1 << i))]));
    const failed = checks.find(check => !values[check.id]);
    const result = evaluateAdmission(values);
    assert.equal(result.failedId, failed?.id ?? null);
    assert.equal(result.status, failed ? 'rejected' : 'simulated-admission');
  }
});
test('missing or non-boolean inputs fail closed in the model', () => {
  assert.equal(evaluateAdmission().failedId, 'identity');
  assert.equal(evaluateAdmission({identity: 'true'}).failedId, 'identity');
});
test('acceptance explicitly denies actual execution', () => {
  const result = evaluateAdmission(Object.fromEntries(checks.map(check => [check.id, true])));
  assert.match(result.reason, /No runtime was inspected, admitted, or launched/);
});
