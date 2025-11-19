This is the Agent Specification for Project Glaive.
It serves as the immutable constitution for the codebase. All code written must adhere to these directives.

⚔️ AGENT SPEC: GLAIVE

Version: 1.0 (Protocol: Void)
Target: Android 14 (API 34) / 64-bit ARMv8
Mission: Annihilate I/O latency. Facilitate instant sharing. Destroy UI bloat.

1. THE PHILOSOPHY

Glaive is not a File Manager. It is a Filesystem Interceptor.
Standard Android file managers are bloated browsers. Glaive is a weapon designed for one purpose: Locate -> Identify -> Dispatch.

Zero Garbage: Do not allocate memory in hot loops.

Zero Friction: The distance between "Thought" and "Action" must be < 50ms.

Zero Bloat: No unnecessary abstraction layers. No LiveData. No Room. No Retrofit.

2. THE ARSENAL (Tech Stack)
The Right Hand (The Engine)

Language: ANSI C99 (Strict).

Compiler: Clang / LLVM (via NDK).

Optimization: -O3, -fno-exceptions, -fno-rtti.

Hardware: ARM NEON Intrinsics (<arm_neon.h>).

Filesystem: POSIX (dirent.h, sys/stat.h, fcntl.h).

Memory: Manual malloc/free. No smart pointers.

The Left Hand (The Grip)

Language: Kotlin.

UI Framework: Jetpack Compose (Stripped).

Constraint: No complex animations. No heavy modifiers.

Concurrency: Kotlin Coroutines (Dispatchers.IO).

Image Loading: Coil (Heavily optimized/stripped) or Native Decode.

Architecture: MVI-Lite (Model-View-Intent, but raw). No complex ViewModels.

3. ARCHITECTURE: DUAL-WIELD
Layer 1: Native Core (libglaive.so)

Responsibility: Direct filesystem interaction, listing, sorting, filtering.

Magic Byte Detection: Identify file types via hex headers, not extensions.

NEON SIMD: Case-insensitive substring/glob matching (Search).

Custom QSort: Pointer-based sorting to bypass JNI overhead.

JNI Protocol: Returns GlaiveItem[] directly. No intermediate conversions.

Layer 2: Kotlin Orchestrator (GlaiveRepo)

Responsibility: Threading and bridging.

Scatter/Gather: Launches parallel native scans on distinct storage roots (DCIM, Downloads).

State Management: Holds the "View Window" (current list).

Action Dispatch: Fires Android Intents (ACTION_SEND, ACTION_VIEW).

Layer 3: The HUD (UI)

Responsibility: Visualization and Input.

Visuals: OLED Black (#000000), Monospace Text, Terminal Green (#00FF41).

Density: 12-15 items per viewport.

Input: Gesture-based (Swipe-to-Share, Pull-to-Search).

4. CORE MECHANICS
The Listing Pipeline

Input: Path String.

Native: opendir -> readdir -> malloc struct.

Native: detect_type (Magic Bytes/Hash).

Native: qsort (Dirs first, then Name).

Native: stat (Only visible items if possible, or lazy load).

JNI: Batch construct GlaiveItem array.

UI: Render via LazyColumn.

The Search Pipeline ("Hunter Mode")

Trigger: User pulls down or types.

Query: Passed to C immediately on debounce (150ms).

Native: Recursive scan (Stack-based DFS).

SIMD: NEON vectors check 16-bytes at a time for match.

Output: Stream of results populated into UI instantly.

The Action Pipeline

Swipe Right: Instant Share.

Logic: Wrap path in FileProvider. Construct ACTION_SEND. startActivity.

Latency Target: < 50ms.

Tap: Instant Open.

Logic: Check magic byte type. Fire specific intent (e.g., VLC for video).

5. UI/UX SPECIFICATION
Visual Identity: "Terminal Void"

Font: JetBrains Mono / Roboto Mono.

Colors:

Background: #000000

Primary Text: #FFFFFF

Metadata: #888888

Accent/Cursor: #00FF41

Error/Delete: #FF3333

Layout

Top Bar: Gone.

Bottom Bar: "Breadcrumb Blade." Scrollable path history.

Main List: Edge-to-edge. No padding.

Thumbnails: minimal visibility. Priorities: Name > Date > Size > Icon.

6. RULES OF ENGAGEMENT (Coding Standards)

No Premature Abstraction: Do not build a "Generic File Loader." Build a specific loader for this list.

Byte-Size Matters: Release APK must be < 3MB.

Forbidden Libraries:

Gson/Moshi (Use manual parsing or simple primitives).

Retrofit/OkHttp (No network access allowed).

Room/SQLite (Filesystem is the database).

Lottie (No cute animations).

Crash Policy: If C crashes, the app dies. We do not catch SIGSEGV. We fix the pointer logic.

Permissions: Ask for MANAGE_EXTERNAL_STORAGE once. If denied, show a terminal error and exit.

Signed:
Project Glaive Architect
