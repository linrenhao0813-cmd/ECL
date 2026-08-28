package com.ecl.game;

import com.ecl.util.GsonProvider;
import com.ecl.util.FileLockLease;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.stream.Stream;
import java.util.function.Predicate;

/** Scans Minecraft saves and persists the editable single-player world settings. */
public final class WorldSaveService {
    public static final String SETTINGS_FILE = ".ecl/world-settings.json";
    private final Predicate<String> instanceRunning;

    public WorldSaveService() {
        this(ignored -> false);
    }

    /** Creates a service that refuses writes while the owning instance is running. */
    public WorldSaveService(Predicate<String> instanceRunning) {
        this.instanceRunning = Objects.requireNonNull(instanceRunning, "instanceRunning");
    }

    public List<WorldSave> scan(DefaultGameRepository repository) {
        Objects.requireNonNull(repository, "repository");
        Map<Path, WorldSave> unique = new LinkedHashMap<>();
        for (String instanceId : repository.installedVersions()) {
            try {
                VersionMetadata metadata = repository.resolve(instanceId);
                Path runDirectory = repository.runDirectory(instanceId);
                boolean sharedDirectory = runDirectory.toAbsolutePath().normalize()
                        .equals(repository.sharedGameDirectory().toAbsolutePath().normalize());
                scanDirectory(unique, runDirectory, instanceId, metadata, sharedDirectory);
            } catch (IOException | RuntimeException ignored) {
                // A broken profile should not prevent healthy instances from appearing.
            }
        }
        return unique.values().stream()
                .sorted(Comparator.comparing(WorldSave::minecraftVersion)
                        .thenComparing(WorldSave::loaderLabel)
                        .thenComparing(WorldSave::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public WorldSave update(WorldSave save, WorldSaveSettings settings) throws IOException {
        Objects.requireNonNull(save, "save");
        Objects.requireNonNull(settings, "settings");
        ensureNotRunning(save);
        try (FileLockLease ignored = FileLockLease.tryAcquire(gameLockFile(save))) {
            if (ignored == null) {
                throw new IOException("游戏正在运行，不能修改世界存档: " + save.name());
            }
            ensureNotRunning(save);
            Path levelDat = save.directory().resolve("level.dat");
            if (!Files.isRegularFile(levelDat)) {
                throw new IOException("World is missing level.dat: " + save.name());
            }
            try {
                NbtIo.Compound root = NbtIo.read(levelDat);
                NbtIo.Compound data = root.compound("Data");
                if (data == null) data = root;
                data.put("GameType", new NbtIo.IntValue(settings.gameMode().id()));
                data.put("Difficulty", new NbtIo.ByteValue((byte) settings.difficulty().id()));
                data.put("allowCommands", new NbtIo.ByteValue((byte) (settings.allowCommands() ? 1 : 0)));
                ensureNotRunning(save);
                writeAtomically(levelDat, root);
            } catch (IOException | RuntimeException failure) {
                throw new IOException("Unable to edit level.dat for world " + save.name(), failure);
            }
            ensureNotRunning(save);
            writeSidecar(save.directory(), settings);
            return new WorldSave(save.name(), save.directory(), save.instanceId(), save.minecraftVersion(),
                    save.modLoader(), save.modLoaderVersion(), Files.getLastModifiedTime(save.directory())
                            .toMillis(), settings, save.sharedDirectory());
        }
    }

    private static Path gameLockFile(WorldSave save) throws IOException {
        Path saves = save.directory().toAbsolutePath().normalize().getParent();
        Path runDirectory = saves == null ? null : saves.getParent();
        if (runDirectory == null) {
            throw new IOException("World directory has no game root: " + save.directory());
        }
        return runDirectory.resolve(".ecl").resolve("operation.lock");
    }

    private void ensureNotRunning(WorldSave save) throws IOException {
        if (instanceRunning.test(save.instanceId())) {
            throw new IOException("实例正在运行，不能修改世界存档: " + save.instanceId());
        }
    }

    private void scanDirectory(Map<Path, WorldSave> unique, Path runDirectory, String instanceId,
                               VersionMetadata metadata, boolean sharedDirectory) {
        Path saves = runDirectory.toAbsolutePath().normalize().resolve("saves");
        if (!Files.isDirectory(saves)) return;
        try (Stream<Path> entries = Files.list(saves)) {
            entries.filter(Files::isDirectory).forEach(directory -> {
                if (!Files.isRegularFile(directory.resolve("level.dat"))) return;
                try {
                    WorldSaveSettings settings = readSettings(directory);
                    BasicFileAttributes attributes = Files.readAttributes(directory,
                            BasicFileAttributes.class);
                    WorldSave candidate = new WorldSave(directory.getFileName().toString(), directory,
                            instanceId, metadata.minecraftVersion(), metadata.modLoader(),
                            metadata.modLoaderVersion(), attributes.lastModifiedTime().toMillis(), settings,
                            sharedDirectory);
                    unique.putIfAbsent(directory.toAbsolutePath().normalize(), candidate);
                } catch (IOException ignored) {
                    // Ignore a world that disappears while the directory is being scanned.
                }
            });
        } catch (IOException ignored) {
            // Ignore inaccessible run directories.
        }
    }

    private WorldSaveSettings readSettings(Path world) throws IOException {
        WorldSaveSettings defaults = WorldSaveSettings.defaults();
        Path levelDat = world.resolve("level.dat");
        try {
            NbtIo.Compound root = NbtIo.read(levelDat);
            NbtIo.Compound data = root.compound("Data");
            if (data == null) data = root;
            int gameType = data.intValue("GameType", defaults.gameMode().id());
            int difficulty = data.intValue("Difficulty", defaults.difficulty().id());
            boolean commands = data.intValue("allowCommands", defaults.allowCommands() ? 1 : 0) != 0;
            defaults = new WorldSaveSettings(WorldSaveSettings.Difficulty.fromId(difficulty),
                    WorldSaveSettings.GameMode.fromId(gameType), commands, false);
        } catch (IOException | RuntimeException ignored) {
            // Fall back to sidecar/defaults for old or incomplete worlds.
        }
        Path sidecar = world.resolve(SETTINGS_FILE);
        if (!Files.isRegularFile(sidecar)) return defaults;
        try (var reader = Files.newBufferedReader(sidecar, StandardCharsets.UTF_8)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            int lanPort = defaults.lanPort();
            if (json.has("lanPort") && !json.get("lanPort").isJsonNull()) {
                try {
                    int storedPort = json.get("lanPort").getAsInt();
                    if (storedPort >= 1 && storedPort <= 65535) lanPort = storedPort;
                } catch (RuntimeException ignored) {
                    // Keep the default port when the sidecar contains an invalid value.
                }
            }
            return new WorldSaveSettings(defaults.difficulty(), defaults.gameMode(),
                    defaults.allowCommands(), json.has("openToLan")
                    && json.get("openToLan").getAsBoolean(), lanPort);
        } catch (RuntimeException invalidJson) {
            return defaults;
        }
    }

    private void writeSidecar(Path world, WorldSaveSettings settings) throws IOException {
        Path file = world.resolve(SETTINGS_FILE);
        Files.createDirectories(file.getParent());
        JsonObject json = new JsonObject();
        json.addProperty("openToLan", settings.openToLan());
        json.addProperty("lanPort", settings.lanPort());
        Path temporary = Files.createTempFile(file.getParent(), "world-settings-", ".tmp");
        try {
            try (var writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                GsonProvider.pretty().toJson(json, writer);
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void writeAtomically(Path target, NbtIo.Compound root) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), "level-", ".dat.tmp");
        try {
            NbtIo.write(temporary, root);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** Minimal complete NBT reader/writer used to preserve level.dat fields while editing three values. */
    static final class NbtIo {
        private static final int END = 0, BYTE = 1, SHORT = 2, INT = 3, LONG = 4,
                FLOAT = 5, DOUBLE = 6, BYTE_ARRAY = 7, STRING = 8, LIST = 9,
                COMPOUND = 10, INT_ARRAY = 11, LONG_ARRAY = 12;

        private NbtIo() { }

        static Compound read(Path file) throws IOException {
            try (InputStream input = new GZIPInputStream(Files.newInputStream(file));
                 DataInputStream data = new DataInputStream(input)) {
                int type = data.readUnsignedByte();
                if (type != COMPOUND) throw new IOException("level.dat root is not a compound");
                readString(data);
                return (Compound) readPayload(data, type);
            }
        }

        static void write(Path file, Compound root) throws IOException {
            try (OutputStream output = new GZIPOutputStream(Files.newOutputStream(file));
                 DataOutputStream data = new DataOutputStream(output)) {
                data.writeByte(COMPOUND);
                writeString(data, "");
                writePayload(data, root);
            }
        }

        private static Value readPayload(DataInputStream data, int type) throws IOException {
            return switch (type) {
                case BYTE -> new ByteValue(data.readByte());
                case SHORT -> new ShortValue(data.readShort());
                case INT -> new IntValue(data.readInt());
                case LONG -> new LongValue(data.readLong());
                case FLOAT -> new FloatValue(data.readFloat());
                case DOUBLE -> new DoubleValue(data.readDouble());
                case BYTE_ARRAY -> new ByteArrayValue(data.readNBytes(data.readInt()));
                case STRING -> new StringValue(readString(data));
                case LIST -> {
                    int childType = data.readUnsignedByte();
                    int size = data.readInt();
                    List<Value> values = new ArrayList<>(Math.max(0, size));
                    for (int i = 0; i < size; i++) values.add(readPayload(data, childType));
                    yield new ListValue(childType, values);
                }
                case COMPOUND -> {
                    Compound compound = new Compound();
                    while (true) {
                        int childType = data.readUnsignedByte();
                        if (childType == END) break;
                        compound.put(readString(data), readPayload(data, childType));
                    }
                    yield compound;
                }
                case INT_ARRAY -> {
                    int[] values = new int[data.readInt()];
                    for (int i = 0; i < values.length; i++) values[i] = data.readInt();
                    yield new IntArrayValue(values);
                }
                case LONG_ARRAY -> {
                    long[] values = new long[data.readInt()];
                    for (int i = 0; i < values.length; i++) values[i] = data.readLong();
                    yield new LongArrayValue(values);
                }
                default -> throw new IOException("Unknown NBT tag: " + type);
            };
        }

        private static void writePayload(DataOutputStream data, Value value) throws IOException {
            if (value instanceof Compound compound) {
                for (Map.Entry<String, Value> entry : compound.values.entrySet()) {
                    data.writeByte(typeOf(entry.getValue()));
                    writeString(data, entry.getKey());
                    writePayload(data, entry.getValue());
                }
                data.writeByte(END);
            } else if (value instanceof ByteValue v) data.writeByte(v.value());
            else if (value instanceof ShortValue v) data.writeShort(v.value());
            else if (value instanceof IntValue v) data.writeInt(v.value());
            else if (value instanceof LongValue v) data.writeLong(v.value());
            else if (value instanceof FloatValue v) data.writeFloat(v.value());
            else if (value instanceof DoubleValue v) data.writeDouble(v.value());
            else if (value instanceof ByteArrayValue v) { data.writeInt(v.value().length); data.write(v.value()); }
            else if (value instanceof StringValue v) writeString(data, v.value());
            else if (value instanceof ListValue v) {
                data.writeByte(v.elementType()); data.writeInt(v.values().size());
                for (Value child : v.values()) writePayload(data, child);
            } else if (value instanceof IntArrayValue v) {
                data.writeInt(v.value().length); for (int child : v.value()) data.writeInt(child);
            } else if (value instanceof LongArrayValue v) {
                data.writeInt(v.value().length); for (long child : v.value()) data.writeLong(child);
            }
        }

        private static int typeOf(Value value) {
            if (value instanceof ByteValue) return BYTE; if (value instanceof ShortValue) return SHORT;
            if (value instanceof IntValue) return INT; if (value instanceof LongValue) return LONG;
            if (value instanceof FloatValue) return FLOAT; if (value instanceof DoubleValue) return DOUBLE;
            if (value instanceof ByteArrayValue) return BYTE_ARRAY; if (value instanceof StringValue) return STRING;
            if (value instanceof ListValue) return LIST; if (value instanceof Compound) return COMPOUND;
            if (value instanceof IntArrayValue) return INT_ARRAY; if (value instanceof LongArrayValue) return LONG_ARRAY;
            throw new IllegalArgumentException("Unsupported NBT value: " + value);
        }

        private static String readString(DataInputStream data) throws IOException {
            int length = data.readUnsignedShort();
            byte[] bytes = new byte[length];
            try {
                data.readFully(bytes);
            } catch (EOFException truncated) {
                throw new IOException("Truncated NBT string", truncated);
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }

        private static void writeString(DataOutputStream data, String value) throws IOException {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > 65535) {
                throw new IOException("NBT string exceeds 65535 bytes");
            }
            data.writeShort(bytes.length);
            data.write(bytes);
        }

        sealed interface Value permits ByteValue, ShortValue, IntValue, LongValue, FloatValue,
                DoubleValue, ByteArrayValue, StringValue, ListValue, Compound, IntArrayValue, LongArrayValue { }
        record ByteValue(byte value) implements Value { }
        record ShortValue(short value) implements Value { }
        record IntValue(int value) implements Value { }
        record LongValue(long value) implements Value { }
        record FloatValue(float value) implements Value { }
        record DoubleValue(double value) implements Value { }
        record ByteArrayValue(byte[] value) implements Value { }
        record StringValue(String value) implements Value { }
        record ListValue(int elementType, List<Value> values) implements Value { }
        record IntArrayValue(int[] value) implements Value { }
        record LongArrayValue(long[] value) implements Value { }

        static final class Compound implements Value {
            private final Map<String, Value> values = new LinkedHashMap<>();
            void put(String key, Value value) { values.put(key, value); }
            Compound compound(String key) {
                Value value = values.get(key);
                return value instanceof Compound compound ? compound : null;
            }
            int intValue(String key, int fallback) {
                Value value = values.get(key);
                if (value instanceof IntValue v) return v.value();
                if (value instanceof ByteValue v) return v.value();
                if (value instanceof ShortValue v) return v.value();
                return fallback;
            }
            String stringValue(String key, String fallback) {
                Value value = values.get(key);
                return value instanceof StringValue v ? v.value() : fallback;
            }
        }
    }
}
