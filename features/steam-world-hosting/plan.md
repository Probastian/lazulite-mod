[... existing file content above is unchanged, see original plan.md for the base Steam World Hosting implementation plan ...]

## Amendment: Auth-Mode Fix — Implementation Plan (2026-07-21)

Mirrors the specification's own "Amendment: Auth-Mode Fix" heading/naming
convention (`features/steam-world-hosting/specification.md`'s Amendment
section), appended here per that same convention rather than restructuring
the original plan above. This is a plan for an **edit to already-shipped,
already-verified code** (the original plan's Decision 6 mixin set, confirmed
built/tested green in `verification-report.md` Sections 1–2 and re-verified
after a prior targeted fix in Section 8) — not new design work. No
implementation code is written by this plan.

Authoritative requirements: specification FR1.3, FR1.6 (corrected), FR1.7
(new), FR5.1 (corrected scope), the Networking "Handshake/auth bypass"
paragraph and its reversed annotation, and Open Questions 8–11. This plan
does not re-derive or second-guess any of those; it only locates the exact
edit points and sequences the change.

### Existing Implementation (read directly off disk this pass)

**The mixin family is *not* uniformly "byte-for-byte mirrored" across all
three modules — two distinct groups exist, both needing the same fix:**

1. **`ServerLoginStubDigestMixin`** — three near-identical copies, one per
   module, all targeting the server-side login handler and all containing
   the same five `@Redirect` sites gated on
   `connection.getRemoteAddress()`/`getAddress() instanceof SteamAddress`:
   - `platform/fabric-26.2/src/main/java/de/lazuli/mixin/ServerLoginStubDigestMixin.java:38-92`
     — `@Mixin(ServerLoginPacketListenerImpl.class)`, `@Shadow @Final Connection connection`,
     redirects on `PublicKey#getEncoded()` (in `handleHello`) and, in `handleKey`:
     `ServerboundKeyPacket#isChallengeValid`, `#getSecretKey`, `Crypt#getCipher`,
     `Crypt#digestData` (stubs a `new byte[20]`).
   - `platform/fabric-26.1/.../ServerLoginStubDigestMixin.java` — **byte-for-byte
     identical** to the 26.2 file (confirmed via direct read; same class names,
     same method bodies, same line count).
   - `platform/fabric-1.21.11/.../ServerLoginStubDigestMixin.java` — **same
     shape, different (Yarn) names**, per the spec's own flagged "may have a
     slightly different subset, verify": `@Mixin(ServerLoginNetworkHandler.class)`,
     `@Shadow @Final ClientConnection connection`, redirects on `onHello`
     (`PublicKey#getEncoded()`) and `onKey` (`LoginKeyC2SPacket#verifySignedNonce`,
     `#decryptSecretKey`, `NetworkEncryptionUtils#cipherFromKey`,
     `NetworkEncryptionUtils#computeServerId`, stubbing the same `new byte[20]`).
     **Confirmed: the same five-redirect subset exists on all three modules —
     no smaller/larger subset on 1.21.11**, only renamed classes/methods
     (already logged in `minecraft.md`'s "Integrated-server Netty/login
     networking stack" row, reused, not re-derived here).

2. **`ClientHandshakeStubDigestMixin`** (client-side counterpart, same
   real-vs-debug condition per the Amendment's "Scope of the reversal"
   note) — also three copies, same two-group split: 26.2/26.1 byte-for-byte
   identical (`@Mixin(ClientHandshakePacketListenerImpl.class)`, redirects on
   `ClientboundHelloPacket#getPublicKey()` and `Crypt#digestData`); 1.21.11
   Yarn-named (`@Mixin(ClientLoginNetworkHandler.class)`, redirects on
   `LoginHelloS2CPacket#getPublicKey()` and
   `NetworkEncryptionUtils#computeServerId`). Same `instanceof SteamAddress`
   condition shape as the server side, confirmed by direct read of all three
   files — this correction applies to it identically.

3. **`ConnectionSteamChannelMixin`** (`platform/fabric-26.2/.../ConnectionSteamChannelMixin.java:101-107`,
   mirrored on 26.1/1.21.11) — contains `lazuli$killDoubleEncryption`, an
   `@Inject`-cancellable hook on `setEncryptionKey`/`setupEncryption` that
   cancels double-encryption **only when `channel instanceof SteamNettyChannel`**
   (a transport-layer check, not a `SteamAddress`-remote-endpoint check).
   **This condition is transport-shape, not auth-bypass-shape, and is
   correctly out of scope for this fix** — it is not tied to the
   "is Mojang session verification being stubbed" question at all; disabling
   Minecraft's own double-encryption on top of an already-Steam-encrypted
   channel remains correct and desired for every Steam P2P connection,
   real or dev, per FR3.2/Networking (unchanged, unflagged by the Amendment).
   Also contains the `Bootstrap.channel/group/connect` hijack (FR3.2,
   entirely unrelated, unaffected).

4. **`ServerKeyPacketMixin`** (`platform/fabric-26.2/.../ServerKeyPacketMixin.java`,
   mirrored on the other two modules) — makes `ServerboundKeyPacket`'s
   constructor null-key-safe (`key == null -> return new byte[0]`) so the
   already-nullified secret key from `ServerLoginStubDigestMixin`'s
   `lazuli$nullifySecretKey` redirect doesn't NPE inside `Crypt.encryptUsingKey`.
   **This mixin has no `instanceof SteamAddress`/`FabricLoader` condition of
   its own at all** — it is a pure null-safety patch that only ever matters
   when `ServerLoginStubDigestMixin` has actually nulled the key. Once that
   mixin is gated to fire only in a dev environment, this one's null-key
   branch simply becomes unreachable in the real-session case (the real key
   is never null), with zero code change needed here.

**`FabricLoader.getInstance().isDevelopmentEnvironment()` availability** —
no new research needed; `net.fabricmc.loader.api.FabricLoader` is already a
established, repo-wide import (e.g.
`services/src/main/java/de/lazuli/services/steamworks/ClasspathSteamLibraryLoader.java:38`,
`FabricLoader.getInstance().getConfigDir()`), confirmed present in all three
platform modules' dependency graph already. `isDevelopmentEnvironment()` is a
stable, no-argument, no-overload Fabric Loader API method with no known
cross-version divergence (it is Fabric Loader's own API, not
Minecraft/Yarn/Mojang-mapped, so the obfuscation boundary does not apply to
it at all) — this is genuinely the trivial, no-risk case the task description
anticipated, and needs no further `javap` verification beyond confirming the
import already resolves (it does, per the precedent above).

**`minecraft.md`'s existing rows** — the "Integrated-server Netty/login
networking stack" row already documents `ServerLoginPacketListenerImpl`/
`ServerLoginNetworkHandler`'s `handleHello`/`handleKey`/`onHello`/`onKey`
names and signatures on both sides of the obfuscation boundary; no Loom-dev-
environment-detection precedent exists in that file prior to this pass (this
amendment is the first feature to use `isDevelopmentEnvironment()` inside a
mixin body), consistent with Open Question 10's framing of it as "a fourth
option not originally named."

### Files to Modify

All six files below get the **same one-line-shaped change**: an early-return
guard added at the top of every `@Redirect` method (or, more precisely, at
the top of each method's body, before the existing `instanceof SteamAddress`
check), gated on `!FabricLoader.getInstance().isDevelopmentEnvironment()`.
No new class, no new file, per the Amendment's "Mechanism (corrected)"
section.

1. `platform/fabric-26.2/src/main/java/de/lazuli/mixin/ServerLoginStubDigestMixin.java`
2. `platform/fabric-26.1/src/main/java/de/lazuli/mixin/ServerLoginStubDigestMixin.java`
3. `platform/fabric-1.21.11/src/main/java/de/lazuli/mixin/ServerLoginStubDigestMixin.java`
4. `platform/fabric-26.2/src/main/java/de/lazuli/mixin/ClientHandshakeStubDigestMixin.java`
5. `platform/fabric-26.1/src/main/java/de/lazuli/mixin/ClientHandshakeStubDigestMixin.java`
6. `platform/fabric-1.21.11/src/main/java/de/lazuli/mixin/ClientHandshakeStubDigestMixin.java`

**No change** to `ConnectionSteamChannelMixin.java` (×3, double-encryption
kill stays unconditional on `channel instanceof SteamNettyChannel`, per
Existing Implementation item 3 above — it is not part of the auth-bypass
condition this fix reverses) or `ServerKeyPacketMixin.java` (×3, no
condition of its own to change, per item 4). **No change** to
`IntegratedServerWorldHostingMixin.java` (×3) — explicitly withdrawn by the
Amendment's "Mechanism (corrected)" section ("needs no change for this fix").
**No change** to `fabric.mod.json`/`lazuli.mixins.json` on any module (no new
mixin class registered, no entrypoint-ordering change). **No change** to any
`config/steam-world-hosting.json` shape (Configuration annotation, no new
field).

### Where/how the guard is inserted (per method, both mixin classes)

Read directly off each mixin body (not guessed): every `@Redirect` method in
both `ServerLoginStubDigestMixin` and `ClientHandshakeStubDigestMixin`
follows the identical two-branch shape —

```java
private <T> lazuli$xyz(...) {
    if (connection.getRemoteAddress() /* or getAddress() */ instanceof SteamAddress) {
        return <stub-value>;
    }
    return <real-call>(...);
}
```

The corrected guard is inserted as a **new first branch**, ahead of the
existing `instanceof SteamAddress` check, in every one of these methods
(five per `ServerLoginStubDigestMixin`, two per `ClientHandshakeStubDigestMixin`,
identical shape on all three modules for each class):

```java
private <T> lazuli$xyz(...) {
    if (!FabricLoader.getInstance().isDevelopmentEnvironment()) {
        return <real-call>(...); // real, non-debug session: never stub
    }
    if (connection.getRemoteAddress() /* or getAddress() */ instanceof SteamAddress) {
        return <stub-value>;
    }
    return <real-call>(...);
}
```

This is equivalent to, but slightly more mechanical/lower-risk than, folding
`isDevelopmentEnvironment()` into the existing `instanceof` condition as a
single combined boolean (`isDevelopmentEnvironment() && ... instanceof SteamAddress`)
— both shapes are acceptable; the plan does not mandate one exact textual
form, only that the net effect is "the stub branch is reachable only when
`isDevelopmentEnvironment()` is true," per FR1.6/FR1.7/FR5.1 and the
Amendment's own pseudocode. Implementation may choose either the early-return
form shown here (matches the Amendment's own `Mechanism (corrected)` snippet
almost verbatim) or a combined-condition form, whichever produces the
smaller diff against the existing method bodies — **not** a redesign of the
methods' control flow either way. One new import,
`net.fabricmc.loader.api.FabricLoader`, is added to both mixin classes on
all three modules (six files total).

**No change needed inside any `@Redirect`'s method body beyond this one
guard** — the existing `connection`/`SteamAddress` shadow fields, method
signatures, and `@At`/target strings are all untouched; this is purely an
added condition inside already-existing method bodies, not a change to any
mixin's injection point, target class, or target method.

### Test Strategy

Per the specification's own accepted no-live-testing scope (Non-goals, Open
Question 7, unchanged by this amendment) and the original plan's own Test
Strategy section (same framing, reused): this fix introduces **zero**
plain-JVM-testable seam of its own (pure mixin-body logic gated on a
Fabric-Loader-native, mixin-context-only call — `FabricLoader.getInstance()`
cannot be meaningfully exercised outside a running Fabric-Loader-initialized
process, let alone the `instanceof SteamAddress` check it now guards).
Verification is therefore limited to, in order:

1. **Compilation** — `gradlew compileJava` (or the full `build`) across all
   three platform modules with the guard added to all six files; a green
   compile confirms the new `FabricLoader` import resolves and the control
   flow is well-typed (no new checked exceptions introduced — every existing
   method's `throws` clause is unchanged since the new branch calls the same
   already-declared-throwing real methods, or in the early-return real-call
   case, exactly duplicates the existing fall-through `return <real-call>(...)`
   line that already exists at the bottom of the same method).
2. **Code review against the six confirmed call sites above** — for each of
   the five (`ServerLoginStubDigestMixin`) + two (`ClientHandshakeStubDigestMixin`)
   redirect methods on all three modules (21 individual method bodies total,
   3 modules × 7 methods), confirm the new guard is present, correctly
   ordered ahead of the existing `instanceof` check, and that its "real path"
   branch calls the exact same real method the existing fall-through already
   calls (no logic drift introduced by the edit).
3. **No re-verification of unrelated, already-shipped parts of this feature**
   (Rich Presence, connect-string codec, Friends Sidebar bridging, hosting
   lifecycle, `SteamServerChannel`/`SteamNettyChannel` data-plane) is in
   scope for this pass, consistent with the task's own instruction and this
   repo's precedent for scoping a small fix (`verification-report.md`
   Section 8's "targeted re-check (not a full re-pass)" framing for the
   prior bind-ordering bug fix — same discipline applies here).
4. **No live in-game testing** (unchanged accepted gap, Open Question 7) —
   in particular, this fix's own most important real-world consequence (a
   real handshake now running end-to-end over `SteamNettyChannel`/
   `SteamServerChannel` for the first time, per Open Question 11's second
   bullet) is **not** verifiable by this workflow and remains an explicitly
   flagged, accepted gap for a future live-testing pass — restated here, not
   newly discovered.

### Risks

1. **This is a real, shipped-behavior reversal of already-verified code**
   (Open Question 11), not a purely additive change — `ServerLoginStubDigestMixin`'s
   unconditional-for-all-`SteamAddress` behavior was itself previously
   verified as intentional (`verification-report.md` Section 3, item 6:
   "Fixed-stub-digest auth -- confirmed... no gold-plating attempted,
   matching the RESOLVED acceptance"). That acceptance is explicitly
   withdrawn by this amendment for the real-session case; a future
   verification pass must re-confirm the corrected condition rather than
   assume the prior PASS still applies to this file.
2. **First-ever real handshake over `SteamNettyChannel`** (Amendment/Open
   Question 11) — a real (non-dev) Steam World Hosting session will, for the
   first time, run Minecraft's real RSA key exchange, real double-encryption,
   and real Mojang session-hash verification across the custom Netty
   channel/poller-thread pipeline (`SteamNettyChannel`/`SteamServerChannel`).
   This exact code path (real handshake bytes flowing through the
   channel-0/poller-thread pump) was never exercised before this fix, since
   every prior real session took the stub-digest path. Packet-size/ordering
   interaction with the poller loop is a genuine unknown this plan cannot
   resolve without live testing (accepted gap, restated from Open Question
   11, not new).
3. **All six edited files are near-identical, low-line-count mechanical
   changes, but touch security-relevant handshake code across three
   platform modules simultaneously** — a copy/paste-style edit error in any
   one of the seven method bodies per module (e.g. guarding the wrong branch,
   or accidentally leaving a stub reachable for real sessions) would
   silently reintroduce exactly the security weakening this fix is meant to
   remove. Mitigated by the plan's own explicit Test Strategy point 2
   (per-method code review against all 21 method bodies), not by any
   automated test (none exists for this file family, unchanged from the
   original plan's Risk 1/4 framing of this mixin family as inherently not
   unit-testable).
4. **No new risk to `ConnectionSteamChannelMixin`/`ServerKeyPacketMixin`**
   (Existing Implementation items 3/4) — confirmed via direct read that
   neither file's own condition needs to change, so this plan carries no
   risk of accidentally under- or over-scoping the fix into those two files;
   flagged only so implementation does not second-guess this and touch them
   unnecessarily.

### Acceptance Criteria

Mapped to the amendment's approved requirements:

1. **(Corresponds to FR1.6, requirement 1 above)** For a real (non-dev)
   launch, every one of the 21 redirect methods (7 per module × 3 modules)
   in `ServerLoginStubDigestMixin`/`ClientHandshakeStubDigestMixin` falls
   through to the real Minecraft/crypto call (`PublicKey#getEncoded()`,
   `ServerboundKeyPacket#isChallengeValid`/`getSecretKey`, `Crypt#getCipher`/
   `digestData` or their Yarn-named 1.21.11 equivalents) — never the stub
   value — regardless of whether the connection's remote address is a
   `SteamAddress`. Confirmed by code review per Test Strategy point 2.
2. **(Corresponds to FR1.7/FR5.1, requirement 2 above)** For a Loom
   dev/debug launch (`FabricLoader.getInstance().isDevelopmentEnvironment() == true`),
   all 21 methods retain their exact prior stub behavior for `SteamAddress`
   connections, unconditional on whether Steam World Hosting is otherwise
   active — i.e. the guard changes only the real-session case, never narrows
   or removes the existing dev-environment stub path.
3. **(Corresponds to FR1.3, unchanged)** No change to `HostGateway`/
   `canJoin`/`onP2PSessionRequest` friend-gating logic anywhere in this pass
   — confirmed by Files to Modify above listing no `services/`- or
   platform-`worldhosting`-package file touching that logic.
4. **(Corresponds to the task's minimal-scope requirement)** No file outside
   the six listed in Files to Modify is touched; no new mixin, no new class,
   no new config field, no `fabric.mod.json`/`lazuli.mixins.json` change, no
   `IntegratedServerWorldHostingMixin` change — confirmed by the Files to
   Modify section's explicit "No change" callouts above.
5. **Compilation** — `gradlew build` succeeds for all three platform modules
   with the six files edited and the new `FabricLoader` import added to two
   of them per module.
6. **Explicitly out of scope for this pass's own sign-off** (unchanged
   accepted gap): live confirmation that a real player's handshake actually
   succeeds end-to-end over `SteamNettyChannel`, and that the previously
   masked "Invalid session" failure mode for a non-owning account now
   surfaces correctly as vanilla's own disconnect screen (spec UI section's
   "Corrected note") — both require live testing this workflow does not
   perform (Open Question 7, Risk 2).

### Dependencies

No new external (non-Fabric) dependency — this fix uses only
`net.fabricmc.loader.api.FabricLoader` (Fabric Loader's own API, already a
transitive/direct dependency of every platform module via the existing
Fabric Loom/Fabric Loader toolchain, and already directly imported elsewhere
in this repo, e.g. `ClasspathSteamLibraryLoader.java:38`) — no Maven
Central/registry lookup is needed since no new coordinate is introduced.
