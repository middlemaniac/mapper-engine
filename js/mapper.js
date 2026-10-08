const DataMapper = require("./data_mapper");
const orchestrator = require("./orchestrator");

module.exports = DataMapper;

if (require.main === module) {
  try {
    orchestrator.main();
  } catch (error) {
    console.error(`Error: ${error.message}`);
    process.exitCode = 1;
  }
}
