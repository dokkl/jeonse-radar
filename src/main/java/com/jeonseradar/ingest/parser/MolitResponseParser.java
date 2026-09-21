package com.jeonseradar.ingest.parser;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class MolitResponseParser {

    public static class ParseException extends RuntimeException {
        public ParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public ParsedResponse parse(String xml) {
        Document doc = toDocument(xml);

        String rawCode = firstText(doc, "resultCode");
        if (rawCode == null) {
            rawCode = firstText(doc, "returnReasonCode");
        }
        ResultCode code = ResultCode.from(rawCode);

        int totalCount = parseIntOrZero(firstText(doc, "totalCount"));
        List<Map<String, String>> items = extractItems(doc);

        return new ParsedResponse(code, rawCode, totalCount, items);
    }

    private Document toDocument(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            return factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new ParseException("공공데이터 응답 XML 파싱 실패", e);
        }
    }

    private List<Map<String, String>> extractItems(Document doc) {
        List<Map<String, String>> items = new ArrayList<>();
        NodeList itemNodes = doc.getElementsByTagName("item");

        for (int i = 0; i < itemNodes.getLength(); i++) {
            Node itemNode = itemNodes.item(i);
            if (itemNode.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Map<String, String> fields = new LinkedHashMap<>();
            NodeList children = itemNode.getChildNodes();
            for (int j = 0; j < children.getLength(); j++) {
                Node child = children.item(j);
                if (child.getNodeType() == Node.ELEMENT_NODE) {
                    String value = child.getTextContent();
                    fields.put(child.getNodeName(), value == null ? "" : value.trim());
                }
            }
            items.add(fields);
        }
        return items;
    }

    private String firstText(Document doc, String tagName) {
        NodeList nodes = doc.getElementsByTagName(tagName);
        if (nodes.getLength() == 0) {
            return null;
        }
        Element element = (Element) nodes.item(0);
        String text = element.getTextContent();
        return text == null ? null : text.trim();
    }

    private int parseIntOrZero(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
