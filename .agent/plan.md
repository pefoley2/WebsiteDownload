# Project Plan

An Android application which mirrors and displays an offline copy of a website. The app should allow users to enter a URL, download the website's content (HTML, CSS, images, etc.) for offline viewing, and then browse that content within the app.

## Project Brief

# Project Brief: WebsiteMirror (MVP)

This application allows users to mirror and display offline copies of websites. By entering a URL, the app fetches all necessary assets (HTML, CSS, images) and stores them locally, providing a seamless browsing experience even without an internet connection.

## Features
- **URL Mirroring Engine**: A robust service that traverses a provided URL to download HTML, CSS, JavaScript, and image assets for offline use.
- **Offline Content Browser**: An integrated viewer designed to render locally stored website files while maintaining internal link integrity.
- **Mirror Management**: A simple dashboard to view, launch, or delete previously downloaded website mirrors.
- **Progress Tracking**: Real-time visual feedback showing the status and progress of active downloads.

## High-Level Technical Stack
- **Language**: Kotlin
- **UI Framework**: Jetpack Compose
- **Navigation**: **Jetpack Navigation 3** (state-driven) for managing transitions between the dashboard and the viewer.
- **Adaptive Strategy**: **Compose Material Adaptive** library to ensure a consistent experience across phones, tablets, and foldables (e.g., using a List-Detail pane for mirrors).
- **Concurrency**: Kotlin Coroutines & Flow for efficient background asset downloading and state management.
- **Networking & Storage**: OkHttp for asset fetching and the Android File System (Internal Storage) for persistent offline content.

---
*Note: The UI Design Image section has been omitted as the image generation tool is currently unavailable.*

## Implementation Steps
**Total Duration:** 22m 7s

### Task_1_ProjectSetupAndNavigation: Set up project dependencies for Navigation 3, Material Adaptive, and OkHttp. Define core data models for website mirrors and initialize the basic navigation graph.
- **Status:** COMPLETED
- **Updates:** 1. Dependencies for Navigation 3, Material Adaptive, and OkHttp added.
- **Acceptance Criteria:**
  - Dependencies added to build.gradle
  - Core Mirror models defined
  - Navigation 3 shell implemented
- **Duration:** 4m 45s

### Task_2_MirroringEngineAndStorage: Implement the mirroring engine using OkHttp and Coroutines. Handle recursive downloading of HTML, CSS, and images. Implement local storage logic and internal link remapping for offline integrity.
- **Status:** COMPLETED
- **Updates:** 1. Implemented `MirrorEngine` using OkHttp, Jsoup, and Coroutines.
- **Acceptance Criteria:**
  - Successful asset fetching and storage
  - Internal links remapped correctly
  - Background download logic implemented
- **Duration:** 6m 6s

### Task_3_UIAndManagement: Build the main Dashboard using Material Adaptive List-Detail pane. Implement the URL entry and progress tracking UI. Create the Offline Content Viewer for rendering mirrored sites.
- **Status:** COMPLETED
- **Updates:** Fixed crash by making data models Parcelable. Added kotlin-parcelize plugin. All models used in navigation state are now saveable.
- **Acceptance Criteria:**
  - Adaptive UI works on different screens
  - Download progress is visible
  - Local content renders in Viewer
- **Duration:** 2m 49s

### Task_4_RunAndVerify: Perform a final run of the application to ensure stability and alignment with the requirements. Verify the end-to-end flow from URL entry to offline browsing.
- **Status:** COMPLETED
- **Updates:** 1. App verified as stable by Critic agent.
2. Crash during state restoration fixed and verified.
3. Mirroring flow (Download -> Storage -> WebView) works end-to-end.
4. Adaptive layout logic confirmed in code.
5. Edge-to-edge implemented.
6. Minor UX suggestions logged for future refinement.
- **Acceptance Criteria:**
  - App builds successfully
  - App does not crash
  - Website mirrors successfully offline
  - All existing tests pass
- **Duration:** 8m 27s

