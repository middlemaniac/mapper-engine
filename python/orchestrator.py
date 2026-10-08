import argparse
import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

try:
    from .data_mapper import DataMapper
    from .format_io import read_data, write_data
except ImportError:
    from data_mapper import DataMapper
    from format_io import read_data, write_data


def main(args=None):
    parser = argparse.ArgumentParser(
        description="Transform data between registered formats using a JSON mapping file."
    )
    parser.add_argument("input", type=Path)
    parser.add_argument("mapping", type=Path)
    parser.add_argument("output", nargs="?", type=Path)
    parser.add_argument("--input-format")
    parser.add_argument("--output-format")
    parsed = parser.parse_args(args)

    try:
        mapping = read_data(parsed.mapping, "json")
        source = read_data(parsed.input, parsed.input_format)
        result = DataMapper.transform(source, mapping)
        rendered = write_data(
            result,
            parsed.output,
            parsed.output_format,
            mapping,
        )
        if parsed.output is None:
            sys.stdout.write(rendered)
    except (OSError, ET.ParseError, json.JSONDecodeError, KeyError, ValueError) as error:
        print("Error: " + str(error), file=sys.stderr)
        return 1
    return 0
