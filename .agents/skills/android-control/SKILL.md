---
name: android-control
description: Use when inspecting or controlling the user's connected Android phone through android-phone tools, including app navigation, screen-state questions, typing, Settings flows, notifications, or tasks that clearly depend on Android UI state. Applies observe-act-verify operation, avoids redundant observations, handles risky actions with confirmation, and verifies visible completion before final responses.
---

# Android Control

Use this skill only for Android phone-control tasks. Normal desktop, coding, browser, file, research, and assistant requests do not need phone tools unless the user asks to inspect/control the phone, refers to phone state, or the task clearly depends on an Android app or screen.

## Operating Loop

1. Start with phone_observe when you do not already have current screen context. 
2. Use `android-phone` tools to act.
3. Action tools such as phone_tap_node, phone_tap_xy, phone_tap_normalized, phone_type_text, phone_scroll, phone_swipe, phone_press_back, phone_press_home, and phone_open_app already return a post-action observation; treat that result as the next observation instead of immediately calling phone_observe again.
4. Continue until the requested final state is visible, explicitly confirmed, or blocked. Do not claim success after one partial step.
5. For multi-step tasks, track every requested subgoal and verify the final requested state.
6. Keep phone-surface status and final responses concise.

## Acting Efficiently

- Prefer stable node/text selectors and app/package launch tools over exploratory tapping.
- Use `phone_open_app` for clear app-open requests, then verify the observed package or screen summary matches the requested app.
- Use coordinates only when needed. If choosing a point from a screenshot, prefer `phone_tap_normalized` with `xPct`/`yPct`; use `phone_tap_xy` only with current full-screen physical pixels.
- Screenshots, node bounds, and gestures use full-screen coordinates including status/navigation bars. Do not subtract system bars.
- Use `phone_wait` only for visible loading, animation, or unsettled UI; prefer short waits around 300-400 ms.
- Do not use Back/Home as a reset habit. Use navigation only when the observation shows a wrong screen, dialog, overlay, dead end, or accidental navigation.
- Screenshots, node bounds, and gestures use full-screen coordinates, including the status and navigation bars. Do not subtract system bars.
- If tapping a location chosen from a screenshot that may have been displayed at a scaled size, use phone_tap_normalized with xPct/yPct fractions of the full screenshot. Use phone_tap_xy only when you have physical full-screen pixels from the current observation or screenshot metadata.
- Observations include display w/h and node bounds in physical pixels. Screenshot results include the exact screenshot w/h in physical pixels. Use the observation returned by action tools after taps instead of adding a redundant observe.
- **Opening app**: call `phone_open_app` with the app label first. If that fails or opens the wrong target, observe and retry with a package name only when known.

## Context And Overlays

- If System UI, notification shade, recents, lock screen, or another overlay is on top, use safe navigation such as Back/Home, short wait, and retry before reporting a blocker.
- Treat short follow-ups as referring to the current on-screen app, page, or task context unless the user names a different destination.
- When the user says "that", "it", "the result", "the video", or similar, ground the reference in the current observation before navigating away.

## Autonomy And Safety

- Observe, open apps, scroll, swipe, wait, and use Back/Home/Recents autonomously when they are appropriate.
- Taps, long presses, text entry, text submission, and screenshots require an enforced single-use approval capability. Before one of those actions, call phone_ask_user_confirmation with the exact target command and exact args object. Then pass the returned approvalCapability unchanged to that exact action without observing or navigating in between.
- Approval expires quickly, belongs only to the current tool session/run, and cannot be replayed or applied to different arguments. If the screen changes or authorization fails, request approval again; never reuse the old capability.
- Node actions must include both the observationId and nodeId returned together by the current observation. Never combine a nodeId with another observation or omit observationId.
- Example: {"tool":"phone_ask_user_confirmation","args":{"command":"tap_node","args":{"observationId":"<current observation UUID>","nodeId":"n17"},"message":"Open the selected item"}} followed, only after approval, by {"tool":"phone_tap_node","args":{"observationId":"<same observation UUID>","nodeId":"n17","approvalCapability":"<returned token>"}}.
- Biometric, fingerprint, passkey, password-manager, and OS credential prompts must remain manual.

## Final Responses

- Start with `TASK_COMPLETE:` only when the requested phone task is verified complete.
- Start with `BLOCKED:` when you cannot continue; include the current observed package/screen and the exact manual action needed.
