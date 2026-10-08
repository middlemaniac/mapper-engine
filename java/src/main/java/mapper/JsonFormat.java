package mapper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.Map;

public final class JsonFormat {
    private static final ObjectMapper JSON = new ObjectMapper();

    private JsonFormat() {
    }

    public static Object read(Path path) throws Exception {
        return JSON.readValue(path.toFile(), Object.class);
    }

    public static Map<String, Object> readMapping(Path path) throws Exception {
        return JSON.readValue(path.toFile(), new TypeReference<Map<String, Object>>() { });
    }

    public static String write(Object data, Map<String, Object> mapping) throws Exception {
        return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(data) + "\n";
    }
}
