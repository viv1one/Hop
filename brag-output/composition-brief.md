# Hyperframes Composition Brief: HOP

## Objective
Create a short launch-style brag video for HOP, a serverless, no-accounts, proximity-first mesh app for photos/video and end-to-end encrypted messaging.

## Output
- Composition directory: `brag-output/composition/`
- Rendered video: `brag-output/brag.mp4`
- Format: landscape — 1920x1080
- Duration: 21 seconds

## Source Material
- Project root: /Users/vivone/Code/Hop
- Primary files read: README.md, mobile/android/app/src/main/kotlin/com/hop/app/theme/Theme.kt, mobile/android/app/src/main/kotlin/com/hop/app/composer (reach-tier picker), BUILD_PLAN.md
- Product name: HOP
- Tagline / strongest claim: "There is no HOP-operated backend that could be subpoenaed, breached, sold, or quietly start harvesting data — because it doesn't exist."
- Key UI or visual moment to recreate: the reach-tier picker (Locality / Town / City / Country pills) from the post-composer screen — the one mesh concept a user actually touches.
- Copy that must appear verbatim:
  - "THERE IS NO SERVER."
  - "No server to subpoena. No server to breach. No server to sell."
  - "Because it doesn't exist."

## Creative Direction
- Tone preset: cinematic
- Creative direction: a trailer for infrastructure you can't subpoena — **note**: only bundled music available is upbeat/driving ("happy-beats-business-moves"), not dark/brooding, so the emotional color is confident forward momentum rather than tension. Keep cinematic *structure* (big type, wide holds, dramatic reveals, restraint) with a driving, upbeat 120bpm bed rather than a moody one.
- Angle: HOP's whole pitch is a stated architectural fact, not a feature list — lead with the absence of a server as the most aggressive design decision in the video.
- Hook: black frame, "THERE IS NO SERVER." slams in, then "Because we never built one."
- Outro / punchline: three short claims held individually in near-silence, then "Because it doesn't exist," wordmark HOP, tagline "Serverless. Yours."
- Avoid:
  - Generic SaaS language ("streamline," "unlock," etc.)
  - Abstract filler visuals — every scene must reference something real from the app (the actual color system, the actual reach-tier picker, the actual product claim)
  - Unrelated visual redesign — use HOP's real Material 3 color tokens, not an invented palette

## Visual Identity
- Background: #121218 (dark surface, HOP's actual dark-theme background)
- Text: #F2F2F5 (HOP's actual dark-theme on-background)
- Accent: #9C93FF (HOP's actual dark-theme primary — indigo-violet), with #5B4FE8 as a secondary/deeper accent for depth
- Display font: system default (Inter/system sans is a faithful stand-in — HOP's real app uses Material 3 defaults, no custom display face)
- Body font: same system family
- Visual references from the project: the indigo-violet glow against near-black; the reach-tier pill selector (Locality/Town/City/Country); a simple node-and-line mesh motif standing in for BLE/WiFi Direct/DHT without naming protocols on screen

## Storyboard
Use the storyboard in `brag-output/brag-plan.md` as the creative contract.

Scene summary:
1. Hook — 3s — "THERE IS NO SERVER." then "Because we never built one."
2. Reveal — 3s — HOP wordmark + indigo glow + tagline "Serverless. No accounts. Proximity-first."
3. The flow — 5s — reach-tier pills (Locality/Town/City/Country) arrive in sequence, one selects, a photo card posts
4. The mesh — 6s — two-device connection line pulls back into a wider node mesh, text "Phone to phone. Then anywhere.", then a lock snaps over a message glyph with "Not even the relay can read it."
5. Punchline / outro — 4s — three "No server to..." lines held individually, "Because it doesn't exist.", wordmark + tagline hold

## Audio
- Audio role: cinematic support, driving/confident rather than brooding (see note above)
- Audio arc: near-silent under the hook → soft swell at the reveal → builds through the flow scene → peaks at the mesh pull-back/encryption beat → falls away for the punchline's spoken-word pacing → one low final hit under the last line
- Music: `happy-beats-business-moves-vol-1-by-ende-dot-app.mp3` (120.19 BPM, bundled cue preset available)
- Music treatment: start near 0 volume under scene 1, rise through scenes 2-3, hold high through scene 4, drop to near-silence for the first three punchline lines in scene 5, one low swell under the final line, soft tail under the wordmark hold
- Music cue guidance: bundled preset at `skills/brag/assets/music/cues/happy-beats-business-moves-vol-1-by-ende-dot-app.music-cues.json`. Beat grid is steady ~0.5s apart from t=3s onward (120bpm). First strongCue cluster starts at 16.02s (intensity 1.0), with more at 17.02/17.52/18.02/18.52/19.02/20.02 — this cluster falls right at the scene 4→5 boundary (t=17s in the plan); consider nudging the lock-snap beat or the scene 4→5 transition to land within ±0.15s of 16.02 or 17.02 for a beat-locked major moment. The tier-pill sequence in scene 3 (t=6-11s) has a clean steady beat grid available (beats at 6.03, 6.52, 7.02, 7.52 ...) for snapping each pill's arrival, but pills are short-label text (not full sentences) so beat-snapping is fine per the reading-time floor.
- Audio-reactive treatment: subtle — the indigo glow behind the Scene 2 wordmark and the Scene 5 final wordmark hold may breathe gently with music RMS/low end. Nothing beat-synced beyond that (no waveform/equalizer visuals).
- Audio-coupled moments:
  - Scene 1 hook slam — one low hit exactly on "THERE IS NO SERVER." landing
  - Scene 3 — 4 tier pills arriving in sequence, ideally snapped to the steady beat grid; a slightly more solid sound on the post-card lift
  - Scene 4 — one low swell/hit on the mesh pull-back reveal; one mechanical click on the lock snapping shut (candidate: land this near the 16.02-17.52s strongCue cluster)
  - Scene 5 — no stings on the three individual "No server to..." lines (let them land in near-silence); one low hit under "Because it doesn't exist."
- SFX selection guidance: sparse, motion-matched, no library-cliché whooshes/risers. `skills/brag/assets/sfx/keyboard/` has short keypress ticks that could work for the pill arrivals if their timbre fits; `skills/brag/assets/sfx/ui/` or `impact/` likely have a suitable soft click for the lock-snap and a solid low hit for the hook slam and final line — Hyperframes should choose exact files based on the implemented animation.
- SFX analysis guidance: `skills/brag/assets/sfx/sfx-analysis.md` / `sfx-analysis.json` — prefer lower high-frequency-risk sounds for the repeated pill-tick moment specifically, since it repeats 4 times in under 2 seconds.
- Exact SFX choice: Hyperframes should choose filenames, timestamps, density, and volume based on the implemented animation.
- Audio files: music copied to `brag-output/composition/assets/music/`; Hyperframes should copy any chosen SFX into the same `assets/` tree.

## Hyperframes Instructions
The specialized Hyperframes domain skills (`hyperframes-core`, `hyperframes-animation`, `hyperframes-creative`, `hyperframes-keyframes`, `hyperframes-cli`) are **not installed in this environment** — proceeding using the `npx hyperframes docs` reference (compositions, data-attributes, gsap, rendering) and CLI scaffolding (`hyperframes init`) instead, per explicit user direction to attempt this without them. Composition conventions below follow what `hyperframes docs` documents directly: `data-composition-id`/`data-width`/`data-height` on the root, `data-start`/`data-duration` + `class="clip"` on scene elements, GSAP paused timelines registered on `window.__timelines`, `<audio>` elements with `data-start`/`data-volume`/`data-track-index`.

Requirements:
- Show at least one real UI element from the source project (the reach-tier picker).
- Keep all text readable in the final render — respect the reading-time floors from `brag-plan.md`.
- Keep the video within 15-25 seconds (target 21s).
- Include the planned music/SFX layer (not disabled, not intentionally silent).
- Run `hyperframes check` before render.
