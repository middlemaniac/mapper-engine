require_relative "data_mapper"
require_relative "format_io"

class MapperOrchestrator
  def self.main(args = ARGV)
    begin
      positional = []
      input_format = nil
      output_format = nil
      index = 0
      while index < args.length
        if ["--input-format", "--output-format"].include?(args[index])
          option = args[index]
          value = args[index + 1]
          raise "#{option} requires a format name" if value.nil? || value.start_with?("--")

          if option == "--input-format"
            input_format = value
          else
            output_format = value
          end
          index += 1
        elsif args[index].start_with?("--")
          raise "Unknown option: #{args[index]}"
        else
          positional << args[index]
        end
        index += 1
      end
      unless (2..3).cover?(positional.length)
        raise "Usage: ruby mapper.rb [--input-format name] <input> <mapping.json> [output] [--output-format name]"
      end

      mapping = FormatIO.jsonToObject(positional[1])
      source = FormatIO.readData(positional[0], input_format)
      result = DataMapper.transform(source, mapping)
      rendered = FormatIO.writeData(result, positional[2], output_format, mapping)
      print rendered unless positional[2]
    rescue StandardError => error
      warn "Error: #{error.message}"
      1
    end
  end
end
