const fs = require("node:fs");
const path = require("node:path");
const { XMLParser } = require("fast-xml-parser");

const parser = new XMLParser({
  ignoreAttributes: false,
  attributeNamePrefix: "@",
  textNodeName: "#text",
  parseAttributeValue: false,
  parseTagValue: false,
  trimValues: true,
  removeNSPrefix: true,
  processEntities: false,
});

const formatCodecs = new Map();
const validXmlName = /^[A-Za-z_][A-Za-z0-9_.:-]*$/;

function normalizeFormat(formatName) {
  return formatName.toLowerCase().replace(/^\./, "");
}

function registerFormat(formatName, reader, writer) {
  if (typeof formatName !== "string" || !formatName.trim()) {
    throw new Error("Format name must be a non-empty string");
  }
  if (typeof reader !== "function" || typeof writer !== "function") {
    throw new Error("Format reader and writer must be functions");
  }
  formatCodecs.set(normalizeFormat(formatName), { reader, writer });
}

function formatForPath(filePath) {
  const formatName = path.extname(filePath).toLowerCase().slice(1);
  if (!formatName) throw new Error("Format must be specified for paths without an extension");
  return formatName;
}

function readData(filePath, formatName) {
  const selectedFormat = normalizeFormat(formatName || formatForPath(filePath));
  const codec = formatCodecs.get(selectedFormat);
  if (!codec) throw new Error(`Unsupported input format: ${selectedFormat}`);
  return codec.reader(filePath);
}

function writeData(data, filePath, formatName, mapping) {
  const selectedFormat = normalizeFormat(formatName || (filePath ? formatForPath(filePath) : "json"));
  const codec = formatCodecs.get(selectedFormat);
  if (!codec) throw new Error(`Unsupported output format: ${selectedFormat}`);
  const rendered = codec.writer(data, mapping);
  if (filePath) {
    fs.mkdirSync(path.dirname(filePath), { recursive: true });
    fs.writeFileSync(filePath, rendered, "utf8");
  }
  return rendered;
}

function jsonToObject(jsonPath) {
  return JSON.parse(fs.readFileSync(jsonPath, "utf8"));
}

function xmlToObject(xmlPath) {
  return parser.parse(fs.readFileSync(xmlPath, "utf8"));
}

function inferXmlRoot(mapping) {
  if (mapping.targetRoot) return mapping.targetRoot;
  if (!Array.isArray(mapping.mappings) || mapping.mappings.length === 0) return null;
  const roots = mapping.mappings.map((entry) =>
    typeof entry?.targetPath === "string" ? entry.targetPath.split(".")[0] : null);
  return roots[0] && roots.every((root) => root === roots[0]) ? roots[0] : null;
}

function toXml(data, rootName) {
  if (!data || typeof data !== "object" || Array.isArray(data)
    || !Object.hasOwn(data, rootName)) {
    throw new Error(`XML data must contain the root element: ${rootName}`);
  }

  const escape = (value, attribute = false) => {
    let result = String(value).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
    if (attribute) result = result.replace(/"/g, "&quot;").replace(/'/g, "&apos;");
    return result;
  };
  const render = (name, value, depth) => {
    if (!validXmlName.test(name)) throw new Error(`Invalid XML element name: ${name}`);
    if (Array.isArray(value)) return value.map((item) => render(name, item, depth)).join("");
    const indent = "  ".repeat(depth);
    if (value === null || typeof value !== "object") {
      return `${indent}<${name}>${value === null ? "" : escape(value)}</${name}>\n`;
    }

    const attributes = [];
    const children = [];
    const text = value["#text"];
    for (const [key, child] of Object.entries(value)) {
      if (key === "#text") continue;
      if (key.startsWith("@")) {
        const attributeName = key.slice(1);
        if (!validXmlName.test(attributeName)) {
          throw new Error(`Invalid XML attribute name: ${attributeName}`);
        }
        attributes.push(` ${attributeName}="${escape(child, true)}"`);
      } else {
        children.push(render(key, child, depth + 1));
      }
    }
    const start = `${indent}<${name}${attributes.join("")}`;
    if (children.length === 0 && (text === undefined || text === null || text === "")) {
      return `${start}/>\n`;
    }
    if (children.length === 0) {
      return `${start}>${escape(text === undefined || text === null ? "" : text)}</${name}>\n`;
    }
    const textContent = text === undefined || text === null || text === ""
      ? ""
      : `${"  ".repeat(depth + 1)}${escape(text)}\n`;
    return `${start}>\n${textContent}${children.join("")}${indent}</${name}>\n`;
  };

  return `<?xml version="1.0" encoding="UTF-8"?>\n${render(rootName, data[rootName], 0)}`;
}

function writeXmlData(data, mapping) {
  const rootName = inferXmlRoot(mapping);
  if (!rootName) throw new Error("XML output requires a common target root or targetRoot");
  const xmlData = Object.hasOwn(data, rootName) ? data : { [rootName]: data };
  return toXml(xmlData, rootName);
}

registerFormat(
  "json",
  (filePath) => jsonToObject(filePath),
  (data) => `${JSON.stringify(data, null, 2)}\n`,
);
registerFormat("xml", (filePath) => xmlToObject(filePath), (data, mapping) => writeXmlData(data, mapping));

module.exports = {
  formatCodecs,
  formatForPath,
  inferXmlRoot,
  jsonToObject,
  normalizeFormat,
  readData,
  registerFormat,
  toXml,
  writeData,
  writeXmlData,
  xmlToObject,
};
