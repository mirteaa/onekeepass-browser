# Bug report: Firefox/YouTube slowdown caused by unthrottled DOM-mutation login detection

## Metadata

| Field | Value |
| --- | --- |
| Project | OneKeePass Browser (`OneKeePass/onekeepass-browser`) |
| Affected version | 1.4.0 (main @ `272f50ca`, 2026-06-15) and earlier |
| Affected browser | Firefox (MV3 manifest with `all_frames: true`) |
| Component | Content script — login-field detection |
| Severity | High — main-thread starvation, video playback stutter, CPU load |
| Status | Patched locally; Firefox build produced and verified |
| Verification basis | Static code analysis + successful release build. **Not** reproduced in a live Firefox profile with profiling. |

## Summary

The content script observes every `childList` mutation on `document.body` and runs a **full-document login-field scan once for every added `<div>` or `<form>`**. YouTube is a continuously mutating single-page app: the player, ads, recommendations and hydration add many `<div>`s per second. Because the script is injected into every frame, the cost is multiplied across player/ad iframes. The result is main-thread contention that makes video playback lag.

A throttled, gated implementation existed in the same source file but was commented out with `#_`, while the calling code carried comments claiming the expensive path was already "debounced + gated". That mismatch is what kept the regression alive.

## Reported symptom

- YouTube video playback lags/stutters badly in Firefox.
- Slowdown correlates with the OneKeePass browser extension being active.

## Root cause (with evidence)

1. **Observer fires per mutation batch, with no throttle**

   Original `src/main/onekeepass/browser/content/mutation_tracking.cljs:50-53`:

   ```clojure
   (let [conf #js {:childList true, :subtree true}
         observer (js/MutationObserver. (partial dom-mutation-tracking-callback callback-on-observe))]
     (.observe observer body conf))
   ```

   `dom-mutation-tracking-callback` (lines 7-48) loops every mutation record and every added node, calling `callback-on-observe` **once per relevant node**. There is no `setTimeout`, no coalescing and no quiet-window check.

2. **The callback is the most expensive function in the content script**

   Original `src/main/onekeepass/browser/content/core.cljs:79`:

   ```clojure
   (mt/inputs-on-dom-observation form-fields/identify-page)
   ```

   `form-fields/identify-page` (`form_fields.cljs:357-410`) performs, per call:

   - `document.querySelector("flutter-view, flt-glass-pane, ...")` on every pass (`:355`);
   - `get-all-input-elements` — a `TreeWalker` over the whole document plus recursive traversal of **every open shadow root** (`:365`, `:592-635`);
   - `checkVisibility()` per text/email input (`:67-72`);
   - `getAttribute` calls plus `document.querySelector("label[for='...']")` per input (`:229-264`);
   - when a password input exists, several `querySelectorAll` calls and regex tests over form attributes (`signals.cljs:91-109`, `:144-165`).

   So one added `<div>` costs O(total DOM size). On YouTube that happens hundreds of times per second.

3. **The cheap gate existed but was not used**

   `core.cljs:58-60` defines:

   ```clojure
   (defn- detect-on-mutation []
     (when (js/document.querySelector "input[type=password]")
       (form-fields/identify-page)))
   ```

   It was never wired to the observer. `core.cljs:73-77` even claimed the active path was "Debounced + gated", but line 79 called the ungated, unthrottled `identify-page`.

4. **A complete throttled implementation was disabled**

   `mutation_tracking.cljs:65-115` contained a 400 ms throttle, a relevance filter and timer arming — all prefixed with `#_` (commented out). The comment warned that a reset-debounce would be starved by churning pages, which is exactly YouTube's behaviour.

5. **Cost multiplied across frames**

   Firefox manifest `browser-specific/firefox/manifest-firefox-v3.json:40-62` sets `all_frames: true` for both the MAIN-world passkey script and `content_main.js`. `core.cljs:42-50` only skips subframes that are `about:blank`/`about:srcdoc`/`data:` or smaller than 50×50 px in **both** dimensions. Normal-sized ad and player iframes still install the observer and run the scan.

6. **Unconditional logging amplified the cost**

   Every pass logs via `okp-println`/`okp-console-log` (`form_fields.cljs:359`, `:279`, `:373`, `:391`; `utils.cljs:24-28`), including `clj->js` conversion of DOM-backed maps when inputs are found. The release build does not elide these logs (`shadow-cljs.edn:55-57`).

## Expected vs actual behaviour

- **Expected:** dynamic DOM changes should trigger at most a small, bounded amount of detection work; a login-less page should incur essentially no scanning.
- **Actual:** each added container triggered a full-document scan in every frame, starving the main thread and degrading video playback.

## Related upstream context

- Issue `#1` ("100 procent usage") was closed by the maintainer as fixed in Desktop v0.25.0. That report concerned the native-messaging proxy process; it is a different symptom from this content-script/DOM issue.
- Open draft PRs `#3` and `#4` address frame binding and stale autofill icons. PR `#4` adds a `MutationObserver` on `documentElement` with `subtree: true` and `attributes: true`; if merged as written it would introduce a similar class of cost. It is not in main and was not applied here.

## Fix applied

Files changed in `/Users/mirte/Downloads/onekeepass-browser`:

1. `src/main/onekeepass/browser/content/mutation_tracking.cljs`
   - One active implementation only. Relevant additions arm a single detection pass after 400 ms; further mutations inside the window are absorbed, and the next relevant mutation re-arms it (throttle, not reset-debounce, so continuous churn cannot starve detection).
   - Relevance filter keeps only `<form>` and non-`okp-*` `<div>` additions; text nodes and the extension's own icon hosts are ignored.
2. `src/main/onekeepass/browser/content/core.cljs`
   - Observer now calls `detect-on-mutation`, so `identify-page` runs only when a password field exists in the light DOM.
   - The unconditional initial scan is retained, so logins present at load (including shadow-DOM fields) are still detected.
3. `docs/CHANGELOG.md` — documented the fix and its limitation.
4. `.gitignore` — ignore `node_modules/` and generated `extension-dist/js/`.

## Verification performed

- `npm run firefox` followed by `npx shadow-cljs release extension-dist`: **Build completed (591 files, 513 compiled, 0 warnings)**.
- Inspected generated `extension-dist/js/content.js`:
  - observer uses `setTimeout($run, 400)` (line ~994);
  - callback bound to `detect_on_mutation` (line ~980);
  - gate compiled to `document.querySelector("input[type=password]")` before `identify_page` (line ~998).
- No live Firefox profile or video playback benchmark was run.

## Residual limitations / follow-ups

- A login injected **dynamically inside a shadow root** is not detected by the mutation path; the initial scan and the **Redetect login fields** button still cover it.
- Detection latency after a form appears is up to 400 ms under churn.
- The built `.xpi` is unsigned; Firefox loads it as a temporary add-on via `about:debugging`, not as a permanent signed install.
- Suggested follow-ups: add a regression test for "N mutations produce 1 scan", profile on a real YouTube tab, consider narrowing `all_frames`, and remove the unused `webRequest`/`webRequestBlocking` permissions from the Firefox manifest.
