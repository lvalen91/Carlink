# Steering wheel voice button and Siri

Carlink exposes a minimal Android voice interaction service so AAOS can route
the configured assistant invocation to the connected CarPlay adapter. Select
**Carlink Siri** as the vehicle's default assistant to enable this route. The
service does not implement speech recognition or collect assistant context.
AAOS requires a recognizer component in this descriptor; its minimal service
declines local recognition without opening the microphone. Siri handles speech
through the connected phone.

When invoked, it sends the adapter's Siri button-down and button-up commands
through the active CarPlay session. The adapter's `AUDIO_SIRI_START` event then
activates Carlink's existing projection microphone and audio path. No new
recording path is started. If CarPlay is disconnected, the call is active, or a
voice request is already in progress, the request is declined; the app does not
start or reconnect a projection session on the assistant's behalf.

The command pair is sent on a background worker. Repeated requests are
debounced, and queued commands are discarded if their session becomes stale.
The assistant session closes after forwarding the request so the vehicle can
continue its normal projection flow. A brief message is shown only when the
request cannot be sent.

This depends on the vehicle allowing Carlink to be selected as its default
assistant and routing the steering wheel's short press to that assistant. Long
press behavior may remain owned by GM's projection or Bluetooth integration.
