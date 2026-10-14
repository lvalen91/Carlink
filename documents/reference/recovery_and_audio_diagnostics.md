# Recovery and audio diagnostics (October 2026)

Multi-day GM AAOS 12 logs exposed startup continuing after teardown, retry
self-cancellation, and a helper reader that could outlive its USB connection.

## Recovery changes

- Adapter initialization checks its session generation before each message and
  before creating the Wi-Fi timer/reader. A failed write aborts initialization;
  the manager only completes startup for the same still-current driver.
- Reconnect jobs own the pending delay, not the executing startup. Scheduling a
  successor therefore does not cancel the attempt that requested it. Coroutine
  cancellation is not reported as a transport failure.
- Helper open/close operations are serialized. Readers hold their own connection,
  endpoint, and generation; stale readers and backlog handlers cannot close a
  replacement connection. Callback delivery remains ordered and headless.
- A successful helper bind with no enumerated adapter is shown as waiting for USB,
  not helper failure. Missing-adapter retries continue at a capped 30-second delay;
  other failures retain the five-attempt limit. USB attach remains the fast path.

Both the main application and USB helper need updating for these changes.

## Audio diagnostics

Enable Debug logging and AUDIO_PERF. `[MUSIC_PLAYBACK]` and `[CALL_PLAYBACK]`
summaries report software pending audio, maximum packet duration/gap, accepted
output bytes, playback-head progress, zero-byte writes, and discarded call bytes.
`[MUSIC_UNDERRUN]` events include ring/residual state and packet age when observed.

Pending audio excludes Android/hardware-owned queues. Playback-head changes are
not end-to-end latency measurements; track replacement or flush can reset them.
Packet gaps can include transitions or intentional silence. Zero-byte nonblocking
writes mean the track cannot accept more data at that instant, not an error.

No music buffer size, prefill, adapter mediaDelay, or call buffering policy was
changed. Validate repeated key cycles, unplug/replug, an absent adapter that later
appears, and music/call sessions before attributing audible changes to recovery.

## October 9 follow-up: silence and USB failures

Live music PCM silence now reaches playback unchanged. Music silence diagnostics
check the full payload, not five sample positions, and count received packets and
bytes before buffer insertion. `acceptedTotalB` is cumulative for the current ring;
`receivedB` and `silencePackets` are interval counters. Navigation's existing
end-marker and zero handling is unchanged.

Helper read failures report header/payload phase, completed/expected bytes,
transfer result/duration, timeout setting, device enumeration presence, last read
and write ages, last write result/duration, and pending callback queue depth.
`timeoutLike` is timing evidence, not a definitive kernel error classification.

Transport errors are dispatched outside synchronous Binder callbacks so cleanup
does not call back into a helper whose session lock is still held. Audio release
joins playback outside its recovery lock. `[USB_CLEANUP]` reports total teardown
time and individual steps taking 250 ms or longer.

These changes do not promise to prevent adapter power loss or firmware resets.
Both APKs must be updated for the USB diagnostics and callback cleanup changes.

## October 10 follow-up: session failures and process exits

Failure cleanup runs under the startup mutex. A session-generation gate allows
only one cleanup owner and ignores old-session callbacks. Explicit stop is
serialized with cleanup; startup checks invalidation after binding and before
creating its driver. Duplicate failures no longer consume extra retry attempts.

Music START/STOP commands mark the diagnostic stream state. Underruns after STOP
are labelled `intentional-stop`, not steady-playback starvation; playback policy
and buffers remain unchanged.

The application installs a process-wide uncaught-exception handler, writes a
bounded emergency `carlink-crashes.log`, and then delegates to Android's original
handler. On next launch it records new Android historical process exits in
`carlink-process-exits.log` (reason/status/importance/memory), including bounded
text traces for ANRs when supplied by the OS. Native crash stacks are not decoded
from binary exit traces. Export all logs includes these files. Abrupt power loss
or OS kills may not produce an uncaught exception; exit records may be unavailable.

`[USB_DELIVERY]` compares audio arrival gaps on the helper with callback queue wait
and callback duration. `[USB_CALLBACK_PERF]` measures app processing duration for
complete inbound messages. Summaries are bounded to roughly 30-second intervals.
Arrival gaps can include intentional media stops and must be interpreted alongside
stream commands, not as proof of wireless failure.

## October 13 recovery and media pipeline changes

- On retry exhaustion, an Activity-owned budget permits one automatic manager and
  surface rebuild. The budget survives that rebuild, so a new manager cannot loop
  rebuilds forever. Explicit Reset Device replenishes it; subsequent failure says
  to try Reset Device and replug only if that fails.
- Activity teardown, reinitialization teardown, and foreground/background codec
  work run off main. Debounced surface updates and overlay recovery use IO workers.
  Native/device operations may still block; monitor evidence guides further audits.
- While an Activity is started, a process watchdog probes main every three seconds.
  A missed 1.5-second response snapshots main and relevant worker stacks to bounded
  `carlink-main-stalls.log`, included in Export all logs. This is a diagnostic, not
  a guarantee of detecting every ANR or a reason to reset a live session.
- Media DATA and album covers use an ordered bounded metadata worker, with
  session-token checks and cache-reset synchronization. Queue overflow drops an
  update with a warning rather than blocking USB/audio. Navigation/control remain
  on the existing ordered path.
- Explicit media STOP clears software media samples; the playback thread pauses
  and flushes that track. Trailing media packets are ignored until START. START
  uses the existing prefill threshold. Calls/navigation remain separate. Transition
  commands and their playback application are logged under AUDIO_PERF.

Audio ring/track capacity and prefill sizes remain unchanged. Test song changes,
pause/resume, Siri/call interruptions, cameras, key cycles and retry exhaustion.

## October 13 follow-up: confirmed main-thread USB stalls

The Logs5 stall stacks match the release mapping and identify synchronous helper
open, initialization writes and MediaSession PAUSE writes on main. Startup now
enforces Dispatchers.IO internally. Media transport commands use a bounded ordered
worker and session-token/driver checks. Main-thread stop/release/reboot calls defer
to IO before acquiring teardown locks. Remote transport rejects synchronous writes
on main and asserts background-only connect, making missed callers visible.

One-second audio/video observations retain eight samples, emit current summaries
every ten samples and dump history for stalled progress or sustained arrival gaps.
Audio also detects runs of fully silent PCM while output progresses. Reports are
rate-limited to ten seconds; they do not trigger recovery. They distinguish input
age, accepted bytes, software pending audio, playback head and decoder/render
progress. Silence may be legitimate; the reports are not audible-glitch counts.
Audio monitoring runs independently of the playback writer.

Enable AUDIO_PERF and VIDEO_PERF in Debug logging. `[AUDIO_STALL_WINDOW]`,
`[VIDEO_STALL_WINDOW]`, short samples and `[COMMAND_SEND]` complement the existing
main-stall/process-exit files. Buffer sizes are unchanged.
