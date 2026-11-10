# Directional merge alerts and recipient selection

Merge alerts are **advisory information only**. DriveLink never steers, brakes, accelerates or otherwise controls a vehicle, and a merge
signal is explicitly *not permission* to merge. Drivers must check mirrors and blind spots.

## Left/right means the requester's left/right
"Left" and "right" are relative to the **requester's heading**, not the screen. `MergeRecipientSelector.assess(requester, direction, other, now, headingConfidence)`
projects the other vehicle into the requester's frame: `forward` (ahead +/behind −) and `lateral` (right +/left −). A vehicle is relevant if it is on the requested side
(`lateral × sideSign > 0`), in the merge window, and travelling the same way.

| Rule | Value |
|---|---|
| Max position age / GPS accuracy | 4 s / 40 m |
| Window | up to 15 m ahead, 30 m behind **+ 3 s of closing speed** (a fast car approaching from behind gets more notice) |
| Lateral reach | 10 m (+ half the position uncertainty) |
| Oncoming / crossing | ignored when both are moving ≥ 5 km/h and headings differ > 60° |
| Notify | confidence ≥ 0.40 |
| "May be merging" (uncertain) | confidence ≥ 0.15 within 40 m, or practically alongside (≤ 15 m) when GPS noise makes the side indeterminate |
| Heading unknown | only vehicles within 25 m, shown as "nearby vehicle may merge" (no side claimed) |

**Confidence** = side certainty (logistic of lateral offset ÷ combined GPS uncertainty, rescaled so a coin-flip is 0) × adjacent-lane band
(1.5–6 m best) × heading confidence (GPS course 1.0, compass 0.8, unknown 0) × position-age factor. Because lane-level accuracy is beyond phone GPS,
the UI never claims certainty: low confidence is worded "may merge".

## Flow
1. Driver taps ◀ Merge or Merge ▶ (3 s cooldown — no spam).
2. **Sender** evaluates every connected peer with its last known position and sends the request **only** to peers with verdict ≠ IGNORE.
   If nobody qualifies: "No vehicle detected on your left. This does not guarantee the lane is clear — check mirrors."
3. **Receiver** re-checks with the requester's tracked position/heading and **drops** requests that are not about it (defence in depth against stale data).
4. Receiver shows a temporary banner with a **left/right arrow**, the requester's temporary id (last 4 chars), where it is relative to the receiver, and voice once.
5. The alert disappears after **8 s**, or immediately when the requester taps **Cancel** (`LANE_CHANGE_REQUEST` with `act=CANCEL`).
   `act` is optional in the protocol; older peers that send only `dir` still work.

## Tests
`MergeRecipientTest` covers: correct side for N/S/E headings, mirror cases, 359°→1° wrap, far/ahead/oncoming/stale/inaccurate rejection, same-lane car,
closing-speed window, GPS-noise downgrade to "uncertain", compass vs GPS confidence, unknown heading, and a crowd scenario selecting only relevant vehicles.
Real traffic behaviour (lane geometry, GPS multipath, two phones) **needs on-road/device testing** — see TESTING.md.
