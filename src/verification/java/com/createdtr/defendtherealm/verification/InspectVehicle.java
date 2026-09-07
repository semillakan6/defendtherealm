package com.createdtr.defendtherealm.verification;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import net.minecraft.nbt.*;

/** Read-only Anvil inspection; does not open a Minecraft world or acquire its save lock. */
public final class InspectVehicle {
    public static void main(String[] args) throws Exception {
        if (args.length != 7) throw new IllegalArgumentException("world minX minY minZ maxX maxY maxZ");
        Path world = Path.of(args[0]);
        int x0 = Integer.parseInt(args[1]), y0 = Integer.parseInt(args[2]), z0 = Integer.parseInt(args[3]);
        int x1 = Integer.parseInt(args[4]), y1 = Integer.parseInt(args[5]), z1 = Integer.parseInt(args[6]);
        if (x1 < x0 || y1 < y0 || z1 < z0 || (long)x1-x0 > 64 || (long)y1-y0 > 64 || (long)z1-z0 > 64) {
            throw new IllegalArgumentException("Ordered bounds of at most 65 cells per axis required");
        }
        Map<String, Integer> counts = new TreeMap<>();
        for (int cx = Math.floorDiv(x0, 16); cx <= Math.floorDiv(x1, 16); cx++) {
            for (int cz = Math.floorDiv(z0, 16); cz <= Math.floorDiv(z1, 16); cz++) {
                CompoundTag chunk = readChunk(world.resolve("region"), cx, cz);
                CompoundTag entities = readChunk(world.resolve("entities"), cx, cz);
                if (entities != null) {
                    for (Tag value : entities.getList("Entities", Tag.TAG_COMPOUND)) {
                        CompoundTag entity = (CompoundTag)value;
                        ListTag pos = entity.getList("Pos", Tag.TAG_DOUBLE);
                        if (pos.size() == 3 && pos.getDouble(0) >= x0 && pos.getDouble(0) <= x1 + 1
                                && pos.getDouble(1) >= y0 && pos.getDouble(1) <= y1 + 1
                                && pos.getDouble(2) >= z0 && pos.getDouble(2) <= z1 + 1) {
                            System.out.println("ENTITY " + entity);
                        }
                    }
                }
                if (chunk == null) continue;
                for (Tag value : chunk.getList("sections", Tag.TAG_COMPOUND)) {
                    CompoundTag section = (CompoundTag)value;
                    int sy = section.getByte("Y") * 16;
                    if (sy > y1 || sy + 15 < y0) continue;
                    CompoundTag states = section.getCompound("block_states");
                    ListTag palette = states.getList("palette", Tag.TAG_COMPOUND);
                    if (palette.isEmpty()) continue;
                    long[] packed = states.getLongArray("data");
                    int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
                    int perLong = 64 / bits;
                    for (int y = Math.max(y0, sy); y <= Math.min(y1, sy + 15); y++) {
                        for (int z = Math.max(z0, cz * 16); z <= Math.min(z1, cz * 16 + 15); z++) {
                            for (int x = Math.max(x0, cx * 16); x <= Math.min(x1, cx * 16 + 15); x++) {
                                int index = ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
                                int p = palette.size() == 1 ? 0 : (int)((packed[index / perLong]
                                        >>> ((index % perLong) * bits)) & ((1L << bits) - 1));
                                CompoundTag state = palette.getCompound(p);
                                String name = state.getString("Name");
                                if (name.equals("minecraft:air")) continue;
                                counts.merge(name, 1, Integer::sum);
                                System.out.println(x + "," + y + "," + z + " " + state);
                            }
                        }
                    }
                }
                for (Tag value : chunk.getList("block_entities", Tag.TAG_COMPOUND)) {
                    CompoundTag be = (CompoundTag)value;
                    int x = be.getInt("x"), y = be.getInt("y"), z = be.getInt("z");
                    if (x >= x0 && x <= x1 && y >= y0 && y <= y1 && z >= z0 && z <= z1) {
                        System.out.println("BLOCK_ENTITY " + be);
                    }
                }
            }
        }
        System.out.println("BLOCK_COUNTS " + counts);
    }

    static CompoundTag readChunk(Path region, int cx, int cz) throws IOException {
        Path path = region.resolve("r." + Math.floorDiv(cx, 32) + "." + Math.floorDiv(cz, 32) + ".mca");
        if (!Files.exists(path)) return null;
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
            file.seek(4L * (Math.floorMod(cx, 32) + Math.floorMod(cz, 32) * 32));
            int location = file.readInt();
            if (location == 0) return null;
            file.seek((long)(location >>> 8) * 4096);
            int length = file.readInt();
            int compression = file.readUnsignedByte();
            if (length < 1 || length > (location & 255) * 4096 - 4) throw new IOException("Invalid chunk length");
            byte[] data = new byte[length - 1];
            file.readFully(data);
            InputStream raw = new ByteArrayInputStream(data);
            InputStream input = switch (compression) {
                case 1 -> new GZIPInputStream(raw);
                case 2 -> new InflaterInputStream(raw);
                case 3 -> raw;
                default -> throw new IOException("Unsupported compression: " + compression);
            };
            try (DataInputStream in = new DataInputStream(input)) {
                return NbtIo.read(in, NbtAccounter.create(32L * 1024 * 1024));
            }
        }
    }
}
