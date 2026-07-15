# Lynk Privacy Policy

**App:** Lynk (`app.lynk`)
**Last updated:** 15 July 2026

Lynk turns an Android phone into a chat and voice endpoint for AI agents that run on
your own computer. This policy explains what data Lynk handles, where it goes, and the
choices you have.

## Summary

Lynk is designed to be **local-first**. There is no Lynk-operated backend server that
collects your data. The app connects directly to a "bridge" running on a computer **you
control**, and to the AI providers **you choose to configure**. The developer of Lynk
does not receive, store, or sell your messages, audio, location, or screen contents.

## What Lynk handles, and why

- **Chat messages.** Text you send and receive is routed from the app to your paired PC
  bridge over a direct WebSocket connection, and from there to whichever agent backend
  you have selected (for example OpenClaw, Hermes, Pi, OpenCode, Codex, Devin, or an
  on-device local model). Messages are processed to provide the core feature of the app.
- **Voice / microphone audio.** When you start a realtime voice session or use composer
  transcription, audio from your microphone is streamed to the voice provider you have
  configured (OpenAI Realtime, using your own API key) to produce responses and
  transcripts. Audio is not retained by Lynk.
- **Approximate and precise location.** Location is read **only on demand**, when an
  agent tool you invoke explicitly requests the device's current location. It is not
  collected in the background and is not sent anywhere except to the agent handling that
  specific request.
- **Screen contents and device actions (Accessibility).** If, and only if, you enable
  the optional Accessibility service, agents you run can observe on-screen content and
  perform phone-control actions on the paired device. This is off by default and is used
  solely to execute the tool actions you request.
- **On-device model files.** Any `.litertlm` or `.gguf` model you import stays in the
  app's private storage on your device and is used for local inference.
- **Network / connection data.** Standard connection information (such as local network
  addresses used for pairing) is used to establish and maintain the link between the app
  and your bridge.

## Data sharing

Lynk shares data only with services **you** configure:

- **Your PC bridge** — software running on a computer you control.
- **AI/agent providers you select** (e.g. OpenAI for realtime voice, and your chosen
  host backend). Data sent to those providers is governed by their own privacy policies.

Lynk does not sell personal data and does not share it with advertising networks.

## Data retention

Lynk does not operate servers that retain your content. Conversation history and settings
are stored locally on your device and on the bridge you control, and can be cleared there.
Data processed by third-party AI providers is retained according to those providers' policies.

## Permissions

Lynk requests the following Android permissions for the purposes described above:
microphone (voice), overlay / display over other apps (the chat bubble), location
(on-demand agent tool), notifications, network access, foreground service (to keep the
connection alive), and — optionally — Accessibility (screen observation and phone control).

## Children

Lynk is a developer/productivity tool and is not directed to children under 13.

## Changes

We may update this policy as the app evolves. Material changes will be reflected here with
an updated "Last updated" date.

## Contact

Questions about this policy: am.will.ryan@gmail.com
