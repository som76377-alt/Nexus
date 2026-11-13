# J6 — Provider Lab phone evidence

J6 is intentionally evidence-first. Do not change the default provider order or hedge timing until all four verbatim reports below have been collected and compared.

## Required evidence set

Capture one complete Provider Lab report from each combination:

| Evidence ID | Device | Network |
|---|---|---|
| P1 | Primary phone (iQOO Z9s Pro 5G) | Mobile data |
| P2 | Primary phone (iQOO Z9s Pro 5G) | Wi-Fi |
| P3 | Secondary test phone | Mobile data |
| P4 | Secondary test phone | Wi-Fi |

If a device cannot use one of the network types, record that combination as unavailable with the reason. Do not substitute a report from another device or network.

## Capture procedure

1. Install the same debug build on the device being tested.
2. Record the app version shown in the report.
3. Connect only to the named network (mobile data or Wi-Fi).
4. Open the hidden Provider Lab using the project's documented debug entry point.
5. Run the existing NewPipe, Piped and Invidious provider checks separately, without forced failures or the timed HTTP 403 injection.
6. Use **Copy report** and paste the entire report unchanged into the matching file below.
7. Record any visible failure, retry, timeout, or playback interruption separately in the notes section. Do not edit the copied report.

## Report files

- `P1-primary-mobile-data.txt`
- `P2-primary-wifi.txt`
- `P3-secondary-mobile-data.txt`
- `P4-secondary-wifi.txt`

These files are intentionally absent until real device reports are supplied. Never create synthetic or reconstructed reports.

## Tuning gate

Only after all four reports are present:

- Compare provider success by device, network, sample, and IP family.
- Identify whether failures are provider-specific, network-specific, or shared.
- Tune the data-only default provider order only where the evidence supports a stable improvement.
- Add or tune hedge timing only after verifying the current resolver's actual scheduling behavior and measuring the trade-off between first-result latency and fallback success.
- Preserve a safe offline/bundled configuration and the existing resolver failure/cooldown semantics.
- Record before/after rationale and tests in the work log.

If every provider fails on a phone, stop order tuning and escalate the J5 investigation. That escalation must remain within ordinary, authorized extraction and diagnostics; do not attempt to bypass platform verification or anti-bot safeguards.

## Evidence status

**PENDING — no physical-device reports have been supplied yet.** CI results are not substitutes for these four reports.
