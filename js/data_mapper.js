class DataMapper {
  static resolvePath(value, parts, index = 0) {
    if (index === parts.length) return value;

    if (Array.isArray(value)) {
      const selector = parts[index];
      if (/^\d+$/.test(selector)) {
        const position = Number(selector);
        if (position >= value.length) throw new Error(`Array index out of range: ${selector}`);
        return DataMapper.resolvePath(value[position], parts, index + 1);
      }
      return value.map((item) => DataMapper.resolvePath(item, parts, index));
    }

    if (value === null || typeof value !== "object" || !Object.hasOwn(value, parts[index])) {
      throw new Error(`Path not found: ${parts.slice(0, index + 1).join(".")}`);
    }
    return DataMapper.resolvePath(value[parts[index]], parts, index + 1);
  }

  static convertValue(value, type, targetPath) {
    if (Array.isArray(value)) {
      return value.map((item) => DataMapper.convertValue(item, type, targetPath));
    }
    if (value !== null && typeof value === "object") {
      if (!type) return value;
      throw new Error("A scalar type cannot be applied to an object");
    }
    if (!type) {
      const leaf = targetPath.split(".").pop();
      if (leaf.toLowerCase() === "id" && /^[+-]?(?:0|[1-9]\d*)$/.test(String(value).trim())) {
        const number = Number(value);
        if (!Number.isSafeInteger(number)) throw new Error(`Integer is out of range: ${value}`);
        return number;
      }
      return String(value);
    }
    if (type === "string") return String(value);
    if (type === "integer") {
      if (!/^[+-]?\d+$/.test(String(value).trim())) {
        throw new Error(`Not an integer: ${value}`);
      }
      const number = Number(value);
      if (!Number.isSafeInteger(number)) throw new Error(`Integer is out of range: ${value}`);
      return number;
    }
    if (type === "number") {
      const number = Number(value);
      if (!Number.isFinite(number)) throw new Error(`Not a finite number: ${value}`);
      return number;
    }
    if (type === "boolean") {
      const normalized = String(value).trim().toLowerCase();
      if (normalized !== "true" && normalized !== "false") throw new Error(`Not a boolean: ${value}`);
      return normalized === "true";
    }
    throw new Error(`Unsupported mapping type: ${type}`);
  }

  static inferTargetRoot(mapping) {
    if (mapping.targetRoot) return mapping.targetRoot;
    if (mapping.preserveTargetRoot === true) return null;
    if (mapping.mappings.some((entry) =>
      !entry || typeof entry.targetPath !== "string" || !entry.targetPath.includes("."))) {
      return null;
    }
    const targets = mapping.mappings.map((entry) => entry.targetPath.split(".")[0]);
    return targets.length && new Set(targets).size === 1 ? targets[0] : null;
  }

  static inferSourceRoot(mapping) {
    const paths = [];
    for (const entry of mapping.mappings) {
      if (!entry || typeof entry !== "object") return null;
      if (typeof entry.sourcePath === "string") paths.push(entry.sourcePath);
      else if (Array.isArray(entry.sourcePaths)) paths.push(...entry.sourcePaths);
      else return null;
    }
    if (paths.length === 0 || paths.some((sourcePath) =>
      typeof sourcePath !== "string" || !sourcePath.includes("."))) return null;
    const roots = paths.map((sourcePath) => sourcePath.split(".")[0]);
    return new Set(roots).size === 1 && !/^\d+$/.test(roots[0]) ? roots[0] : null;
  }

  static setPath(target, path, value) {
    const parts = path.split(".");
    let current = target;
    for (const part of parts.slice(0, -1)) {
      if (!Object.hasOwn(current, part)) {
        Object.defineProperty(current, part, {
          value: {},
          writable: true,
          enumerable: true,
          configurable: true,
        });
      }
      if (current[part] === null || typeof current[part] !== "object" || Array.isArray(current[part])) {
        throw new Error(`Target path conflicts at: ${part}`);
      }
      current = current[part];
    }
    const leaf = parts[parts.length - 1];
    if (Object.hasOwn(current, leaf)) throw new Error(`Target path is mapped more than once: ${path}`);
    Object.defineProperty(current, leaf, {
      value,
      writable: true,
      enumerable: true,
      configurable: true,
    });
  }

  static transform(sourceData, mapping) {
    if (!mapping || !Array.isArray(mapping.mappings)) {
      throw new Error("Mapping file must contain a 'mappings' array");
    }
    if (Object.hasOwn(mapping, "targetRoot") && typeof mapping.targetRoot !== "string") {
      throw new Error("targetRoot must be a string");
    }
    if (Object.hasOwn(mapping, "preserveTargetRoot") && typeof mapping.preserveTargetRoot !== "boolean") {
      throw new Error("preserveTargetRoot must be a boolean");
    }
    const targetRoot = DataMapper.inferTargetRoot(mapping);
    const sourceRoot = DataMapper.inferSourceRoot(mapping);
    const omitSourceRoot = sourceRoot !== null
      && (!sourceData || typeof sourceData !== "object" || !Object.hasOwn(sourceData, sourceRoot));
    const output = {};
    for (const entry of mapping.mappings) {
      const hasSourcePath = entry && typeof entry.sourcePath === "string"
        && entry.sourcePath && !entry.sourcePath.split(".").some((part) => !part);
      const hasSourcePaths = entry && Array.isArray(entry.sourcePaths)
        && entry.sourcePaths.length > 0
        && entry.sourcePaths.every((path) => typeof path === "string" && path
          && !path.split(".").some((part) => !part));
      if (!entry || Object.hasOwn(entry, "sourcePath") === Object.hasOwn(entry, "sourcePaths")
        || (Object.hasOwn(entry, "sourcePath") && !hasSourcePath)
        || (Object.hasOwn(entry, "sourcePaths") && !hasSourcePaths)
        || typeof entry.targetPath !== "string" || !entry.targetPath
        || entry.targetPath.split(".").some((part) => !part)) {
        throw new Error("Each mapping requires sourcePath or sourcePaths and a valid targetPath");
      }
      if (Object.hasOwn(entry, "type") && typeof entry.type !== "string") {
        throw new Error("type must be a string");
      }
      let source;
      if (hasSourcePaths) {
        const separator = entry.separator === undefined ? "" : entry.separator;
        if (typeof separator !== "string") throw new Error("separator must be a string");
        const values = entry.sourcePaths.map((path) => {
          const parts = path.split(".");
          if (omitSourceRoot && parts[0] === sourceRoot) parts.shift();
          return DataMapper.resolvePath(sourceData, parts);
        });
        if (values.some((value) => value !== null && typeof value === "object")) {
          throw new Error("sourcePaths can only combine scalar values");
        }
        source = values.map(String).join(separator);
      } else {
        const parts = entry.sourcePath.split(".");
        if (omitSourceRoot && parts[0] === sourceRoot) parts.shift();
        source = DataMapper.resolvePath(sourceData, parts);
      }
      let outputPath = entry.targetPath;
      if (targetRoot) {
        const prefix = `${targetRoot}.`;
        if (!outputPath.startsWith(prefix)) {
          throw new Error(`targetPath must start with targetRoot: ${outputPath}`);
        }
        outputPath = outputPath.slice(prefix.length);
      }
      if (!outputPath) throw new Error("targetPath must name an output property");
      DataMapper.setPath(output, outputPath, DataMapper.convertValue(source, entry.type, entry.targetPath));
    }
    return output;
  }
}

module.exports = DataMapper;
