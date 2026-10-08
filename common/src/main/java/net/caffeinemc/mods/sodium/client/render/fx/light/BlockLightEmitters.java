package net.caffeinemc.mods.sodium.client.render.fx.light;

import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Tracks the nearest light-emitting blocks so the BlockLightTest pass can light them locally.
 *
 * <p>Ported from Luxium's {@code BlockLightTest}. The selection rule is unchanged: light sources
 * are collected per chunk, and each frame the twelve closest to the camera win, because that is
 * how many the shader evaluates. Anything beyond thirty-six blocks cannot reach the shader's
 * falloff anyway, so it is dropped during selection rather than uploaded.
 *
 * <p>Chunk scans are rate limited to one per tick. A full-height chunk is a quarter million block
 * reads if done exhaustively, and the radius walk means a chunk can always wait a few ticks
 * without any visible consequence.
 */
public final class BlockLightEmitters {
    /** Matches the loop bound in {@code shaders/include/blocklighttest.glsl}. */
    public static final int MAX_EMITTERS = 12;

    /** Luxium's cutoff, squared: 36 blocks. */
    private static final double MAX_DISTANCE_SQ = 1296.0;

    private static final Map<Long, Emitter> SOURCES = new HashMap<>();
    private static final Map<Long, Set<Long>> CHUNK_MEMBERS = new HashMap<>();
    private static final Set<Long> SCANNED_CHUNKS = new HashSet<>();

    private static final Emitter[] NEAREST = new Emitter[MAX_EMITTERS];
    private static final float[] DISTANCES = new float[MAX_EMITTERS];

    /** Number of valid entries in {@link #NEAREST} for the current frame. */
    private static int size;

    @Nullable
    private static ClientLevel level;

    private BlockLightEmitters() {
    }

    public static int size() {
        return size;
    }

    public static Emitter nearest(int index) {
        return NEAREST[index];
    }

    /**
     * Packs the current nearest emitters into the std140 arrays the terrain shader reads.
     *
     * <p>Emitters are transformed into view space here rather than in the shader because the
     * receiver position arrives as an already-transformed varying, and both sides of the diffuse
     * and attenuation terms have to agree on the space they are measured in.
     *
     * @param emitterOut twelve xyz/w entries; w holds the normalized emission, signed
     * @param colorOut   twelve rgb/a entries
     * @param modelView  world-to-view transform for this frame
     * @return number of entries written, always &lt;= {@link #MAX_EMITTERS}
     */
    public static int pack(float[] emitterOut, float[] colorOut, Matrix4fc modelView) {
        int count = Math.min(size, MAX_EMITTERS);
        Vector4f view = new Vector4f();

        for (int i = 0; i < count; i++) {
            Emitter emitter = NEAREST[i];
            int base = i * 4;

            view.set((float) emitter.x(), (float) emitter.y(), (float) emitter.z(), 1.0f).mul(modelView);

            emitterOut[base] = view.x();
            emitterOut[base + 1] = view.y();
            emitterOut[base + 2] = view.z();

            // abs(w) is the emission normalized to 0..1. The sign carries whether the light is
            // held: a carried light has no flood fill behind it, so the shader skips the
            // spread-visibility test for it instead of letting the block-light guide hide it.
            float emission = emitter.level() / 15.0f;
            emitterOut[base + 3] = emitter.held() ? -emission : emission;

            colorOut[base] = emitter.tint()[0];
            colorOut[base + 1] = emitter.tint()[1];
            colorOut[base + 2] = emitter.tint()[2];
            colorOut[base + 3] = 0.0f;
        }

        return count;
    }

    /** Drops every cached emitter. Called when the feature is switched off. */
    public static void clear() {
        SOURCES.clear();
        CHUNK_MEMBERS.clear();
        SCANNED_CHUNKS.clear();
        level = null;

        for (int i = 0; i < MAX_EMITTERS; i++) {
            NEAREST[i] = null;
            DISTANCES[i] = Float.POSITIVE_INFINITY;
        }

        size = 0;
    }

    /** Called when a chunk becomes available. Mirrors Luxium's {@code onChunk}. */
    public static void onChunkLoaded(ClientLevel current, LevelChunk chunk) {
        setLevel(current);

        int chunkX = chunk.getPos().x();
        int chunkZ = chunk.getPos().z();

        // A rescan replaces whatever the previous contents contributed.
        removeChunk(chunkX, chunkZ);

        long chunkKey = ChunkPos.pack(chunkX, chunkZ);
        SCANNED_CHUNKS.add(chunkKey);

        Set<Long> members = new HashSet<>();
        LevelChunkSection[] sections = chunk.getSections();

        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            LevelChunkSection section = sections[sectionIndex];

            // Empty sections are the overwhelming majority, and skipping them is what keeps a
            // full-height chunk cheap enough to scan on the client thread.
            if (section == null || section.hasOnlyAir()) {
                continue;
            }

            int sectionY = chunk.getMinY() + (sectionIndex << 4);

            for (int localY = 0; localY < 16; localY++) {
                for (int localZ = 0; localZ < 16; localZ++) {
                    for (int localX = 0; localX < 16; localX++) {
                        BlockState state = section.getBlockState(localX, localY, localZ);

                        if (state == null) {
                            continue;
                        }

                        int emission = state.getLightEmission();

                        if (emission <= 0) {
                            continue;
                        }

                        long key = BlockPos.asLong(chunkX * 16 + localX, sectionY + localY, chunkZ * 16 + localZ);
                        SOURCES.put(key, create(key, state, emission));
                        members.add(key);
                    }
                }
            }
        }

        CHUNK_MEMBERS.put(chunkKey, members);
    }

    /** Called when a chunk unloads. */
    public static void onChunkUnloaded(int chunkX, int chunkZ) {
        removeChunk(chunkX, chunkZ);
    }

    /** Called when a block changes, so a placed or broken light does not linger. */
    public static void onBlockChanged(ClientLevel current, BlockPos pos, BlockState state) {
        setLevel(current);

        long key = pos.asLong();
        int emission = state.getLightEmission();

        long chunkKey = ChunkPos.pack(pos);
        Set<Long> members = CHUNK_MEMBERS.computeIfAbsent(chunkKey, ignored -> new HashSet<>());

        if (emission <= 0) {
            SOURCES.remove(key);
            members.remove(key);
            return;
        }

        SOURCES.put(key, create(key, state, emission));
        members.add(key);
        SCANNED_CHUNKS.add(chunkKey);
    }

    /**
     * Refreshes the nearest-emitter set for this frame.
     *
     * @param camera the active camera, used for both the scan origin and the held-item light
     */
    public static void tick(ClientLevel current, Camera camera, ItemStack mainHand) {
        setLevel(current);

        double camX = camera.position().x;
        double camY = camera.position().y;
        double camZ = camera.position().z;

        size = 0;

        for (int i = 0; i < MAX_EMITTERS; i++) {
            NEAREST[i] = null;
            DISTANCES[i] = Float.POSITIVE_INFINITY;
        }

        for (Emitter emitter : SOURCES.values()) {
            addNearby(emitter, camX, camY, camZ);
        }

        // A light source in the player's hand has no flood fill to compete with, so it is added
        // directly and flagged as carried for the shader.
        if (mainHand != null && mainHand.getItem() instanceof BlockItem blockItem) {
            BlockState held = blockItem.getBlock().defaultBlockState();
            int emission = held.getLightEmission();

            if (emission > 0) {
                addNearby(new Emitter(camX, camY - 0.15, camZ, emission,
                        tint(blockItem.getBlock()), true), camX, camY, camZ);
            }
        }
    }

    /**
     * Scans up to one not-yet-seen chunk per tick, walking outward from the camera.
     *
     * <p>Without this a chunk load would pay for a full section walk inline, and a fresh world
     * would pay it several times over as it streams in.
     */
    public static void scanNearbyChunks(ClientLevel current, Camera camera) {
        setLevel(current);

        if (current.getChunkSource() == null) {
            return;
        }

        double camX = camera.position().x;
        double camZ = camera.position().z;

        ChunkPos center = new ChunkPos(Mth.floor(camX) >> 4, Mth.floor(camZ) >> 4);

        for (int radius = 0; radius <= 3; radius++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    // Only the outer ring of a larger radius is new work; the inner chunks were
                    // covered by the previous radius and have already been scanned.
                    if (radius > 0 && Math.abs(dx) != radius && Math.abs(dz) != radius) {
                        continue;
                    }

                    int chunkX = center.x() + dx;
                    int chunkZ = center.z() + dz;

                    if (SCANNED_CHUNKS.contains(ChunkPos.pack(chunkX, chunkZ))) {
                        continue;
                    }

                    LevelChunk chunk = current.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);

                    if (chunk != null) {
                        onChunkLoaded(current, chunk);
                        return;
                    }
                }
            }
        }
    }

    private static void addNearby(Emitter emitter, double camX, double camY, double camZ) {
        double dx = emitter.x - camX;
        double dy = emitter.y - camY;
        double dz = emitter.z - camZ;
        double distanceSq = dx * dx + dy * dy + dz * dz;

        if (distanceSq > MAX_DISTANCE_SQ) {
            return;
        }

        // Sorted by ascending distance, so this walks past every closer emitter already placed.
        int insert = 0;
        while (insert < size && distanceSq >= DISTANCES[insert]) {
            insert++;
        }

        if (insert >= MAX_EMITTERS) {
            return;
        }

        int last = Math.min(size, MAX_EMITTERS - 1);

        for (int i = last; i > insert; i--) {
            NEAREST[i] = NEAREST[i - 1];
            DISTANCES[i] = DISTANCES[i - 1];
        }

        NEAREST[insert] = emitter;
        DISTANCES[insert] = (float) distanceSq;
        size = Math.min(size + 1, MAX_EMITTERS);
    }

    private static void setLevel(ClientLevel next) {
        if (level == next) {
            return;
        }

        level = next;

        // A level swap invalidates every cached block position.
        SOURCES.clear();
        CHUNK_MEMBERS.clear();
        SCANNED_CHUNKS.clear();
        size = 0;
    }

    private static void removeChunk(int chunkX, int chunkZ) {
        long chunkKey = ChunkPos.pack(chunkX, chunkZ);
        SCANNED_CHUNKS.remove(chunkKey);

        Set<Long> members = CHUNK_MEMBERS.remove(chunkKey);

        if (members != null) {
            for (long key : members) {
                SOURCES.remove(key);
            }
        }
    }

    private static Emitter create(long packedPos, BlockState state, int emission) {
        BlockPos pos = BlockPos.of(packedPos);

        return new Emitter(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                Math.min(emission, 15), tint(state.getBlock()), false);
    }

    /**
     * Approximates a block's light colour from its registry path.
     *
     * <p>Luxium does the same substring matching rather than reading a real colour property, so
     * this keeps its palette exactly. The named cases cover the blocks whose vanilla light colour
     * differs noticeably from warm white; everything unlisted falls through to the warm default.
     */
    private static float[] tint(net.minecraft.world.level.block.Block block) {
        var id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block);
        return tintForName(id == null ? "" : id.getPath());
    }

    private static float[] tintForName(String name) {
        if (name.contains("soul")) {
            return TINT_SOUL;
        } else if (name.contains("redstone")) {
            return TINT_REDSTONE;
        } else if (!name.contains("sea_lantern") && !name.contains("conduit")) {
            if (!name.contains("end_rod") && !name.contains("pearlescent_froglight")) {
                if (name.contains("verdant_froglight")) {
                    return TINT_VERDANT;
                } else if (!name.contains("glowstone") && !name.contains("ochre_froglight") && !name.contains("shroomlight")) {
                    if (!name.contains("sculk") && !name.contains("respawn_anchor")) {
                        if (name.contains("amethyst")) {
                            return TINT_AMETHYST;
                        } else if (!name.contains("glow_lichen") && !name.contains("glow_berries")) {
                            return name.contains("lava") || name.contains("torch") || name.contains("lantern")
                                    || name.contains("fire") || name.contains("candle")
                                    ? TINT_FLAME : TINT_WARM;
                        } else {
                            return TINT_GLOW_BERRY;
                        }
                    } else {
                        return TINT_SCULK;
                    }
                } else {
                    return TINT_SHROOMLIGHT;
                }
            } else {
                return TINT_END_ROD;
            }
        } else {
            return TINT_SEA_LANTERN;
        }
    }

    // Palette values are taken verbatim from Luxium's BlockLightTest#tint. Note that several of
    // them are deliberately "wrong" against vanilla light colours; matching Luxium matters more
    // than matching vanilla here, since the goal is the same look.
    private static final float[] TINT_SOUL = {0.28f, 0.78f, 1.00f};
    private static final float[] TINT_REDSTONE = {1.00f, 0.20f, 0.08f};
    private static final float[] TINT_VERDANT = {0.68f, 1.00f, 0.66f};
    private static final float[] TINT_AMETHYST = {0.78f, 0.48f, 1.00f};
    private static final float[] TINT_WARM = {1.00f, 0.76f, 0.48f};
    private static final float[] TINT_FLAME = {1.00f, 0.58f, 0.26f};
    private static final float[] TINT_GLOW_BERRY = {0.72f, 1.00f, 0.58f};
    private static final float[] TINT_SCULK = {0.20f, 0.78f, 0.92f};
    private static final float[] TINT_SHROOMLIGHT = {1.00f, 0.82f, 0.48f};
    private static final float[] TINT_END_ROD = {1.00f, 0.72f, 0.95f};
    private static final float[] TINT_SEA_LANTERN = {0.58f, 0.95f, 1.00f};

    /**
     * A light source.
     *
     * @param level emission strength, 0..15
     * @param held  true when carried in the player's hand
     */
    public record Emitter(double x, double y, double z, int level, float[] tint, boolean held) {
    }
}