# Contributing to Tiny Tube

Tiny Tube focuses on a subscription feed, YouTube search, fullscreen playback and offline videos. Keep the APK small, use framework APIs and plain Java, and keep work off the main thread.

Bug fixes, YouTube compatibility and improvements to these core functions are welcome. Comments, playlists, casting, background audio, account login and other video services are outside the current scope. Discuss larger changes in an issue before implementing them.

Report bugs with the app version, Android version, device model, expected behavior and steps to reproduce. Remove personal information from screenshots and logs. Tiny Tube does not send automatic crash reports; share diagnostics voluntarily in an issue.

Each pull request must explain its user benefit, scope, APK size impact and validation. Maintainers review issues and pull requests for relevance before accepting changes; opening an issue does not imply a feature will be added.

## Release review

Before every release, review every change since the previous one. Accept only core features and fixes. Check source, manifest, scripts, resources, screenshots and the built APK for credentials and personal data. Never publish signing keys.

Record the reviewed commit in PUBLIC_REVIEW.md. Use a strictly increasing numeric tag such as v130; the release workflow uses 130 as both versionCode and versionName. Preserve the stable release signing key privately. Configure the public-release GitHub environment with a required maintainer reviewer before enabling releases.

Keep release notes focused on user-visible changes. Verify the APK signature and installation, core search/playback and offline behavior before approving a release. A snapshot audit and compilation alone are not a device test.
