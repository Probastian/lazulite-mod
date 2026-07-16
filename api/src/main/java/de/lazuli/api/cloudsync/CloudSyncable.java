package de.lazuli.api.cloudsync;

/**
 * A Feature-authored contract: any feature's own service may implement this
 * to export/import its own configuration as an opaque byte blob, so it can be
 * synced through Steam Cloud by {@code features/steam-cloud-sync} without
 * that feature ever importing another feature's classes (the
 * {@code Feature -> Feature} edge is forbidden per {@code architecture.md}).
 *
 * <p>This is a genuinely new wiring shape in this repo: a contract defined by
 * one Feature ({@code steam-cloud-sync}), implemented by an adapter bridging
 * a <em>different</em> Feature's state (today: {@code hello-world-main-menu}),
 * constructed and aggregated by the platform composition root into a
 * {@code List<CloudSyncable>} handed to {@code steam-cloud-sync}'s own
 * {@code CloudSyncCoordinator}. See
 * {@code docs/adr/0003-cloudsyncable-cross-feature-bridging-via-api-contracts.md}
 * for the full reasoning on why this needed its own ADR beyond ADR-0001/0002.
 *
 * <p>Implementations should be cheap to construct and call repeatedly (once
 * per reconciliation checkpoint -- client startup and shutdown, never
 * per-tick); {@link #exportState()}/{@link #importState(byte[])} are expected
 * to simply (de)serialize whatever local config file the implementing
 * feature already owns, not to perform any Cloud I/O themselves.
 *
 * <p>Usage example (a platform composition root bridging a feature's config
 * into this contract, per ADR-0003):
 * <pre>{@code
 * CloudSyncable adapter = new CloudSyncable() {
 *     public String cloudSyncId() {
 *         return "hello-world-main-menu";
 *     }
 *     public byte[] exportState() {
 *         return configIO.serialize(configIO.load(configPath).config())
 *                 .getBytes(StandardCharsets.UTF_8);
 *     }
 *     public void importState(byte[] data) {
 *         HelloWorldMainMenuConfigIO.ParseResult result =
 *                 configIO.parse(new String(data, StandardCharsets.UTF_8));
 *         Files.writeString(configPath, configIO.serialize(result.config()));
 *     }
 *     public long localLastModifiedMillis() {
 *         try {
 *             return Files.exists(configPath) ? Files.getLastModifiedTime(configPath).toMillis() : -1L;
 *         } catch (IOException e) {
 *             return -1L;
 *         }
 *     }
 * };
 * }</pre>
 */
public interface CloudSyncable {

    /**
     * @return a stable, unique identifier for this syncable's data, also used
     *         to derive its Cloud file name; must never change once shipped
     *         (changing it orphans any previously-synced Cloud copy)
     */
    String cloudSyncId();

    /**
     * @return the current state, serialized to bytes (typically UTF-8 JSON
     *         text produced by the implementing feature's own config I/O
     *         class); never {@code null}
     */
    byte[] exportState();

    /**
     * Applies a previously-exported state (either this device's own earlier
     * export, or one pulled from Steam Cloud) back onto the implementing
     * feature's local config.
     *
     * @param data previously-exported bytes, in the same shape
     *             {@link #exportState()} produces; implementations should
     *             fail closed (ignore, log, keep prior state) rather than
     *             throw on malformed input
     */
    void importState(byte[] data);

    /**
     * Reports when the local state this syncable exports was last modified,
     * so {@code features/steam-cloud-sync} can apply the same FR0.4
     * "closest we can get to last-write-wins" reconciliation rule it already
     * applies to Groups 3-5's plain local files -- comparing this value
     * directly against the Cloud copy's own last-write timestamp, without
     * this contract ever needing to expose a {@link java.nio.file.Path} or
     * any other implementation detail of the feature it bridges.
     *
     * @return the epoch-millisecond last-modified time of the local state
     *         {@link #exportState()} would currently produce; a negative
     *         value (e.g. {@code -1L}) if no local state exists yet on this
     *         device, so any existing Cloud copy is always treated as newer
     */
    long localLastModifiedMillis();
}
