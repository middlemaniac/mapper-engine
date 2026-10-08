# Format-independent data mapper

This repository contains equivalent command-line mappers in Java, JavaScript, Python, and Ruby. The pipeline separates format-specific reading and writing from one shared mapping transformation. Any registered input format can be mapped to any registered output format; format pairing does not affect mapping behavior.

Each runtime separates responsibilities into `DataMapper` (format-neutral mapping), a format I/O module (`FormatIO` or `format_io`) for reading and writing, and an orchestration entry point (`orchestrator`, with the existing `mapper` command retained as a thin launcher). Java keeps JSON and XML codecs in separate files; the other runtimes define their codecs in the format I/O module. The common mapping class exposes `transform(data, mapping)`; format readers and writers have their own APIs and can be extended without changing mapping logic.

## Run the sample conversions

From the repository root, file extensions select the registered input and output codecs independently. The following sample commands demonstrate both directions, but the mapper does not restrict which formats may be paired:

```text
python python/mapper.py sample/employee.xml sample/employee-mapping.json output/person.json
node js/mapper.js sample/employee.xml sample/employee-mapping.json output/person.json
ruby ruby/mapper.rb sample/employee.xml sample/employee-mapping.json output/person.json
python python/mapper.py sample/person.json sample/person-mapping.json output/employee.xml
node js/mapper.js sample/person.json sample/person-mapping.json output/employee.xml
ruby ruby/mapper.rb sample/person.json sample/person-mapping.json output/employee.xml
```

For Java, build the executable JAR from `java` using `mvn package`, then run it from the repository root:

```text
java -jar java/target/format-data-mapper-1.0.0.jar sample/employee.xml sample/employee-mapping.json output/person.json
java -jar java/target/format-data-mapper-1.0.0.jar sample/person.json sample/person-mapping.json output/employee.xml
```

Install the JavaScript dependency with `npm install` in `js` before running that implementation. All mappers create the output directory when needed. When the output path is omitted, the mapped result is written as JSON to standard output.

## Format codecs

JSON and XML codecs are built in and live separately from the shared mapping class. Codec selection uses the input/output file extension by default; callers can pass `--input-format` and `--output-format` on the command line, including for paths without an extension. Additional formats register independent readers and writers in the runtime format I/O layer and do not require changes to `DataMapper.transform`. For example, a registered `csv` codec can be selected with `--input-format csv` or an output path ending in `.csv`.

## Mapping behavior

`transform(data, mapping)` is the single mapping operation for every format. `mappings` is an array of `{ "sourcePath": "...", "targetPath": "...", "type": "..." }` entries. Use `sourcePaths` with an optional `separator` to join multiple scalar source values into one target value. Paths are dot-separated names; repeated XML elements become arrays, paths through arrays collect matching values, and numeric path segments select an array element by zero-based index. XML readers ignore namespace prefixes. XML attributes are represented as `@name`, and mixed-content text as `#text`.

If all source paths share a leading segment that is absent from the parsed input object, the mapper treats it as an optional source envelope and omits it. If all target paths share the same first segment and contain nested paths, that segment is treated as the target entity root and omitted from the mapped object. Set `targetRoot` to name the root explicitly, or `preserveTargetRoot` to retain it in the mapped object. XML writing uses the target root as the document element. Supported explicit `type` values are `string`, `integer`, `number`, and `boolean`. Without a type, scalar values remain strings, except integer values mapped to an `id` property without leading zeroes, which are emitted as integers; objects and arrays are preserved.

To add another format in a runtime, register a reader that returns the runtime's normal object/list/scalar representation and a writer that serializes the mapped representation. The writer callback receives `(data, mapping)` so it can use format-specific metadata without adding format conditions to the mapping operation.

## Python tests

Run `python -m unittest discover -s python` from the repository root.

The sample commands use `sample/employee-mapping.json` and `sample/person-mapping.json` directly; both map only fields specified in their mapping files. Generated output goes into `output` so sample inputs remain unchanged.
