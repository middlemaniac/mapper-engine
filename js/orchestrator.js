const DataMapper = require("./data_mapper");
const formatIO = require("./format_io");

function parseArguments(args) {
  const positional = [];
  let inputFormat;
  let outputFormat;
  for (let index = 0; index < args.length; index += 1) {
    if (args[index] === "--input-format" || args[index] === "--output-format") {
      const option = args[index];
      const value = args[index + 1];
      if (!value || value.startsWith("--")) throw new Error(`${option} requires a format name`);
      if (option === "--input-format") inputFormat = value;
      else outputFormat = value;
      index += 1;
    } else if (args[index].startsWith("--")) {
      throw new Error(`Unknown option: ${args[index]}`);
    } else {
      positional.push(args[index]);
    }
  }
  const [inputPath, mappingPath, outputPath] = positional;
  if (!inputPath || !mappingPath || positional.length > 3) {
    throw new Error(
      "Usage: node mapper.js [--input-format name] <input> <mapping.json> [output] [--output-format name]",
    );
  }
  return { inputPath, mappingPath, outputPath, inputFormat, outputFormat };
}

function main(args = process.argv.slice(2)) {
  const { inputPath, mappingPath, outputPath, inputFormat, outputFormat } = parseArguments(args);
  const mapping = formatIO.jsonToObject(mappingPath);
  const source = formatIO.readData(inputPath, inputFormat);
  const result = DataMapper.transform(source, mapping);
  const rendered = formatIO.writeData(result, outputPath, outputFormat, mapping);
  if (!outputPath) process.stdout.write(rendered);
}

module.exports = { main, parseArguments };

if (require.main === module) {
  try {
    main();
  } catch (error) {
    console.error(`Error: ${error.message}`);
    process.exitCode = 1;
  }
}
