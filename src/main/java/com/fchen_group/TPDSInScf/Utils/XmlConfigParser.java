package com.fchen_group.TPDSInScf.Utils;

import org.w3c.dom.*;
import javax.xml.parsers.*;
import java.io.*;
import java.util.*;

/**
 * Simple XML config parser for test parameters.
 * Reads test-config.xml and returns parameter maps for each test type.
 */
public class XmlConfigParser {

    public static class TestConfig {
        public String type;
        public boolean enabled = true;
        public String csvFile;
        public List<String> platforms = new ArrayList<>();
        public int[] memorySizes;
        public int[] threadCounts;
        public int repetitions = 30;
        public int fibN = 40;
        public String s3Bucket;
        public String s3Region;

        public int[] getMemorySizes() { return memorySizes != null ? memorySizes : new int[]{128,256,512,1024,2048}; }
        public int[] getThreadCounts() { return threadCounts != null ? threadCounts : new int[]{1,2,4,8,16}; }
    }

    public static Map<String, TestConfig> parse(String xmlPath) throws Exception {
        Map<String, TestConfig> result = new LinkedHashMap<>();
        File file = new File(xmlPath);
        if (!file.exists()) return result;

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(file);
        doc.getDocumentElement().normalize();

        NodeList testNodes = doc.getElementsByTagName("test");
        for (int i = 0; i < testNodes.getLength(); i++) {
            Element elem = (Element) testNodes.item(i);
            TestConfig cfg = new TestConfig();
            cfg.type = elem.getAttribute("type");
            cfg.enabled = !"false".equals(elem.getAttribute("enabled"));
            cfg.csvFile = getText(elem, "csvFile");
            cfg.fibN = getInt(elem, "fibN", 40);
            cfg.repetitions = getInt(elem, "repetitions", 30);
            cfg.s3Bucket = getText(elem, "s3Bucket");
            cfg.s3Region = getText(elem, "s3Region");

            String platformsStr = getText(elem, "platforms");
            if (platformsStr != null && !platformsStr.isEmpty()) {
                for (String p : platformsStr.split(",")) cfg.platforms.add(p.trim());
            }

            String memStr = getText(elem, "memorySizes");
            if (memStr != null && !memStr.isEmpty()) {
                String[] parts = memStr.split(",");
                cfg.memorySizes = new int[parts.length];
                for (int j = 0; j < parts.length; j++) cfg.memorySizes[j] = Integer.parseInt(parts[j].trim());
            }

            String threadStr = getText(elem, "threadCounts");
            if (threadStr != null && !threadStr.isEmpty()) {
                String[] parts = threadStr.split(",");
                cfg.threadCounts = new int[parts.length];
                for (int j = 0; j < parts.length; j++) cfg.threadCounts[j] = Integer.parseInt(parts[j].trim());
            }

            result.put(cfg.type, cfg);
        }
        return result;
    }

    private static String getText(Element parent, String tagName) {
        NodeList list = parent.getElementsByTagName(tagName);
        if (list.getLength() == 0) return null;
        Node node = list.item(0);
        return node != null ? node.getTextContent().trim() : null;
    }

    private static int getInt(Element parent, String tagName, int defaultVal) {
        String text = getText(parent, tagName);
        if (text == null || text.isEmpty()) return defaultVal;
        return Integer.parseInt(text);
    }
}
