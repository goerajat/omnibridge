package com.omnibridge.fix.dictionary;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.File;
import java.io.FileWriter;
import java.util.*;

/**
 * Generates simplified FIX dictionary XML files from the FIX Repository.
 *
 * <p>Usage:</p>
 * <pre>
 * java -cp fix-dictionary.jar com.fixengine.dictionary.FixDictionaryGenerator \
 *     /path/to/fix_repository /path/to/output FIX.4.2 FIX.4.4
 * </pre>
 */
public class FixDictionaryGenerator {

    private final Map<Integer, FieldInfo> fields = new TreeMap<>();
    private final Map<String, MessageInfo> messages = new TreeMap<>();
    private final Map<String, ComponentInfo> components = new HashMap<>();
    private final Map<String, GroupInfo> groups = new TreeMap<>();
    private final Map<String, List<ContentInfo>> componentContents = new HashMap<>();

    static class FieldInfo {
        int tag;
        String name;
        String type;
        Map<String, String> enums = new LinkedHashMap<>();

        FieldInfo(int tag, String name, String type) {
            this.tag = tag;
            this.name = name;
            this.type = type;
        }
    }

    static class MessageInfo {
        String componentId;
        String msgType;
        String name;
        List<Integer> tags = new ArrayList<>();
        List<String> groupRefs = new ArrayList<>();

        MessageInfo(String componentId, String msgType, String name) {
            this.componentId = componentId;
            this.msgType = msgType;
            this.name = name;
        }
    }

    static class ComponentInfo {
        String componentId;
        String name;
        String type;

        ComponentInfo(String componentId, String name, String type) {
            this.componentId = componentId;
            this.name = name;
            this.type = type;
        }
    }

    static class ContentInfo {
        String tagText;
        int indent;
        int position;

        ContentInfo(String tagText, int indent, int position) {
            this.tagText = tagText;
            this.indent = indent;
            this.position = position;
        }
    }

    static class GroupInfo {
        String name;
        int countTag;
        int firstTag;
        List<Integer> memberTags = new ArrayList<>();

        GroupInfo(String name, int countTag, int firstTag) {
            this.name = name;
            this.countTag = countTag;
            this.firstTag = firstTag;
        }
    }

    public void parseRepository(File basePath) throws Exception {
        System.out.println("Parsing repository at: " + basePath.getAbsolutePath());

        parseFields(new File(basePath, "Fields.xml"));
        parseEnums(new File(basePath, "Enums.xml"));
        parseMessages(new File(basePath, "Messages.xml"));
        parseComponents(new File(basePath, "Components.xml"));
        parseMsgContents(new File(basePath, "MsgContents.xml"));
        identifyGroups();
        associateTagsWithMessages();
    }

    private void parseFields(File file) throws Exception {
        Document doc = parseXml(file);
        NodeList fieldNodes = doc.getElementsByTagName("Field");

        for (int i = 0; i < fieldNodes.getLength(); i++) {
            Element el = (Element) fieldNodes.item(i);
            int tag = Integer.parseInt(getChildText(el, "Tag"));
            String name = getChildText(el, "Name");
            String type = getChildText(el, "Type");
            fields.put(tag, new FieldInfo(tag, name, type));
        }
        System.out.println("  Parsed " + fields.size() + " fields");
    }

    private void parseEnums(File file) throws Exception {
        Document doc = parseXml(file);
        NodeList enumNodes = doc.getElementsByTagName("Enum");

        int enumCount = 0;
        for (int i = 0; i < enumNodes.getLength(); i++) {
            Element el = (Element) enumNodes.item(i);
            int tag = Integer.parseInt(getChildText(el, "Tag"));
            String value = getChildText(el, "Value");
            String symbolicName = getChildText(el, "SymbolicName");
            String desc = getChildText(el, "Description");

            String description = symbolicName != null ? symbolicName : (desc != null ? desc : value);
            // Truncate long descriptions
            if (description != null && description.length() > 100) {
                description = description.substring(0, 100);
            }

            FieldInfo field = fields.get(tag);
            if (field != null) {
                field.enums.put(value, description);
                enumCount++;
            }
        }
        System.out.println("  Parsed " + enumCount + " enum values");
    }

    private void parseMessages(File file) throws Exception {
        Document doc = parseXml(file);
        NodeList msgNodes = doc.getElementsByTagName("Message");

        for (int i = 0; i < msgNodes.getLength(); i++) {
            Element el = (Element) msgNodes.item(i);
            String componentId = getChildText(el, "ComponentID");
            String msgType = getChildText(el, "MsgType");
            String name = getChildText(el, "Name");
            messages.put(componentId, new MessageInfo(componentId, msgType, name));
        }
        System.out.println("  Parsed " + messages.size() + " messages");
    }

    private void parseComponents(File file) throws Exception {
        Document doc = parseXml(file);
        NodeList compNodes = doc.getElementsByTagName("Component");

        for (int i = 0; i < compNodes.getLength(); i++) {
            Element el = (Element) compNodes.item(i);
            String componentId = getChildText(el, "ComponentID");
            String name = getChildText(el, "Name");
            String type = getChildText(el, "ComponentType");
            components.put(componentId, new ComponentInfo(componentId, name, type));
        }
        System.out.println("  Parsed " + components.size() + " components");
    }

    private void parseMsgContents(File file) throws Exception {
        Document doc = parseXml(file);
        NodeList contentNodes = doc.getElementsByTagName("MsgContent");

        for (int i = 0; i < contentNodes.getLength(); i++) {
            Element el = (Element) contentNodes.item(i);
            String componentId = getChildText(el, "ComponentID");
            String tagText = getChildText(el, "TagText");
            String indentStr = getChildText(el, "Indent");
            String posStr = getChildText(el, "Position");

            int indent = indentStr != null ? (int) Double.parseDouble(indentStr) : 0;
            int position = posStr != null ? (int) Double.parseDouble(posStr) : 0;

            componentContents.computeIfAbsent(componentId, k -> new ArrayList<>())
                    .add(new ContentInfo(tagText, indent, position));
        }

        // Sort by position
        for (List<ContentInfo> contents : componentContents.values()) {
            contents.sort(Comparator.comparingInt(c -> c.position));
        }
        System.out.println("  Parsed message contents for " + componentContents.size() + " components");
    }

    private void identifyGroups() {
        for (Map.Entry<String, ComponentInfo> entry : components.entrySet()) {
            ComponentInfo comp = entry.getValue();
            if (!"BlockRepeating".equals(comp.type)) {
                continue;
            }

            List<ContentInfo> contents = componentContents.get(comp.componentId);
            if (contents == null || contents.isEmpty()) {
                continue;
            }

            Integer countTag = null;
            Integer firstTag = null;
            List<Integer> memberTags = new ArrayList<>();

            for (ContentInfo content : contents) {
                if (content.tagText == null) continue;

                // Skip component references
                if (!content.tagText.matches("\\d+")) {
                    continue;
                }

                int tag = Integer.parseInt(content.tagText);
                FieldInfo field = fields.get(tag);
                if (field == null) continue;

                if ("NumInGroup".equals(field.type) && countTag == null) {
                    countTag = tag;
                } else if (content.indent >= 1) {
                    if (firstTag == null) {
                        firstTag = tag;
                    }
                    memberTags.add(tag);
                }
            }

            if (countTag != null && firstTag != null) {
                GroupInfo group = new GroupInfo(comp.name, countTag, firstTag);
                group.memberTags.addAll(memberTags);
                groups.put(comp.name, group);
            }
        }
        System.out.println("  Identified " + groups.size() + " repeating groups");
    }

    private void associateTagsWithMessages() {
        for (Map.Entry<String, MessageInfo> entry : messages.entrySet()) {
            MessageInfo msg = entry.getValue();
            List<ContentInfo> contents = componentContents.get(msg.componentId);
            if (contents == null) continue;

            for (ContentInfo content : contents) {
                if (content.tagText == null) continue;

                // Skip standard header/trailer
                if ("StandardHeader".equals(content.tagText) ||
                    "StandardTrailer".equals(content.tagText)) {
                    continue;
                }

                // Check if numeric tag
                if (content.tagText.matches("\\d+")) {
                    int tag = Integer.parseInt(content.tagText);
                    FieldInfo field = fields.get(tag);
                    if (field != null) {
                        if ("NumInGroup".equals(field.type)) {
                            // Find the group with this count tag
                            for (GroupInfo group : groups.values()) {
                                if (group.countTag == tag) {
                                    if (!msg.groupRefs.contains(group.name)) {
                                        msg.groupRefs.add(group.name);
                                    }
                                    break;
                                }
                            }
                        } else {
                            msg.tags.add(tag);
                        }
                    }
                } else {
                    // Component reference - check if it's a group
                    if (groups.containsKey(content.tagText)) {
                        if (!msg.groupRefs.contains(content.tagText)) {
                            msg.groupRefs.add(content.tagText);
                        }
                    }
                }
            }
        }
    }

    public void generateXml(String version, File outputFile) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.newDocument();

        Element root = doc.createElement("fix-dictionary");
        root.setAttribute("version", version);
        doc.appendChild(root);

        // Fields section
        Element fieldsEl = doc.createElement("fields");
        root.appendChild(fieldsEl);

        for (FieldInfo field : fields.values()) {
            Element fieldEl = doc.createElement("field");
            fieldEl.setAttribute("tag", String.valueOf(field.tag));
            fieldEl.setAttribute("name", field.name);
            fieldEl.setAttribute("type", field.type);

            for (Map.Entry<String, String> enumEntry : field.enums.entrySet()) {
                Element enumEl = doc.createElement("enum");
                enumEl.setAttribute("value", enumEntry.getKey());
                enumEl.setAttribute("desc", enumEntry.getValue() != null ? enumEntry.getValue() : enumEntry.getKey());
                fieldEl.appendChild(enumEl);
            }

            fieldsEl.appendChild(fieldEl);
        }

        // Groups section
        Element groupsEl = doc.createElement("groups");
        root.appendChild(groupsEl);

        for (GroupInfo group : groups.values()) {
            Element groupEl = doc.createElement("group");
            groupEl.setAttribute("name", group.name);
            groupEl.setAttribute("countTag", String.valueOf(group.countTag));
            groupEl.setAttribute("firstTag", String.valueOf(group.firstTag));

            for (int tag : group.memberTags) {
                Element memberEl = doc.createElement("member");
                memberEl.setAttribute("tag", String.valueOf(tag));
                groupEl.appendChild(memberEl);
            }

            groupsEl.appendChild(groupEl);
        }

        // Messages section
        Element messagesEl = doc.createElement("messages");
        root.appendChild(messagesEl);

        // Sort by message type
        List<MessageInfo> sortedMessages = new ArrayList<>(messages.values());
        sortedMessages.sort(Comparator.comparing(m -> m.msgType));

        for (MessageInfo msg : sortedMessages) {
            Element msgEl = doc.createElement("message");
            msgEl.setAttribute("msgType", msg.msgType);
            msgEl.setAttribute("name", msg.name);

            for (int tag : msg.tags) {
                Element tagEl = doc.createElement("tag");
                tagEl.setAttribute("id", String.valueOf(tag));
                msgEl.appendChild(tagEl);
            }

            for (String groupRef : msg.groupRefs) {
                Element groupRefEl = doc.createElement("groupRef");
                groupRefEl.setAttribute("name", groupRef);
                msgEl.appendChild(groupRefEl);
            }

            messagesEl.appendChild(msgEl);
        }

        // Write to file
        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        Transformer transformer = transformerFactory.newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");

        DOMSource source = new DOMSource(doc);
        StreamResult result = new StreamResult(outputFile);
        transformer.transform(source, result);

        System.out.println("Generated: " + outputFile.getAbsolutePath());
        System.out.println("  Fields: " + fields.size());
        System.out.println("  Messages: " + messages.size());
        System.out.println("  Groups: " + groups.size());
    }

    private Document parseXml(File file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(file);
    }

    private String getChildText(Element parent, String childName) {
        NodeList nodes = parent.getElementsByTagName(childName);
        if (nodes.getLength() > 0) {
            return nodes.item(0).getTextContent();
        }
        return null;
    }

    public void clear() {
        fields.clear();
        messages.clear();
        components.clear();
        groups.clear();
        componentContents.clear();
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Usage: FixDictionaryGenerator <fix-repository-path> <output-dir> [versions...]");
            System.out.println("Example: FixDictionaryGenerator /path/to/fix_repository ./output FIX.4.2 FIX.4.4");
            System.exit(1);
        }

        String repoPath = args[0];
        String outputDir = args[1];
        String[] versions = args.length > 2 ?
                Arrays.copyOfRange(args, 2, args.length) :
                new String[]{"FIX.4.0", "FIX.4.1", "FIX.4.2", "FIX.4.3", "FIX.4.4"};

        // Handle nested directory structure
        File repoDir = new File(repoPath);
        File innerDir = new File(repoDir, repoDir.getName());
        if (innerDir.exists()) {
            repoDir = innerDir;
        }

        File outputDirFile = new File(outputDir);
        outputDirFile.mkdirs();

        FixDictionaryGenerator generator = new FixDictionaryGenerator();

        for (String version : versions) {
            File basePath = new File(repoDir, version + "/Base");
            if (!basePath.exists()) {
                System.out.println("Warning: " + basePath + " not found, skipping " + version);
                continue;
            }

            System.out.println("\nProcessing " + version + "...");
            generator.clear();
            generator.parseRepository(basePath);

            String outputName = version.replace(".", "") + ".xml";
            File outputFile = new File(outputDirFile, outputName);
            generator.generateXml(version, outputFile);
        }

        System.out.println("\nDone!");
    }
}
