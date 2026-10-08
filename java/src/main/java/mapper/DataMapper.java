package mapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DataMapper {
    private DataMapper() {
    }

    public static Object transform(Object sourceData, Map<String, Object> mapping) {
        Object entriesValue = mapping.get("mappings");
        if (!(entriesValue instanceof List<?>)) {
            throw new IllegalArgumentException("Mapping file must contain a 'mappings' array");
        }
        if (mapping.containsKey("targetRoot") && !(mapping.get("targetRoot") instanceof String)) {
            throw new IllegalArgumentException("targetRoot must be a string");
        }
        if (mapping.containsKey("preserveTargetRoot")
                && !(mapping.get("preserveTargetRoot") instanceof Boolean)) {
            throw new IllegalArgumentException("preserveTargetRoot must be a boolean");
        }

        List<?> entries = (List<?>) entriesValue;
        String targetRoot = inferTargetRoot(mapping, entries);
        String sourceRoot = inferSourceRoot(mapping);
        boolean omitSourceRoot = sourceRoot != null
                && (!(sourceData instanceof Map<?, ?>)
                        || !((Map<?, ?>) sourceData).containsKey(sourceRoot));
        Map<String, Object> output = new LinkedHashMap<>();
        for (Object item : entries) {
            if (!(item instanceof Map<?, ?>)) {
                throw new IllegalArgumentException("Each mapping must be an object");
            }
            Map<?, ?> entry = (Map<?, ?>) item;
            Object sourcePathValue = entry.get("sourcePath");
            Object sourcePathsValue = entry.get("sourcePaths");
            Object targetPathValue = entry.get("targetPath");
            boolean hasSourcePath = sourcePathValue instanceof String
                    && !((String) sourcePathValue).isEmpty()
                    && validPath((String) sourcePathValue);
            boolean hasSourcePaths = sourcePathsValue instanceof List<?>
                    && validSourcePaths((List<?>) sourcePathsValue);
            if (entry.containsKey("sourcePath") == entry.containsKey("sourcePaths")
                    || (entry.containsKey("sourcePath") && !hasSourcePath)
                    || (entry.containsKey("sourcePaths") && !hasSourcePaths)
                    || !(targetPathValue instanceof String)
                    || !validPath((String) targetPathValue)) {
                throw new IllegalArgumentException(
                        "Each mapping requires sourcePath or sourcePaths and a valid targetPath");
            }

            String targetPath = (String) targetPathValue;
            Object value;
            if (hasSourcePaths) {
                Object separatorValue = entry.get("separator");
                if (entry.containsKey("separator") && !(separatorValue instanceof String)) {
                    throw new IllegalArgumentException("separator must be a string");
                }
                String separator = entry.containsKey("separator") ? (String) separatorValue : "";
                List<String> values = new ArrayList<>();
                for (Object path : (List<?>) sourcePathsValue) {
                    String[] parts = ((String) path).split("\\.");
                    if (omitSourceRoot && parts[0].equals(sourceRoot)) {
                        parts = slice(parts, 1, parts.length);
                    }
                    Object part = resolvePath(sourceData, parts, 0);
                    if (part instanceof Map<?, ?> || part instanceof List<?>) {
                        throw new IllegalArgumentException("sourcePaths can only combine scalar values");
                    }
                    values.add(String.valueOf(part));
                }
                value = String.join(separator, values);
            } else {
                String[] parts = ((String) sourcePathValue).split("\\.");
                if (omitSourceRoot && parts[0].equals(sourceRoot)) {
                    parts = slice(parts, 1, parts.length);
                }
                value = resolvePath(sourceData, parts, 0);
            }

            validatePath(targetPath, "targetPath");
            if (targetRoot != null) {
                String prefix = targetRoot + ".";
                if (!targetPath.startsWith(prefix)) {
                    throw new IllegalArgumentException(
                            "targetPath must start with targetRoot: " + targetPath);
                }
                targetPath = targetPath.substring(prefix.length());
            }
            if (targetPath.isEmpty()) {
                throw new IllegalArgumentException("targetPath must name an output property");
            }
            Object typeValue = entry.get("type");
            if (entry.containsKey("type") && !(typeValue instanceof String)) {
                throw new IllegalArgumentException("type must be a string");
            }
            setPath(output, targetPath,
                    convertValue(value, (String) typeValue, (String) targetPathValue));
        }
        return output;
    }

    private static String inferTargetRoot(Map<String, Object> mapping, List<?> entries) {
        Object configured = mapping.get("targetRoot");
        if (configured instanceof String && !((String) configured).isEmpty()) {
            return (String) configured;
        }
        if (Boolean.TRUE.equals(mapping.get("preserveTargetRoot"))) {
            return null;
        }
        String common = null;
        for (Object item : entries) {
            if (!(item instanceof Map<?, ?>)
                    || !(((Map<?, ?>) item).get("targetPath") instanceof String)) {
                return null;
            }
            String target = (String) ((Map<?, ?>) item).get("targetPath");
            if (!target.contains(".")) {
                return null;
            }
            String first = target.split("\\.", -1)[0];
            if (common == null) {
                common = first;
            } else if (!common.equals(first)) {
                return null;
            }
        }
        return common;
    }

    private static String inferSourceRoot(Map<String, Object> mapping) {
        Object entriesValue = mapping.get("mappings");
        if (!(entriesValue instanceof List<?>)) {
            return null;
        }
        List<String> paths = new ArrayList<>();
        for (Object item : (List<?>) entriesValue) {
            if (!(item instanceof Map<?, ?>)) {
                return null;
            }
            Map<?, ?> entry = (Map<?, ?>) item;
            if (entry.get("sourcePath") instanceof String) {
                paths.add((String) entry.get("sourcePath"));
            } else if (entry.get("sourcePaths") instanceof List<?>) {
                for (Object path : (List<?>) entry.get("sourcePaths")) {
                    if (!(path instanceof String)) {
                        return null;
                    }
                    paths.add((String) path);
                }
            } else {
                return null;
            }
        }
        if (paths.isEmpty()) {
            return null;
        }
        String common = null;
        for (String path : paths) {
            if (!path.contains(".")) {
                return null;
            }
            String first = path.split("\\.", -1)[0];
            if (common == null) {
                common = first;
            } else if (!common.equals(first)) {
                return null;
            }
        }
        return common != null && common.matches("\\d+") ? null : common;
    }

    private static Object resolvePath(Object value, String[] parts, int index) {
        if (index == parts.length) {
            return value;
        }
        if (value instanceof List<?>) {
            List<?> values = (List<?>) value;
            if (parts[index].matches("\\d+")) {
                int position = Integer.parseInt(parts[index]);
                if (position >= values.size()) {
                    throw new IllegalArgumentException("Array index out of range: " + parts[index]);
                }
                return resolvePath(values.get(position), parts, index + 1);
            }
            List<Object> result = new ArrayList<>();
            for (Object item : values) {
                result.add(resolvePath(item, parts, index));
            }
            return result;
        }
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("Path not found: "
                    + String.join(".", slice(parts, index + 1)));
        }
        Map<?, ?> object = (Map<?, ?>) value;
        if (!object.containsKey(parts[index])) {
            throw new IllegalArgumentException("Path not found: "
                    + String.join(".", slice(parts, index + 1)));
        }
        return resolvePath(object.get(parts[index]), parts, index + 1);
    }

    private static Object convertValue(Object value, String type, String targetPath) {
        if (value instanceof List<?>) {
            List<Object> result = new ArrayList<>();
            for (Object item : (List<?>) value) {
                result.add(convertValue(item, type, targetPath));
            }
            return result;
        }
        if (value instanceof Map<?, ?>) {
            if (type == null) {
                return value;
            }
            throw new IllegalArgumentException("A scalar type cannot be applied to an object");
        }
        String text = String.valueOf(value);
        if (type == null) {
            String leaf = targetPath.substring(targetPath.lastIndexOf('.') + 1);
            if ("id".equalsIgnoreCase(leaf) && text.trim().matches("[+-]?(?:0|[1-9]\\d*)")) {
                try {
                    return Long.parseLong(text.trim());
                } catch (NumberFormatException error) {
                    throw new IllegalArgumentException("Integer is out of range: " + text, error);
                }
            }
            return text;
        }
        if ("string".equals(type)) {
            return text;
        }
        switch (type) {
            case "integer":
                if (!text.trim().matches("[+-]?\\d+")) {
                    throw new IllegalArgumentException("Not an integer: " + text);
                }
                try {
                    return Long.parseLong(text.trim());
                } catch (NumberFormatException error) {
                    throw new IllegalArgumentException("Integer is out of range: " + text, error);
                }
            case "number":
                try {
                    double number = Double.parseDouble(text.trim());
                    if (!Double.isFinite(number)) {
                        throw new IllegalArgumentException("Not a finite number: " + text);
                    }
                    return number;
                } catch (NumberFormatException error) {
                    throw new IllegalArgumentException("Not a finite number: " + text, error);
                }
            case "boolean":
                if ("true".equalsIgnoreCase(text.trim())) {
                    return true;
                }
                if ("false".equalsIgnoreCase(text.trim())) {
                    return false;
                }
                throw new IllegalArgumentException("Not a boolean: " + text);
            default:
                throw new IllegalArgumentException("Unsupported mapping type: " + type);
        }
    }

    @SuppressWarnings("unchecked")
    private static void setPath(Map<String, Object> target, String path, Object value) {
        String[] parts = path.split("\\.");
        Map<String, Object> current = target;
        for (int i = 0; i < parts.length - 1; i++) {
            Object child = current.get(parts[i]);
            if (child == null) {
                child = new LinkedHashMap<String, Object>();
                current.put(parts[i], child);
            }
            if (!(child instanceof Map<?, ?>)) {
                throw new IllegalArgumentException("Target path conflicts at: " + parts[i]);
            }
            current = (Map<String, Object>) child;
        }
        String leaf = parts[parts.length - 1];
        if (current.containsKey(leaf)) {
            throw new IllegalArgumentException("Target path is mapped more than once: " + path);
        }
        current.put(leaf, value);
    }

    private static void validatePath(String path, String name) {
        if (path.isEmpty() || path.startsWith(".") || path.endsWith(".") || path.contains("..")) {
            throw new IllegalArgumentException(name + " must contain non-empty path segments");
        }
    }

    private static boolean validPath(String path) {
        return !path.isEmpty() && !path.startsWith(".") && !path.endsWith(".") && !path.contains("..");
    }

    private static boolean validSourcePaths(List<?> paths) {
        if (paths.isEmpty()) {
            return false;
        }
        for (Object path : paths) {
            if (!(path instanceof String) || !validPath((String) path)) {
                return false;
            }
        }
        return true;
    }

    private static String[] slice(String[] values, int end) {
        String[] result = new String[end];
        System.arraycopy(values, 0, result, 0, end);
        return result;
    }

    private static String[] slice(String[] values, int start, int end) {
        String[] result = new String[end - start];
        System.arraycopy(values, start, result, 0, result.length);
        return result;
    }
}
