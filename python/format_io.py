import json
import re
import xml.etree.ElementTree as ET
from pathlib import Path
from xml.sax.saxutils import escape, quoteattr


_format_codecs = {}


def register_format(format_name, reader, writer):
    if not isinstance(format_name, str) or not format_name:
        raise ValueError("format name must be a non-empty string")
    if not callable(reader) or not callable(writer):
        raise ValueError("format reader and writer must be callable")
    _format_codecs[format_name.lower().lstrip(".")] = (reader, writer)


def format_for_path(path):
    format_name = Path(path).suffix.lower().lstrip(".")
    if not format_name:
        raise ValueError("format must be specified for paths without an extension")
    return format_name


def read_data(path, format_name=None):
    selected_format = (
        format_name.lower().lstrip(".")
        if format_name
        else format_for_path(path)
    )
    codec = _format_codecs.get(selected_format)
    if codec is None:
        raise ValueError("unsupported input format: " + selected_format)
    return codec[0](path)


def write_data(data, path=None, format_name=None, mapping=None):
    selected_format = (
        format_name.lower().lstrip(".")
        if format_name
        else format_for_path(path)
        if path is not None
        else "json"
    )
    codec = _format_codecs.get(selected_format)
    if codec is None:
        raise ValueError("unsupported output format: " + selected_format)
    rendered = codec[1](data, mapping)
    if path is not None:
        output_path = Path(path)
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_text(rendered, encoding="utf-8")
    return rendered


def json_to_object(json_path):
    return json.loads(Path(json_path).read_text(encoding="utf-8"))


def local_name(name):
    return name.rsplit("}", 1)[-1].rsplit(":", 1)[-1]


def add_child(target, name, value):
    if name not in target:
        target[name] = value
    elif isinstance(target[name], list):
        target[name].append(value)
    else:
        target[name] = [target[name], value]


def element_to_value(element):
    attributes = {
        "@" + local_name(name): value
        for name, value in element.attrib.items()
    }
    children = {}
    has_children = False
    direct_text = element.text or ""

    for child in element:
        has_children = True
        add_child(
            children,
            local_name(child.tag),
            element_to_value(child),
        )
        direct_text += child.tail or ""

    text = direct_text.strip()
    if not has_children and not attributes:
        return text

    result = attributes
    result.update(children)
    if text:
        result["#text"] = text
    return result


def xml_to_object(xml_path):
    root = ET.parse(xml_path).getroot()
    return {local_name(root.tag): element_to_value(root)}


def infer_xml_root(mapping):
    configured_root = mapping.get("targetRoot")
    if configured_root:
        return configured_root
    targets = [
        entry.get("targetPath")
        for entry in mapping.get("mappings", [])
        if isinstance(entry, dict) and isinstance(entry.get("targetPath"), str)
    ]
    if len(targets) != len(mapping.get("mappings", [])) or not targets:
        return None
    roots = {target.split(".")[0] for target in targets}
    return next(iter(roots)) if len(roots) == 1 else None


def to_xml(data, root_name):
    if not isinstance(data, dict) or root_name not in data:
        raise ValueError("XML data must contain the root element: " + root_name)
    valid_name = re.compile(r"^[A-Za-z_][A-Za-z0-9_.:-]*$")

    def render(name, value, depth):
        if not valid_name.fullmatch(name):
            raise ValueError("invalid XML element name: " + name)
        indent = "  " * depth
        if isinstance(value, list):
            return "".join(render(name, item, depth) for item in value)
        if value is None or not isinstance(value, dict):
            return f"{indent}<{name}>{'' if value is None else escape(str(value))}</{name}>\n"

        attributes = []
        children = []
        text = value.get("#text")
        for key, child in value.items():
            if key == "#text":
                continue
            if key.startswith("@"):
                attr_name = key[1:]
                if not valid_name.fullmatch(attr_name):
                    raise ValueError("invalid XML attribute name: " + attr_name)
                attributes.append(f" {attr_name}={quoteattr(str(child))}")
            else:
                children.append(render(key, child, depth + 1))

        start = f"{indent}<{name}{''.join(attributes)}"
        if not children and text in (None, ""):
            return start + "/>\n"
        if not children:
            return f"{start}>{escape(str(text))}</{name}>\n"
        text_content = "" if text in (None, "") else (
            f"{'  ' * (depth + 1)}{escape(str(text))}\n"
        )
        return f"{start}>\n{text_content}{''.join(children)}{indent}</{name}>\n"

    return '<?xml version="1.0" encoding="UTF-8"?>\n' + render(root_name, data[root_name], 0)


def write_xml_data(data, mapping):
    root_name = infer_xml_root(mapping)
    if not root_name:
        raise ValueError("XML output requires a common target root or targetRoot")
    if root_name not in data:
        data = {root_name: data}
    return to_xml(data, root_name)


register_format(
    "json",
    json_to_object,
    lambda data, mapping: json.dumps(data, indent=2, ensure_ascii=False) + "\n",
)
register_format("xml", xml_to_object, write_xml_data)
