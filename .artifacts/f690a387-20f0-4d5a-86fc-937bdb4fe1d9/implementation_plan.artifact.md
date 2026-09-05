# Implementation Plan - Fix Navigation State Restoration Crash

The goal is to resolve the "MirrorItem is not saveable" crash by making `MirrorItem`, `WebsiteMirror`, and `MirrorStatus` `Parcelable`. This ensures that the navigation state and adaptive scaffold state can be correctly saved and restored by the Android system.

## User Review Required

> [!IMPORTANT]
> The `kotlin-parcelize` plugin will be added to the project. This is a standard Kotlin plugin for Android that generates `Parcelable` implementations automatically.

## Proposed Changes

### Build Configuration

#### [MODIFY] [libs.versions.toml](file:///C:/Users/pefol/AndroidStudioProjects/WebsiteDownload/gradle/libs.versions.toml)
- Add `kotlin-parcelize` plugin definition.

#### [MODIFY] [app/build.gradle.kts](file:///C:/Users/pefol/AndroidStudioProjects/WebsiteDownload/app/build.gradle.kts)
- Apply the `kotlin-parcelize` plugin.

### Data Models

#### [MODIFY] [WebsiteMirror.kt](file:///C:/Users/pefol/AndroidStudioProjects/WebsiteDownload/app/src/main/java/com/pefoley/websitedownload/data/WebsiteMirror.kt)
- Add `@Parcelize` annotation and implement `Parcelable`.

#### [MODIFY] [MirrorStatus.kt](file:///C:/Users/pefol/AndroidStudioProjects/WebsiteDownload/app/src/main/java/com/pefoley/websitedownload/data/MirrorStatus.kt)
- Add `@Parcelize` annotation and implement `Parcelable`.

#### [MODIFY] [MirrorViewModel.kt](file:///C:/Users/pefol/AndroidStudioProjects/WebsiteDownload/app/src/main/java/com/pefoley/websitedownload/ui/viewmodels/MirrorViewModel.kt)
- Add `@Parcelize` to `MirrorItem` and implement `Parcelable`.

## Verification Plan

### Automated Tests
- Run `./gradlew :app:assembleDebug` to ensure the project still builds.
- (Optional) Run any existing unit tests.

### Manual Verification
- Deploy the app and navigate between screens.
- Perform a configuration change (e.g., rotate the screen) to trigger state restoration and verify no crash occurs.
