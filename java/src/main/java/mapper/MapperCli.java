package mapper;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class MapperCli {
    private MapperCli() {
    }

    public static void main(String[] args) {
        try {
            List<String> positional = new ArrayList<>();
            String inputFormat = null;
            String outputFormat = null;
            for (int i = 0; i < args.length; i++) {
                if ("--input-format".equals(args[i]) || "--output-format".equals(args[i])) {
                    String option = args[i];
                    if (++i >= args.length || args[i].startsWith("--")) {
                        throw new IllegalArgumentException(option + " requires a format name");
                    }
                    if ("--input-format".equals(option)) {
                        inputFormat = args[i];
                    } else {
                        outputFormat = args[i];
                    }
                } else if (args[i].startsWith("--")) {
                    throw new IllegalArgumentException("Unknown option: " + args[i]);
                } else {
                    positional.add(args[i]);
                }
            }
            if (positional.size() < 2 || positional.size() > 3) {
                throw new IllegalArgumentException("Usage: java -jar mapper.jar "
                        + "[--input-format name] <input> <mapping.json> [output] "
                        + "[--output-format name]");
            }

            Path input = Paths.get(positional.get(0));
            Map<String, Object> mapping = JsonFormat.readMapping(Paths.get(positional.get(1)));
            Object data = FormatIO.readData(input, inputFormat);
            Object result = DataMapper.transform(data, mapping);
            Path output = positional.size() == 3 ? Paths.get(positional.get(2)) : null;
            String rendered = FormatIO.writeData(result, output, outputFormat, mapping);
            if (output == null) {
                System.out.print(rendered);
            }
        } catch (Exception error) {
            System.err.println("Error: " + error.getMessage());
            System.exit(1);
        }
    }
}
