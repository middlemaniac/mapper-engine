import math
import re


class DataMapper:
    @staticmethod
    def resolvePath(value, parts, index=0):
        if index == len(parts):
            return value

        if isinstance(value, list):
            selector = parts[index]
            if selector.isdigit():
                position = int(selector)
                if position >= len(value):
                    raise KeyError("array index out of range: " + selector)
                return DataMapper.resolvePath(value[position], parts, index + 1)
            return [
                DataMapper.resolvePath(item, parts, index)
                for item in value
            ]

        if not isinstance(value, dict) or parts[index] not in value:
            raise KeyError("path not found: " + ".".join(parts[:index + 1]))
        return DataMapper.resolvePath(value[parts[index]], parts, index + 1)

    @staticmethod
    def convertValue(value, value_type, target_path):
        if isinstance(value, list):
            return [
                DataMapper.convertValue(item, value_type, target_path)
                for item in value
            ]
        if isinstance(value, dict):
            if value_type is None:
                return value
            raise ValueError("a scalar type cannot be applied to an object")
        if value_type is None:
            leaf = target_path.rsplit(".", 1)[-1]
            if (
                leaf.lower() == "id"
                and re.fullmatch(r"[+-]?(?:0|[1-9]\d*)", str(value).strip())
            ):
                return int(value)
            return str(value)
        if value_type == "string":
            return str(value)
        if value_type == "integer":
            if not re.fullmatch(r"[+-]?\d+", str(value).strip()):
                raise ValueError("not an integer: " + str(value))
            return int(value)
        if value_type == "number":
            number = float(value)
            if not math.isfinite(number):
                raise ValueError("not a finite number: " + str(value))
            return number
        if value_type == "boolean":
            normalized = str(value).strip().lower()
            if normalized not in ("true", "false"):
                raise ValueError("not a boolean: " + str(value))
            return normalized == "true"
        raise ValueError("unsupported mapping type: " + value_type)

    @staticmethod
    def inferTargetRoot(mapping):
        target_root = mapping.get("targetRoot")
        if target_root:
            return target_root
        if mapping.get("preserveTargetRoot") is True:
            return None
        if any(
            not isinstance(entry, dict)
            or not isinstance(entry.get("targetPath"), str)
            or "." not in entry["targetPath"]
            for entry in mapping["mappings"]
        ):
            return None
        targets = [
            entry["targetPath"].split(".")[0]
            for entry in mapping["mappings"]
        ]
        if targets and len(set(targets)) == 1:
            return targets[0]
        return None

    @staticmethod
    def setPath(target, path, value):
        parts = path.split(".")
        current = target
        for part in parts[:-1]:
            if part not in current:
                current[part] = {}
            if not isinstance(current[part], dict):
                raise ValueError("target path conflicts at: " + part)
            current = current[part]
        if parts[-1] in current:
            raise ValueError("target path is mapped more than once: " + path)
        current[parts[-1]] = value

    @staticmethod
    def transform(source_data, mapping):
        if not isinstance(mapping, dict) or not isinstance(mapping.get("mappings"), list):
            raise ValueError("mapping file must contain a 'mappings' array")
        if "targetRoot" in mapping and not isinstance(mapping["targetRoot"], str):
            raise ValueError("targetRoot must be a string")
        if (
            "preserveTargetRoot" in mapping
            and not isinstance(mapping["preserveTargetRoot"], bool)
        ):
            raise ValueError("preserveTargetRoot must be a boolean")

        target_root = DataMapper.inferTargetRoot(mapping)
        source_root = DataMapper.inferSourceRoot(mapping)
        omit_source_root = (
            source_root is not None
            and (not isinstance(source_data, dict) or source_root not in source_data)
        )
        output = {}
        for entry in mapping["mappings"]:
            if not isinstance(entry, dict):
                raise ValueError("each mapping must be an object")
            source_path = entry.get("sourcePath")
            source_paths = entry.get("sourcePaths")
            target_path = entry.get("targetPath")
            valid_source = (
                isinstance(source_path, str)
                and source_path
                and all(source_path.split("."))
            )
            valid_sources = (
                isinstance(source_paths, list)
                and source_paths
                and all(
                    isinstance(path, str) and path and all(path.split("."))
                    for path in source_paths
                )
            )
            has_source_path = "sourcePath" in entry
            has_source_paths = "sourcePaths" in entry
            if (
                has_source_path == has_source_paths
                or (has_source_path and not valid_source)
                or (has_source_paths and not valid_sources)
                or not isinstance(target_path, str)
                or not target_path
            ):
                raise ValueError(
                    "each mapping requires sourcePath or sourcePaths and a valid targetPath"
                )
            if "type" in entry and not isinstance(entry["type"], str):
                raise ValueError("type must be a string")
            if not all(target_path.split(".")):
                raise ValueError("targetPath must contain non-empty path segments")

            if valid_sources:
                separator = entry.get("separator", "")
                if not isinstance(separator, str):
                    raise ValueError("separator must be a string")
                values = [
                    DataMapper.resolvePath(
                        source_data,
                        path.split(".")[1:]
                        if omit_source_root and path.split(".")[0] == source_root
                        else path.split("."),
                    )
                    for path in source_paths
                ]
                if any(isinstance(value, (dict, list)) for value in values):
                    raise ValueError("sourcePaths can only combine scalar values")
                source = separator.join(str(value) for value in values)
            else:
                source_parts = source_path.split(".")
                if omit_source_root and source_parts[0] == source_root:
                    source_parts = source_parts[1:]
                source = DataMapper.resolvePath(source_data, source_parts)

            if target_root:
                prefix = target_root + "."
                if not target_path.startswith(prefix):
                    raise ValueError("targetPath must start with targetRoot: " + target_path)
                target_path = target_path[len(prefix):]
            if not target_path:
                raise ValueError("targetPath must name an output property")

            original_target = entry["targetPath"]
            converted = DataMapper.convertValue(
                source, entry.get("type"), original_target
            )
            DataMapper.setPath(output, target_path, converted)
        return output

    @staticmethod
    def inferSourceRoot(mapping):
        paths = []
        for entry in mapping["mappings"]:
            if isinstance(entry, dict) and isinstance(entry.get("sourcePath"), str):
                paths.append(entry["sourcePath"])
            elif isinstance(entry, dict) and isinstance(entry.get("sourcePaths"), list):
                paths.extend(entry["sourcePaths"])
            else:
                return None
        if not paths or any(not isinstance(path, str) or "." not in path for path in paths):
            return None
        roots = {path.split(".")[0] for path in paths}
        if len(roots) != 1:
            return None
        root = next(iter(roots))
        return None if root.isdigit() else root
