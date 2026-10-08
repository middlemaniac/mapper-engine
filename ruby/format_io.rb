require "json"
require "rexml/document"
require "fileutils"

class FormatIO
  @format_codecs = {}

  class << self
    def registerFormat(format_name, reader, writer)
      raise "Format name must be a non-empty string" unless format_name.is_a?(String) && !format_name.empty?
      raise "Format reader and writer must be callable" unless reader.respond_to?(:call) && writer.respond_to?(:call)

      @format_codecs[format_name.downcase.delete_prefix(".")] = [reader, writer]
    end

    def formatForPath(file_path)
      format_name = File.extname(file_path).downcase.delete_prefix(".")
      raise "Format must be specified for paths without an extension" if format_name.empty?

      format_name
    end

    def readData(file_path, format_name = nil)
      selected_format = (format_name || formatForPath(file_path)).downcase.delete_prefix(".")
      codec = @format_codecs[selected_format]
      raise "Unsupported input format: #{selected_format}" unless codec

      codec[0].call(file_path)
    end

    def writeData(data, file_path = nil, format_name = nil, mapping = nil)
      selected_format = (format_name || (file_path ? formatForPath(file_path) : "json")).downcase.delete_prefix(".")
      codec = @format_codecs[selected_format]
      raise "Unsupported output format: #{selected_format}" unless codec

      rendered = codec[1].call(data, mapping)
      if file_path
        FileUtils.mkdir_p(File.dirname(file_path))
        File.write(file_path, rendered, encoding: "UTF-8")
      end
      rendered
    end

    def jsonToObject(json_path)
      JSON.parse(File.read(json_path, encoding: "UTF-8"))
    end

    def xmlToObject(xml_path)
      root = REXML::Document.new(File.read(xml_path, encoding: "UTF-8")).root
      raise "XML document has no root element" unless root

      { localName(root.name) => elementToValue(root) }
    end

    def localName(name)
      name.to_s.split(":", -1).last
    end

    def addChild(target, name, value)
      unless target.key?(name)
        target[name] = value
      else
        target[name] = [target[name]] unless target[name].is_a?(Array)
        target[name] << value
      end
    end

    def elementToValue(element)
      attributes = {}
      element.attributes.each_attribute do |attribute|
        next if attribute.prefix == "xmlns" || attribute.expanded_name == "xmlns"

        attributes["@#{localName(attribute.name)}"] = attribute.value
      end

      children = {}
      direct_text = +""
      has_elements = false
      element.children.each do |child|
        if child.is_a?(REXML::Element)
          has_elements = true
          addChild(children, localName(child.name), elementToValue(child))
        elsif child.is_a?(REXML::Text)
          direct_text << child.value
        end
      end

      text = direct_text.strip
      return text unless has_elements || !attributes.empty?

      result = attributes.merge(children)
      result["#text"] = text unless text.empty?
      result
    end

    def inferXmlRoot(mapping)
      return mapping["targetRoot"] if mapping["targetRoot"] && !mapping["targetRoot"].empty?

      entries = mapping["mappings"]
      return nil unless entries.is_a?(Array) && !entries.empty?

      roots = entries.map do |entry|
        next nil unless entry.is_a?(Hash) && entry["targetPath"].is_a?(String)

        entry["targetPath"].split(".").first
      end
      roots.first && roots.all? { |root| root == roots.first } ? roots.first : nil
    end

    def writeXmlData(data, mapping)
      root_name = inferXmlRoot(mapping)
      raise "XML output requires a common target root or targetRoot" unless root_name

      xml_data = data.key?(root_name) ? data : { root_name => data }
      toXml(xml_data, root_name)
    end

    def toXml(data, root_name)
      unless data.is_a?(Hash) && data.key?(root_name)
        raise "XML data must contain the root element: #{root_name}"
      end

      render = lambda do |name, value, depth|
        raise "Invalid XML element name: #{name}" unless name.match?(/\A[A-Za-z_][A-Za-z0-9_.:-]*\z/)

        indent = "  " * depth
        if value.is_a?(Array)
          next value.map { |item| render.call(name, item, depth) }.join
        end
        unless value.is_a?(Hash)
          text = value.nil? ? "" : escapeXml(value.to_s)
          next "#{indent}<#{name}>#{text}</#{name}>\n"
        end

        attributes = []
        children = []
        text = value["#text"]
        value.each do |key, child|
          next if key == "#text"

          if key.start_with?("@")
            attr_name = key[1..]
            raise "Invalid XML attribute name: #{attr_name}" unless attr_name.match?(/\A[A-Za-z_][A-Za-z0-9_.:-]*\z/)

            attributes << " #{attr_name}=\"#{escapeXml(child.to_s, true)}\""
          else
            children << render.call(key, child, depth + 1)
          end
        end
        start = "#{indent}<#{name}#{attributes.join}"
        if children.empty? && (text.nil? || text == "")
          next "#{start}/>\n"
        end
        if children.empty?
          next "#{start}>#{escapeXml(text.to_s)}</#{name}>\n"
        end
        text_content = text.nil? || text == "" ? "" : "#{indent}  #{escapeXml(text.to_s)}\n"
        "#{start}>\n#{text_content}#{children.join}#{indent}</#{name}>\n"
      end

      "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n#{render.call(root_name, data[root_name], 0)}"
    end

    def escapeXml(value, attribute = false)
      escaped = value.gsub("&", "&amp;").gsub("<", "&lt;").gsub(">", "&gt;")
      attribute ? escaped.gsub('"', "&quot;").gsub("'", "&apos;") : escaped
    end
  end
end

FormatIO.registerFormat(
  "json",
  ->(file_path) { FormatIO.jsonToObject(file_path) },
  ->(data, _mapping) { JSON.pretty_generate(data) + "\n" },
)
FormatIO.registerFormat(
  "xml",
  ->(file_path) { FormatIO.xmlToObject(file_path) },
  ->(data, mapping) { FormatIO.writeXmlData(data, mapping) },
)
