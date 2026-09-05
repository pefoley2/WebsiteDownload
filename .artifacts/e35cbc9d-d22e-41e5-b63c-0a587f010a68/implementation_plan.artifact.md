# Implementation Plan - Back Navigation and URL Formatting Improvements

This plan addresses two improvements suggested by the critic agent: implementing back navigation in the `DashboardScreen` and improving how URLs are stored and displayed.

## Proposed Changes

### UI Components

#### [MODIFY] [DashboardScreen.kt](file:///C:/Users/pefol/AndroidStudioProjects/WebsiteDownload/app/src/main/java/com/pefoley/websitedownload/ui/screens/DashboardScreen.kt)
- Add `BackHandler` to handle the system back button.
- If the `navigator` can navigate back (e.g., from Detail to List on phone), it will do so instead of exiting the app.

### Data & ViewModels

#### [MODIFY] [MirrorViewModel.kt](file:///C:/Users/pefol/AndroidStudioProjects/WebsiteDownload/app/src/main/java/com/pefoley/websitedownload/ui/viewmodels/MirrorViewModel.kt)
- Update `startMirror` to save a `metadata.json` file in the mirror's directory. This file will store the original URL.
- Update `loadMirrors` to read the `metadata.json` file if it exists, ensuring the original URL is displayed correctly in the UI.
- Improve the fallback for URL reconstruction if metadata is missing.

#### [MODIFY] [MirrorEngine.kt](file:///C:/Users/pefol/AndroidStudioProjects/WebsiteDownload/app/src/main/java/com/pefoley/websitedownload/data/MirrorEngine.kt)
- No direct changes needed here if we handle metadata in the ViewModel, but we might want to ensure the root directory is created before metadata is written. Actually, `startMirror` in `MirrorViewModel` already handles directory creation.

## Verification Plan

### Automated Tests
- Build the project to ensure no regressions: `./gradlew :app:assembleDebug`

### Manual Verification
- **Back Navigation**: On a phone-sized screen/emulator, navigate to the Detail pane (Add or select an item) and press the back button. It should return to the List pane.
- **URL Formatting**: Start a new mirror. Verify that the URL displayed in the list is the original URL (e.g., `https://google.com`) and not the path-like string (e.g., `https___google_com`).
- Check that existing mirrors (if any) still load correctly, even if they lack metadata.
