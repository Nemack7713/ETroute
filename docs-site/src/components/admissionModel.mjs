// Documentation only: user-selected booleans are not execution evidence.
export const checks = Object.freeze([
  {id: 'identity', label: 'Trusted publisher and expected pack identity', failure: 'Publisher or pack identity is not trusted.'},
  {id: 'integrity', label: 'Expected hashes verified against trusted metadata', failure: 'Integrity is missing or mismatched.'},
  {id: 'abi', label: 'Python, native dependencies, and device ABI match', failure: 'Runtime or dependency ABI is incompatible.'},
  {id: 'android', label: 'Android API and loader requirements match', failure: 'Android requirements are not satisfied.'},
  {id: 'location', label: 'Executable and import paths satisfy installation policy', failure: 'Installation paths are not approved for this device.'},
  {id: 'probe', label: 'Bounded startup and required-import probe passed', failure: 'Startup/import probe has not passed.'},
]);
export function evaluateAdmission(values) {
  const failed = checks.find(check => values?.[check.id] !== true);
  return failed
    ? {status: 'rejected', failedId: failed.id, reason: failed.failure}
    : {status: 'simulated-admission', failedId: null, reason: 'Simulation only: all modeled checks pass. No runtime was inspected, admitted, or launched.'};
}
