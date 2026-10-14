# Rolling diagnostics and streaming recovery

Local file logging is enabled once when upgrading to this diagnostic build, using
the Normal preset. Subsequent changes in Settings → Logs remain respected.
Files rotate at approximately 5 MiB; old files are pruned at rotation/start to
keep approximately 40 MiB of retained history, with the existing seven-day expiry.
Logs contain device identifiers and media metadata, but no call recordings.

After a problem, open Settings → Logs and press **Export all logs to USB / storage**.
Select the removable drive in Android's storage picker and save the ZIP. Export
flushes pending entries and snapshots all retained files before copying. Logging
continues during the external copy. If GM's document provider does not expose the
drive, this app cannot force access without additional system privileges; another
destination exposed by the picker can be used.

The helper delivers USB messages on one ordered, bounded worker, preserving the
relationship between phone-state notifications, audio commands and video frames.
It no longer silently drops video frames on a separate dispatch queue. A full
128-message queue reports a USB delivery-backlog error and triggers client recovery.
Transfer IDs are atomic and queued deliveries are scoped to their session.
Chunk slicing uses byte arrays directly to avoid per-byte boxing allocations.

Calls and Siri retain separate AudioTracks and now use 120 ms ring capacity,
20 ms startup prefill, and a 2× platform-minimum AudioTrack buffer. Music retains
its existing buffering. PCM cursor updates, overflow handling and microphone
trimming are serialized to prevent replay/corruption under contention. Audio route
changes are included in operational logs to verify whether calls reach PHONE_CALL.

Truck validation is required: test startup video, music under load, a long call,
sleep/resume, and ZIP export to removable storage. Build success alone does not
establish that the hardware symptoms are resolved.
# Purging diagnostics

Settings → Logs → **Purge all logs** asks for confirmation, then permanently removes
app-private rolling logs, crash/process-exit and main-stall files, plus
the cached diagnostic ZIP. It never deletes copies already exported to USB/storage
or vehicle/app settings. If logging was enabled, a new rolling file starts; if it
was disabled, purge leaves it disabled. Fresh live events can recreate diagnostic
files after purge. Queued diagnostic writes from before purge are invalidated.
Deletion runs on IO; failures are reported rather than claiming full success.
