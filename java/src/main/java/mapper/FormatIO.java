package mapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class FormatIO {
    @FunctionalInterface
    public interface Reader {
        Object read(Path path) throws Exception;
    }

    @FunctionalInterface
    public interface Writer {
        String write(Object data, Map<String, Object> mapping) throws Exception;
    }

    private static final class Codec {
        private final Reader reader;
        private final Writer writer;

        private Codec(Reader reader, Writer writer) {
            this.reader = reader;
            this.writer = writer;
        }
    }

    private static final Map<String, Codec> CODECS = new LinkedHashMap<>();

    static {
        registerFormat("json", JsonFormat::read, JsonFormat::write);
        registerFormat("xml", XmlFormat::read, XmlFormat::write);
    }

    private FormatIO() {
    }

    public static void registerFormat(String format, Reader reader, Writer writer) {
        if (format == null || format.trim().isEmpty()) {
            throw new IllegalArgumentException("Format name must be a non-empty string");
        }
        if (reader == null || writer == null) {
            throw new IllegalArgumentException("Format reader and writer must be provided");
        }
        CODECS.put(normalize(format), new Codec(reader, writer));
    }

    public static Object readData(Path path, String format) throws Exception {
        String selected = format == null || format.isEmpty() ? formatForPath(path) : normalize(format);
        Codec codec = CODECS.get(selected);
        if (codec == null) {
            throw new IllegalArgumentException("Unsupported input format: " + selected);
        }
        return codec.reader.read(path);
    }

    public static String writeData(
            Object data, Path path, String format, Map<String, Object> mapping) throws Exception {
        String selected = format == null || format.isEmpty()
                ? path == null ? "json" : formatForPath(path)
                : normalize(format);
        Codec codec = CODECS.get(selected);
        if (codec == null) {
            throw new IllegalArgumentException("Unsupported output format: " + selected);
        }
        String rendered = codec.writer.write(data, mapping);
        if (path != null) {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(path, rendered.getBytes(StandardCharsets.UTF_8));
        }
        return rendered;
    }

    private static String formatForPath(Path path) {
        Path filename = path.getFileName();
        String name = filename == null ? "" : filename.toString();
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) {
            throw new IllegalArgumentException(
                    "Format must be specified for paths without an extension");
        }
        return normalize(name.substring(dot + 1));
    }

    private static String normalize(String format) {
        String normalized = format.toLowerCase(Locale.ROOT);
        return normalized.startsWith(".") ? normalized.substring(1) : normalized;
    }
}
