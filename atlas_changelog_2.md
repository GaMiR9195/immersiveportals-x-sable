Changelog
All notable changes to this project will be documented in this file.
The format is based on Keep a Changelog, and this project tries to adhere to Semantic Versioning.

Unreleased Changes
Atlas hardening since the 0.5.0 merge (PR #15):

Fixed
Multi-portal straddle: contact clipping is aperture-bounded again — the 0.5.0 full-plane clip let two sessions' half-spaces swallow a ship, and parts beside a free-standing frame lost ground collision.
Ships whose parent dimension is unloaded (e.g. a nether ship while everyone is in the overworld) fell through the world. They now go dormant (native Fixed body, zero tick cost) and wake in place when a player loads the area; no chunks are force-loaded.
Ships in different parent dimensions collided when their coordinates overlapped (per-body parent-frame check in the native dispatcher).
Connected ships got stuck on the wrong logical side of a portal: the per-member transit gate deadlocked once the first member crossed (gate removed).
A roped partner no longer teleports through together with the crossing ship.
Changed
Rigid assemblies (swivel bearings, fixed couplings — joints locking an angular axis) cross portals atomically as one unit.
Ropes span portals: a crossed ship's rope routes through the aperture and pulls the trailing body toward it; the chain follows once both ends cross, and backing out unwinds. Ropes overstretched past 1.75× natural length (snagged trailing body, cross-dimension splits) break, vanilla-lead-style.
Known Issues
Rare missed rope re-unification when both ends cross the same portal within a tick (diagnostics in place).