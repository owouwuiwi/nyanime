# Nyanime host player presentation

Engine-independent controls, timeline, sheets and gesture feedback. The host
supplies state and callbacks. The attributed FLOW UI subset retains the
approved visuals without FLOW's player, extraction, account, network or
preference runtime. MELD is not a dependency.

Original contracts/adapters are Apache-2.0. The selected FLOW UI and scoped
resources remain GPL-3.0-only; see PROVENANCE.md and third-party/flow-ui/NOTICE.
Both license sets are bundled offline. The entire module accompanies
application corresponding source.

One host-owned timeline preview is committed only on a real pointer release;
cancellation, disposal and media changes cancel it. Keyboard/accessibility
seeks remain supported. The blank video area must not intercept host gestures.
Reduced motion follows the same player theme contract.

Verify with :presentation-player:testDebugUnitTest,
:presentation-player:assembleRelease, then scripts/verify_player_presentation.py
against the resulting AAR. Checks reject excluded runtime classes and native
engines and verify notices. Host APK and physical-device tests remain separate;
earlier independent-renderer trials do not validate this port.
