import React, {useState} from 'react';
import {checks, evaluateAdmission} from './admissionModel.mjs';

export default function AdmissionSimulator() {
  const [values, setValues] = useState({});
  const result = evaluateAdmission(values);
  const failedIndex = checks.findIndex(check => check.id === result.failedId);
  return (
    <section className="admissionSimulator" aria-label="RuntimePack admission simulation">
      <h3>Try the admission model</h3>
      <p><strong>Simulation only.</strong> Select assumed outcomes. This component does not inspect files, verify signatures, run Python, or call NativeSupervisor.</p>
      <fieldset>
        <legend>Assumed check outcomes</legend>
        {checks.map((check, index) => (
          <label className="admissionCheck" key={check.id}>
            <input type="checkbox" checked={values[check.id] === true}
              onChange={event => setValues({...values, [check.id]: event.target.checked})} />
            <span>{check.label}<small>{failedIndex < 0 || index < failedIndex ? 'Modeled pass' : index === failedIndex ? 'First rejection' : 'Not reached'}</small></span>
          </label>
        ))}
      </fieldset>
      <p role="status" aria-live="polite"><strong>{result.status === 'rejected' ? 'Simulated rejection' : 'Simulated admission'}:</strong> {result.reason}</p>
      <button type="button" className="button button--secondary" onClick={() => setValues({})}>Reset assumptions</button>
    </section>
  );
}
