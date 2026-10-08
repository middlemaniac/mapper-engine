class DataMapper
  def self.resolvePath(value, parts, index = 0)
    return value if index == parts.length

    if value.is_a?(Array)
      selector = parts[index]
      if selector.match?(/\A\d+\z/)
        position = selector.to_i
        raise "Array index out of range: #{selector}" if position >= value.length

        return resolvePath(value[position], parts, index + 1)
      end
      return value.map { |item| resolvePath(item, parts, index) }
    end

    unless value.is_a?(Hash) && value.key?(parts[index])
      raise "Path not found: #{parts.take(index + 1).join(".")}"
    end
    resolvePath(value[parts[index]], parts, index + 1)
  end

  def self.convertValue(value, type, target_path)
    return value.map { |item| convertValue(item, type, target_path) } if value.is_a?(Array)
    if value.is_a?(Hash)
      return value if type.nil?

      raise "A scalar type cannot be applied to an object"
    end
    if type.nil?
      leaf = target_path.split(".").last
      return Integer(value, 10) if leaf.casecmp("id").zero? && value.to_s.strip.match?(/\A[+-]?(?:0|[1-9]\d*)\z/)

      return value.to_s
    end

    case type
    when "string"
      value.to_s
    when "integer"
      raise "Not an integer: #{value}" unless value.to_s.strip.match?(/\A[+-]?\d+\z/)

      Integer(value, 10)
    when "number"
      number = Float(value)
      raise "Not a finite number: #{value}" unless number.finite?

      number
    when "boolean"
      normalized = value.to_s.strip.downcase
      raise "Not a boolean: #{value}" unless %w[true false].include?(normalized)

      normalized == "true"
    else
      raise "Unsupported mapping type: #{type}"
    end
  end

  def self.inferTargetRoot(mapping)
    return mapping["targetRoot"] if mapping["targetRoot"]
    return nil if mapping["preserveTargetRoot"] == true

    entries = mapping["mappings"]
    valid_targets = entries.all? { |entry|
      entry.is_a?(Hash) && entry["targetPath"].is_a?(String) && entry["targetPath"].include?(".")
    }
    return nil unless valid_targets

    targets = entries.map { |entry| entry["targetPath"].split(".").first }
    targets.uniq.length == 1 ? targets.first : nil
  end

  def self.setPath(target, path, value)
    parts = path.split(".")
    current = target
    parts[0...-1].each do |part|
      current[part] = {} unless current.key?(part)
      raise "Target path conflicts at: #{part}" unless current[part].is_a?(Hash)

      current = current[part]
    end

    leaf = parts.last
    raise "Target path is mapped more than once: #{path}" if current.key?(leaf)

    current[leaf] = value
  end

  def self.inferSourceRoot(mapping)
    paths = []
    mapping["mappings"].each do |entry|
      if entry.is_a?(Hash) && entry["sourcePath"].is_a?(String)
        paths << entry["sourcePath"]
      elsif entry.is_a?(Hash) && entry["sourcePaths"].is_a?(Array)
        paths.concat(entry["sourcePaths"])
      else
        return nil
      end
    end
    return nil if paths.empty? || paths.any? { |path| !path.is_a?(String) || !path.include?(".") }

    roots = paths.map { |path| path.split(".").first }.uniq
    roots.length == 1 && !roots.first.match?(/\A\d+\z/) ? roots.first : nil
  end

  def self.transform(source_data, mapping)
    unless mapping.is_a?(Hash) && mapping["mappings"].is_a?(Array)
      raise "Mapping file must contain a 'mappings' array"
    end
    raise "targetRoot must be a string" if mapping.key?("targetRoot") && !mapping["targetRoot"].is_a?(String)
    if mapping.key?("preserveTargetRoot") && ![true, false].include?(mapping["preserveTargetRoot"])
      raise "preserveTargetRoot must be a boolean"
    end

    target_root = inferTargetRoot(mapping)
    source_root = inferSourceRoot(mapping)
    omit_source_root = source_root && (!source_data.is_a?(Hash) || !source_data.key?(source_root))
    output = {}
    mapping["mappings"].each do |entry|
      source_path = entry.is_a?(Hash) ? entry["sourcePath"] : nil
      source_paths = entry.is_a?(Hash) ? entry["sourcePaths"] : nil
      target_path = entry.is_a?(Hash) ? entry["targetPath"] : nil
      has_source_path = source_path.is_a?(String) && !source_path.empty? &&
                        source_path.split(".", -1).none?(&:empty?)
      has_source_paths = source_paths.is_a?(Array) && !source_paths.empty? &&
                         source_paths.all? { |path|
                           path.is_a?(String) && !path.empty? &&
                             path.split(".", -1).none?(&:empty?)
                         }
      unless entry.key?("sourcePath") != entry.key?("sourcePaths") &&
             (entry.key?("sourcePath") ? has_source_path : has_source_paths) &&
             target_path.is_a?(String) &&
             !target_path.empty? && target_path.split(".", -1).none?(&:empty?)
        raise "Each mapping requires sourcePath or sourcePaths and a valid targetPath"
      end
      raise "type must be a string" if entry.key?("type") && !entry["type"].is_a?(String)

      if has_source_paths
        separator = entry.fetch("separator", "")
        raise "separator must be a string" unless separator.is_a?(String)

        values = source_paths.map do |path|
          parts = path.split(".")
          parts.shift if omit_source_root && parts.first == source_root
          resolvePath(source_data, parts)
        end
        raise "sourcePaths can only combine scalar values" if values.any? { |value| value.is_a?(Hash) || value.is_a?(Array) }

        source = values.map(&:to_s).join(separator)
      else
        parts = source_path.split(".")
        parts.shift if omit_source_root && parts.first == source_root
        source = resolvePath(source_data, parts)
      end
      target_path = entry["targetPath"]
      if target_root
        prefix = "#{target_root}."
        raise "targetPath must start with targetRoot: #{target_path}" unless target_path.start_with?(prefix)

        target_path = target_path.delete_prefix(prefix)
      end
      raise "targetPath must name an output property" if target_path.empty?

      setPath(output, target_path, convertValue(source, entry["type"], entry["targetPath"]))
    end
    output
  end
end
