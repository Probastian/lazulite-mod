# Verification Report -- Steam World Hosting

Verified against features/steam-world-hosting/specification.md (RESOLVED
Open Questions 1-7) and features/steam-world-hosting/plan.md. No live
in-game testing was performed (Non-goals, Open Question 7, and this task
own explicit "must NOT launch the client" constraint) -- verification relied
entirely on gradlew build/test output, javap -p / javap -c against the
real resolved jars in the Gradle caches, and static grep/read cross-referencing.

## 1. Build and test -- independently confirmed GREEN

- gradlew compileJava test build (incremental, no --rerun-tasks):
  BUILD SUCCESSFUL, all 46 actionable tasks up-to-date across api,
  services, features:*, and all three platform:fabric-* modules
  (compile, mixin annotation processing, jar/remapJar for fabric-1.21.11).
- gradlew :features:steam-world-hosting:test re-run independently: green.
  Test-result XML counts (TEST-*.xml, tests=/failures=/errors=):
  SteamWorldHostingConfigTest=2, SteamWorldHostingConfigIOTest=7,
  ConnectStringCodecTest=12, HostGatewayTest=4,
  HostingPresenceScannerTest=5 -- 30/30, 0 failures, 0 errors. Matches the
  claimed count exactly.

## 2. javap spot-check of the two self-reported "divergent" mixin claims -- confirmed accurate

Extracted the real resolved jars and ran javap -p / javap -c directly
(not trusting the implementer citations at face value):

- Yarn 1.21.11 ClientConnection (minecraft-merged-...yarn.1_21_11...jar):
  confirmed two overloaded static connect methods exist -- the plain
  ClientConnection connect(InetSocketAddress, NetworkingBackend, MultiValueDebugSampleLogImpl)
  and the targeted ChannelFuture connect(InetSocketAddress, NetworkingBackend, ClientConnection).
  The mixin full-descriptor selector
  (Lnet/minecraft/network/ClientConnection;connect(...)Lio/netty/channel/ChannelFuture;)
  is necessary and correctly disambiguates; javap -c confirms real
  Bootstrap.group/channel/connect(InetAddress,int) invocations inside that
  exact overload bytecode. getAddress() and setupEncryption(Cipher,Cipher)
  both exist as claimed.
- Mojang-mapped 26.2 Connection (minecraft-merged-deobf-26.2.jar):
  confirmed the client-connect builder signature really did change --
  ChannelFuture connect(InetSocketAddress, EventLoopGroupHolder, Connection)
  (a different parameter type than the legacy prototype equivalent) -- and this
  connect name is unambiguous on this side (only one method literally
  named connect), so the mixin bare method="connect" selector is
  correct without a full descriptor. javap -c confirms the same
  Bootstrap.group/channel/connect(InetAddress,int) call sequence inside this
  method. setEncryptionKey(Cipher,Cipher) exists as claimed.
- Also independently verified (not flagged as divergent, but load-bearing):
  ServerNetworkIo.bind(InetAddress,int) (Yarn) and its two
  ServerBootstrap.childHandler(...)/.group(...) call sites inside bind
  really exist in bytecode; IntegratedServer.setupServer()Z/isRemote()Z
  (Yarn) and .initServer()Z/.isPublished()Z (26.2) all exist with the
  claimed signatures; LoginKeyC2SPacket (SecretKey, PublicKey, byte[])
  constructor and NetworkEncryptionUtils.encrypt(Key, byte[]) both exist
  matching ServerKeyPacketMixin redirect target.
- .claude/context/minecraft.md was updated with three new rows recording
  these findings, consistent with the repo living-record convention.

One unverifiable risk found during this check, not raised in the
implementer own report: platform/fabric-26.1 and fabric-26.2
ConnectionSteamChannelMixin declares @Shadow @Final private
io.netty.channel.Channel channel, but javap -p shows the real field is
private io.netty.channel.Channel channel -- NOT final. Whether
SpongePowered Mixin @Final-on-@Shadow annotation strictly rejects this
mismatch at mixin-application time (a runtime/classload-time check, not a
compile-time one) could not be determined without launching the client, which
this task explicitly forbids. This is flagged as an unverified risk, not a
confirmed bug -- the 1.21.11 variant avoids the issue entirely by checking
getAddress() instanceof SteamAddress instead of shadowing the field, so only
the two Mojang-mapped modules carry this risk.

## 3. Requirements/Open-Questions cross-check against code

All seven RESOLVED Open Questions are reflected as written:
1. Unconditional "always host" -- SteamWorldHostingClientInitializer has no
   toggle beyond config enabled; HostingLifecycle/WorldHostingHookHolder
   have no manual stop short of world unload. Confirmed.
2. Reuses SteamworksServiceHandoff/SteamAvailability -- confirmed, .require()
   called, never re-constructed.
3. Legacy SteamNetworking P2P surface -- confirmed used throughout
   (SteamServerChannel/SteamNettyChannel/SteamAmbientSession), no
   ISteamNetworkingSockets/Messages reference anywhere.
4. services/-not-api/ placement for the shared gateway -- confirmed:
   SteamFriendsGateway/SteamworksSteamFriendsGateway/NoopSteamFriendsGateway
   all live in services/src/main/java/de/lazuli/services/steamworks/;
   FriendsService.java import list no longer contains
   com.codedisaster.steamworks.* at all (grep-confirmed) -- refactor is
   real and complete, not partial.
5. Reuse of the existing "Join game" context-menu slot (index 3) -- confirmed
   in FriendContextMenuWidget (all 3 platform modules): isEnabled(3) and
   the mouseClicked index-3 branch now route through
   hostingStatusReader/worldJoinRequester instead of
   FriendSidebarStateMachine/FriendActionListener.onJoin; no new fifth
   LABELS entry was added.
6. Fixed-stub-digest auth -- confirmed via ServerKeyPacketMixin
   (null-key-safe encrypt redirect) and the handshake-stub mixin pair; no
   gold-plating attempted, matching the RESOLVED acceptance.
7. No live in-game testing -- respected by this verification pass too, per
   the task own constraint.

FR0.1-FR0.3, FR1.5, FR2.1-FR2.3, FR4.1-FR4.3 are all implemented and
unit-tested or code-reviewed as claimed. FR1.1/FR0.2 have a real gap --
see Section 6 below.

## 4. Architecture boundaries -- no violations found

- grep for friendssidebar/friends-sidebar inside
  features/steam-world-hosting/src/main/java/ and for worldhosting inside
  features/friends-sidebar/src/main/java/: zero matches either direction
  -- no Feature-to-Feature import exists.
- Cross-feature bridging is composition-root-only, exactly the ADR-0003 shape:
  SteamWorldHostingClientInitializer publishes WorldHostingBridgeHandoff
  (real or Noop pair); FriendsSidebarClientInitializer calls
  WorldHostingBridgeHandoff.requireJoinRequester()/requireHostingStatusReader()
  and threads them through FabricFriendsSidebarInjector into
  FriendContextMenuWidget two new nullable constructor parameters.
  Confirmed end-to-end for fabric-26.2; same shape present on fabric-26.1/
  fabric-1.21.11.
- All three fabric.mod.json files list
  "de.lazuli.SteamWorldHostingClientInitializer" in "client", correctly
  positioned after SteamworksClientInitializer and before
  FriendsSidebarClientInitializer/SteamCloudSyncClientInitializer (the
  plan Decision-4/Risk-2 ordering requirement) -- this satisfies this task
  explicit "confirm the entrypoint is actually wired" check; the feature is
  not silently inert on any of the three platforms.
- Netty/mixin glue is duplicated per platform (de.lazuli.worldhosting
  package, de.lazuli.mixin.* mixins) and not shared beyond api/services,
  as the spec Architecture section requires.

## 5. Deviations from the plan -- evaluated

1. Two extra mixins (ServerConnectionListenerCaptureMixin,
   ServerKeyPacketMixin) beyond the plan four named ones -- both are
   necessary (pipeline-capture and null-key-safe encrypt are load-bearing
   prerequisites the plan own Decision 6 narrative implied but did not
   enumerate as separate files) and both were javap-confirmed against real
   targets. Justified, not scope creep.
2. SteamFriendsGateway.setJoinRequestedListener(...) -- a small, additive
   method needed to wire Steam native "Join Game" overlay callback (FR3.1
   path 1) through the shared gateway rather than a second direct
   SteamFriends construction. Justified, consistent with Decision 1
   "entirely plain-Java-typed" contract shape.
3. Loopback-placeholder plus pending-target field instead of full
   ServerAddress/ServerNameResolver address-smuggling -- confirmed in
   SteamAmbientSession.connectToSteamPeer ("127.0.0.1" placeholder,
   ConnectScreen.connect(...), mixin substitutes the real SteamAddress
   before Bootstrap.connect fires). Simpler than the prototype and
   consistent with the spec own Non-goals ("v1 does not touch Direct
   Connect"). Reasonable, smaller-surface simplification, not a regression
   against any stated requirement.
4. NoopHostGateway/NoopHostingLifecycle unreferenced -- confirmed via
   grep: both classes exist in
   features/steam-world-hosting/src/main/java/.../services/ but are never
   constructed anywhere. This is dead code, not a masked wiring bug per se --
   see Section 6, which found a different, real bug in the disabled path.

## 6. Bug found: FR0.2/FR0.3 violated by an unconditional ephemeral Netty bind

All three platforms IntegratedServerWorldHostingMixin calls
self.getNetworkIo().bind(null, 0) (Yarn, IntegratedServer#setupServer) /
self.getConnection().startTcpServerListener(null, 0) (26.1/26.2,
#initServer) unconditionally on every singleplayer world load, before
checking WorldHostingHookHolder.isEnabled(). Only the subsequent
WorldHostingHookHolder.onWorldLoad() call (which does correctly no-op when
the feature was never publish()-ed, i.e. Steam unavailable or config
disabled) is gated.

This means an extra ephemeral Netty server-listener bind happens even when
Steam is unavailable or steam-world-hosting.json enabled field is false --
directly contradicting FR0.2 "no hosting pipeline is bootstrapped ...
the integrated server ... functions exactly as vanilla, with zero behavioral
change" and FR0.3 "when false, behaves identically to Steam being
unavailable." The bind is to an OS-ephemeral port and never advertised, so the
practical security/network exposure is low, but it is still real,
unconditional extra work/resource use (a live Netty server channel plus whatever
ServerBootstrap construction it triggers) that the spec explicitly says must
not happen in the disabled case. This should block full FR0.2/FR0.3
sign-off and be fixed by moving the bind call behind
WorldHostingHookHolder.isEnabled() (or an equivalent pre-check) before
calling getNetworkIo().bind(...)/startTcpServerListener(...).

Confirmed identical on fabric-1.21.11, fabric-26.1, fabric-26.2.

## 7. What remains unverified (accepted gap, per task constraint)

No live in-game testing was performed at any point (no runClient, no game
window) -- per this task explicit remote-control constraint and the spec
own Open Question 7/Non-goals framing. As a direct consequence, the following
are claims only, not independently confirmed by this pass:
- Real Steam P2P handshake success (SendP2PPacket/ReadP2PPacket
  round-tripping actual Minecraft protocol bytes end-to-end).
- Real mixin injection/application at runtime (Mixin own transformer
  actually accepting all eight registered mixins without a
  MixinApplicatorError) -- most classes/methods were javap-confirmed to
  exist with matching descriptors, but the @Shadow @Final field-modifier
  mismatch noted in Section 2 is a genuine open risk this static approach
  cannot fully resolve.
- Actual in-game "Join World" context-menu click behavior, and the native
  Steam overlay "Join Game" button actually appearing/working.
- Real NAT traversal / two-real-Steam-account join success.
- The FR1.4 isPublished()/isRemote() pause-suppression actually taking
  effect during real gameplay.
- FriendsService refactor (Decision 1) not regressing any of Friends
  Sidebar own already-shipped behavior (avatar loading, rich presence
  status text, etc.) -- compiles and the existing test suite (unaffected,
  since FriendsService itself has no dedicated unit tests per that
  feature own known Risk 8) still passes, but no in-game re-verification of
  that feature own acceptance matrix was performed here either.

## Overall Verdict: PASS, with one accepted known risk (@Shadow @Final, Section 2) deferred to live testing

The implementation is substantially complete, faithful to the spec/plan, and
the vast majority of claims (green build, 30/30 tests, javap-confirmed mixin
targets including both self-reported divergences, correct entrypoint wiring
on all three platforms, clean architecture boundaries, all resolved Open
Questions reflected in code) were independently confirmed rather than taken
on faith. Section 6 documented a real, confirmed violation of FR0.2/FR0.3 (an
unconditional ephemeral-bind) across all three platform modules; this has
since been fixed by the implementer and independently re-verified (Section 8)
as correctly gated behind WorldHostingHookHolder.isEnabled() on all three
modules, with build/tests still green. The @Shadow @Final mismatch (Section 2)
remains an accepted, unresolved risk deferred to a future live-testing pass. Everything else is an
accepted, previously-flagged gap (no live in-game verification) rather than a
surprise.

## 8. Re-verification of FR0.2/FR0.3 bind-ordering fix

Targeted re-check (not a full re-pass) of the Section 6 bug after an
implementer fix. Read the current IntegratedServerWorldHostingMixin.java
on all three platform modules directly (not the implementer diff):

- fabric-1.21.11 (setupServer, Yarn), fabric-26.1 and fabric-26.2 (initServer,
  Mojmap): the ephemeral bind call (getNetworkIo().bind(null, 0) /
  getConnection().startTcpServerListener(null, 0)) is now wrapped in
  if (WorldHostingHookHolder.isEnabled()) { ... }, placed before the
  unconditional WorldHostingHookHolder.onWorldLoad() call, which was left
  untouched and not duplicated. All three modules are textually identical in
  this method (diffed against each other, zero output).
- WorldHostingHookHolder.isEnabled() (identical across all three modules,
  read at platform/fabric-*/src/main/java/de/lazuli/worldhosting/WorldHostingHookHolder.java)
  returns lifecycle != null && canJoin != null, i.e. true only after publish()
  has run. Traced publish()'s only call site,
  SteamWorldHostingClientInitializer.onInitializeClient():
  active = steamworksService.isSteamAvailable() && config.enabled(); when
  !active it returns early (publishing only the Noop bridge pair) without ever
  calling WorldHostingHookHolder.publish(...). So isEnabled() is genuinely
  false in both the Steam-unavailable and config-disabled cases, and the new
  guard actually achieves FR0.2/FR0.3 (no bind, no extra Netty work) rather
  than just compiling. All three platforms' SteamWorldHostingClientInitializer.java
  are identical here too (diffed, zero output).
- gradlew compileJava test build re-run (incremental, no --rerun-tasks):
  BUILD SUCCESSFUL, 46 actionable tasks, all up-to-date. Cross-checked that
  this wasn't a stale-cache false pass: the compiled
  IntegratedServerWorldHostingMixin.class under fabric-1.21.11/build/classes
  has a newer mtime than the current .java source, confirming the class
  actually reflects the just-read, fixed source rather than a pre-fix
  artifact.
- Out of scope per this task and left untouched: the @Shadow @Final risk on
  fabric-26.1/26.2's ConnectionSteamChannelMixin (Section 2).

**Outcome: PASS.** The Section 6 bug is confirmed fixed on all three
platform modules; build and tests remain green.
