# MIUI clipboard and phrase glass

Reference: user-supplied WeType-Tool-v2.0.1.apk, SHA-256
`778fe2ad1f784d04b4b4bffdd7e61353ffda4b2f0675fec953de1d722cfe3473`.
Static DEX inspection found popup setup at code offset `0x216974` and its
show/restore callback at `0x2ea738`. The callback reads `mInputMethodService`
and `insideView` from the **popup**, replaces the inside background, and hides
keyboard content through `mInputFrame` and `mCandidatesFrame`. It does not
read insideView from InputMethodService. The reference uses its existing IME
glass background; transparency alone does not create a glass material.

The connected OS4.0.0.8.XOCCNXM phone's MIUIFrequentPhrase.apk (SHA-256
`d9b4581b7dc02076da232ed24b16225936573764563edad0f8be9780d40e613a`)
has no insideView field or restoreInputArea method. Its clipboard_pop_view
layout has a FrameLayout root containing a LinearLayout with resource entry
name inside_view. Both initPopupWindow overloads show the popup, and dismiss
provides the cleanup boundary. Resource numeric IDs are not stable across ROMs.

Implementation resolves the old field or the new resource name, clears only
that container background, and inserts the existing HyperOS native material
behind the system content. The clipboard setting uses the appearance screen's
saved glass, blur and bloom parameters without popup-specific caps. If no glass
parameters are saved, it enables the shared default preset. Light/dark tint also
uses the appearance screen's colors. Reopening the popup reads the latest saved
snapshot. System adapters, cards,
text, selection states and click listeners retain their existing ownership.
The material follows the inside container's bounds and scale. Keyboard frame
children use alpha zero to preserve IME geometry; dismissal and hot reload
restore their original alpha and background. Pending retries are cancelled on
close. Unsupported native material restores the system panel background.

When the IME already has a visible appearance carrier covering the popup's screen
bounds, the popup reveals that carrier instead of stacking another native blur
surface. Otherwise it creates its own shared-parameter material. Card fills use
a light translucent tint; original backgrounds are restored on dismiss.
This includes the appearance screen's ordinary background blur when native
glass is disabled, so enabling the clipboard toggle does not force a second,
visually different native glass layer over that configured background.
Device validation of this branch reports native=false, glass=false,
carrier=[0,1587]/1200x1083, content=[0,1587]/1200x892, covered=true,
material=false and sharedUnderlay=true. The user confirmed the entrance dark
sweep is gone. Native-glass appearance still depends on enabling that mode in
the appearance settings; this validation was performed in ordinary blur mode.
Card styling is also reconciled before drawing, including late RecyclerView
bindings. Replaced drawables copy level, synchronize view state, and jump to
the current state. The user confirmed first-open outlines appear; their tint
was subsequently raised from alpha 0x12/0x18 to 0x30/0x38 for visibility.

Native WeType S15 clipboard/phrase items are styled separately through the
existing overlay-show reconciliation. No additional blur surface is created.
Only the first background-bearing group per list item gets the shared alpha
(dark 0x60, light 0x38) and a fixed 20dp rounded fill with clipped ripple feedback.
Nested card containers are not recolored again. Tab selectors, text, buttons
and image drawables keep native ownership. Pre-draw reconciliation covers
recycled items; detach and hot reload remove listeners and restore backgrounds.
The first device screenshot confirmed translucency but showed deformed corners
from cloning the host drawable; the fixed rounded-fill revision awaits device
confirmation of shape and opacity.

The initial implementation applied the material synchronously after
initPopupWindow. Device logs proved that isShowing was already true while
PopupDecorView was not attached, causing "Material carrier must be attached
before enabling pass-window blur". The fallback restored the opaque background,
and an existing Session prevented retries. Initialization now uses contentView.post
to wait for tree attachment, checks inside.isAttachedToWindow before creating a
Session, and guards queued work by popup callback identity so dismiss cancels it.

Validation: debug build and all 94 existing JVM tests pass. These tests do not
exercise private MIUI compositor APIs. The new APK was installed on the connected
phone and LSPosed successfully hot reloaded it. Runtime logs report material=true;
open clipboard and phrase screenshots confirm background blur and glass edge highlights.
The user also confirmed keyboard contents restore normally after dismissal.
Device screenshots are local ignored build artifacts, not committed resources.
Additional compatibility checks:
Check clipboard and phrases, scrolling, tab switching, dismiss/reopen, floating
and one-handed modes, light/dark mode, and disabling the clipboard glass toggle.
After closing, keyboard keys and candidate text must be visible again.

## Update discovery

MiuiPhraseDiscovery rule version 1 first validates the known popup and the two
verified show signatures: five ints, or five ints followed by a boolean, returning
void on a non-static method. The owner must extend PopupWindow and contain an
InputMethodService instance field. Missing known interfaces trigger an atomic
host-cache lookup keyed by rule version and SHA-256 of the loaded component APK
paths and contents. Cache records are revalidated against the current class loader.
Changed APK bytes invalidate fallback cache entries regardless of versionCode.

If no valid cache remains, DexKit searches the component APK for the observed
`initPopupWindow: token invalidated` log literal and applies the same contracts.
It accepts only one declaring popup class, never an arbitrary first match. The
selected class and show-method names are cached; failures retain system styling.
Layout lookup additionally supports a unique vertical direct child of the root
FrameLayout containing tab text and a RecyclerView if inside_view was renamed.
Service lookup supports a uniquely typed InputMethodService field if its name
changed. PopupWindow.dismiss provides cleanup even if the subclass no longer
overrides it. Discovery is reinstalled from popup handles during hot reload.

This is bounded compatibility, not guaranteed support for arbitrary ROM changes:
if the log literal, method contract or layout changes together, discovery rejects
the new component and logs the mismatch. It does not learn unverified signatures.
