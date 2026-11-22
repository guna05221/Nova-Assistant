# Nova Assistant (Android starter)

A personal Android voice-assistant starter for the Nothing Phone 2a.

## Included in this first buildable milestone
- Jetpack Compose dark assistant screen
- Foreground microphone service shell
- Vosk local wake phrase detection integration for "hey nova" (requires a separately downloaded model)
- Android SpeechRecognizer command capture
- Basic commands: open apps, Android Settings, Google web search, Back, Home, scroll
- Writing prompt handoff through Android share sheet
- Explicitly user-enabled AccessibilityService with gesture-only capabilities

## Honest feature status
This is an early starter, not a finished assistant. No APK has yet been built or tested on a physical device.
- Wake-word listener requires the Vosk model in app/src/main/assets/model.
- SpeechRecognizer may use an online recognition provider; offline command recognition is not guaranteed.
- Search opens Google results in a browser; automated web extraction and AI summaries are not yet implemented.
- Writing currently shares a prompt to another app; AI writing inside Nova is not yet implemented.
- Tamil speech recognition and Tamil wake-word detection are not implemented yet.
- Accessibility gestures are limited to Home, Back and scroll. No screen reading, arbitrary tapping, messaging or file operations.

## Build
Requires JDK 17, Android SDK platform 35, Android Gradle Plugin 8.7.3, and Gradle 8.9.
Open in Android Studio or use the included GitHub Actions workflow.

## Install and permissions
Install the generated debug APK. Grant microphone and notification permissions. Open Nova and tap Start listening while the app is visible. Enable Nova under Android Settings > Accessibility. Android may restrict long-running microphone services and may stop them under battery policies.

## Safety
Accessibility is powerful. Nova does not silently send messages, make purchases, delete files or execute arbitrary actions. Keep user confirmation for sensitive actions.


## On-device AI model
- Local inference uses the llama.cpp Android AAR; model weights are downloaded separately into app-private storage.
- The current balanced profile is Qwen3.5 4B Q4_K_M (about 3.03 GB), with a 2,048-token context and CPU/NEON inference.
- Open Nova and choose **Download & activate local AI**. The first download needs Wi-Fi and several GB of free storage; subsequent inference runs offline.
- On an 8 GB phone, close other heavy apps before loading. Actual speed and memory use must be validated on the physical device.
- Once loaded, spoken commands are sent to the local model for structured intent planning. Only the explicitly allow-listed phone actions are executed. Message sending and other sensitive actions remain unsupported in this build.


## Hybrid agent milestone (November 2026)
- The local Qwen model now receives an intent-planning prompt that explicitly handles natural-language goals, including hunger/food discovery, rather than only exact command phrases.
- Added first agent tool: location-aware nearby restaurant search through Android Maps intents.
- Added Swiggy and Zomato HTTPS search handoffs, plus general web-search routing.
- Added Android coarse/fine location permission requests. The phone user must grant location access for location-aware results.
- Safety boundary: Nova can open searches and apps, but cannot place food orders, send messages, or pay without a separate explicit confirmation flow.
- Current milestone is an initial agent-tool integration, not a complete autonomous multi-step agent. Search results are shown by the destination app/site; Nova does not yet scrape or compare live restaurant listings itself. Generic online research, robust tool chaining, verified execution, conversational memory, and expanded app integrations remain subsequent milestones.


## Cloud backend foundation (development only)
- Vercel Function routes are scaffolded under `api/`: health, pairing session, and authenticated chat.
- Server-side OpenAI Responses API integration is disabled by default. No provider request is sent until `NOVA_CLOUD_AI_ENABLED=true` and server environment variables are configured.
- See [BACKEND_SETUP.md](BACKEND_SETUP.md) for local configuration and limitations, and [CLOUD_AI_ARCHITECTURE.md](CLOUD_AI_ARCHITECTURE.md) for the target design.
- No Vercel project has been created or deployed. No provider key or paid resource has been created.
