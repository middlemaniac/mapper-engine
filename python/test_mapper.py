import json
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

import data_mapper
from data_mapper import DataMapper
from format_io import read_data, register_format, write_data
from mapper import DataMapper as LegacyDataMapper, main as legacy_main


ROOT = Path(__file__).resolve().parent.parent


class MapperTests(unittest.TestCase):
    def test_mapping_core_has_no_format_codec_dependencies(self):
        self.assertNotIn("json", data_mapper.__dict__)
        self.assertNotIn("ET", data_mapper.__dict__)
        self.assertFalse(hasattr(DataMapper, "readData"))
        self.assertFalse(hasattr(DataMapper, "writeData"))

    def test_legacy_module_reexports_mapper_and_cli(self):
        self.assertIs(LegacyDataMapper, DataMapper)
        self.assertTrue(callable(legacy_main))

    def test_person_mapping_generates_employee_xml(self):
        person = read_data(ROOT / "sample" / "person.json")
        mapping = read_data(ROOT / "sample" / "person-mapping.json")
        document = ET.fromstring(
            write_data(DataMapper.transform(person, mapping), format_name="xml", mapping=mapping)
        )

        self.assertEqual(document.tag, "Employee")
        self.assertEqual(document.findtext("EmpID"), "101")
        self.assertEqual(document.findtext("FirstName"), "Alice Smith")
        self.assertEqual(document.findtext("Address/City"), "Metropolis")
        self.assertEqual(document.findtext("Address/ZipCode"), "10001")

    def test_json_codec_reads_and_writes_data(self):
        person = read_data(ROOT / "sample" / "person.json", "json")
        mapping = read_data(ROOT / "sample" / "person-mapping.json")
        mapped = DataMapper.transform(person, mapping)
        rendered = write_data(mapped, format_name="json", mapping=mapping)

        self.assertEqual(json.loads(rendered)["FirstName"], "Alice Smith")

    def test_xml_codec_reads_and_writes_data(self):
        source = read_data(ROOT / "sample" / "employee.xml", "xml")
        mapping = read_data(ROOT / "sample" / "employee-mapping.json")
        mapped = DataMapper.transform(source, mapping)
        document = ET.fromstring(
            write_data(mapped, format_name="xml", mapping=mapping)
        )

        self.assertEqual(document.tag, "person")
        self.assertEqual(document.findtext("id"), "101")
        self.assertEqual(document.findtext("fullName"), "Alice")

    def test_json_to_xml_keeps_existing_source_envelope(self):
        person = read_data(ROOT / "sample" / "person.json")
        mapping = read_data(ROOT / "sample" / "person-mapping.json")
        document = ET.fromstring(
            write_data(
                DataMapper.transform({"person": person}, mapping),
                format_name="xml",
                mapping=mapping,
            )
        )

        self.assertEqual(document.findtext("FirstName"), "Alice Smith")

    def test_custom_format_codec_can_be_registered(self):
        register_format(
            "testdata",
            lambda path: json.loads(Path(path).read_text(encoding="utf-8")),
            lambda data, mapping: json.dumps(data) + "\n",
        )
        source = ROOT / "sample" / "person.json"
        loaded = read_data(source, "testdata")
        self.assertEqual(loaded["fullName"], "Alice Smith")
        rendered = write_data(loaded, format_name="testdata")
        self.assertEqual(json.loads(rendered)["fullName"], "Alice Smith")

    def test_sample_mapping_is_applied_without_modification(self):
        source = read_data(ROOT / "sample" / "employee.xml")
        mapping = read_data(ROOT / "sample" / "employee-mapping.json")
        result = DataMapper.transform(source, mapping)

        self.assertEqual(result["id"], 101)
        self.assertEqual(result["fullName"], "Alice")
        self.assertNotIn("LastName", result)
        self.assertEqual(result["contactInfo"]["location"]["postalCode"], "10001")

    def test_repeated_xml_elements_map_to_arrays(self):
        source = {
            "Feed": {
                "Employee": [
                    {"Name": "Ari", "Active": "true"},
                    {"Name": "Bo", "Active": "false"},
                ]
            }
        }
        mapping = {
            "mappings": [
                {"sourcePath": "Feed.Employee.Name", "targetPath": "names"},
                {
                    "sourcePath": "Feed.Employee.Active",
                    "targetPath": "active",
                    "type": "boolean",
                },
            ]
        }
        self.assertEqual(
            DataMapper.transform(source, mapping),
            {"names": ["Ari", "Bo"], "active": [True, False]},
        )

    def test_top_level_array_paths_are_not_treated_as_envelopes(self):
        source = [{"Name": "Ari", "Age": "35"}, {"Name": "Bo", "Age": "29"}]
        mapping = {
            "mappings": [
                {"sourcePath": "0.Name", "targetPath": "firstName"},
                {"sourcePath": "0.Age", "targetPath": "firstAge"},
            ]
        }

        self.assertEqual(
            DataMapper.transform(source, mapping),
            {"firstName": "Ari", "firstAge": "35"},
        )


if __name__ == "__main__":
    unittest.main()
