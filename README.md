# CleanListen for Android

Mutes swear words in whatever is playing on an Android phone (YouTube, podcasts, music, most apps), about 5 seconds behind live. Speech recognition runs **on the phone** (Vosk). Nothing is recorded or saved: audio only passes through a short memory buffer that is constantly overwritten.

Size: about 60 MB installed.

## Install (Pixel)
1. On the phone, open this page and download **CleanListen.apk** from the [latest release](../../releases/latest).
2. Tap the downloaded file. If asked, allow Chrome to "install unknown apps," then tap **Install**.
3. Open CleanListen and do the **one-time setup** in the yellow box: turn on **CleanListen volume** in Accessibility.
   - If it says *restricted setting*: Settings > Apps > CleanListen > ⋮ (top right) > **Allow restricted settings**, then turn it on.
4. Tap **Start filtering**. Allow audio and notifications. When it asks what to share, choose **Entire screen**, then **Start**.
5. Play something in any app. The meter should move and the "Words muted" count goes up when a word is caught.
6. Tap **Stop** when done. Media volume goes back to where it was.

## How it works
- Android's playback capture hears other apps' sound.
- The sound is held about 5 seconds while Vosk turns it into words.
- Words on the list are muted before the sound reaches the speaker.
- Android can't silence another app directly, so CleanListen sets media volume to 0 and plays the cleaned copy on the separate accessibility volume. That's what the one-time setup is for.

## Known limits
- Apps that block capture (some streaming and audiobook apps, like Audible) can't be filtered. The meter stays flat.
- Bluetooth headphones with "absolute volume" may go silent when media volume is 0. If so, use the phone speaker or wired earbuds, or turn on *Disable Absolute Volume* in Developer options.
- The small speech model misses some words, especially in loud music.

Word list and matching are the same as CleanListen for Mac. Inspired by [profanity_sensor](https://github.com/shreyansh-56/profanity_sensor).
