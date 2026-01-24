package com.omnibridge.fix.dictionary;

import org.agrona.collections.Int2ObjectHashMap;
import org.agrona.collections.IntHashSet;
import org.agrona.collections.Object2ObjectHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.InputStream;
import java.util.*;

/**
 * FIX Protocol Dictionary that reads simplified XML dictionary files.
 *
 * <p>Supports importing other XML files, allowing custom dictionaries to extend
 * standard FIX definitions. The dictionary provides APIs to:</p>
 * <ul>
 *   <li>Get all tags associated with a message type</li>
 *   <li>Get the name of a tag</li>
 *   <li>Check if a tag is the start of a repeating group</li>
 *   <li>Check if a tag belongs to a repeating group</li>
 *   <li>Get enum values for a field</li>
 * </ul>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * FixDictionary dict = FixDictionary.load("FIX44.xml");
 * // Or load custom dictionary that imports standard
 * FixDictionary custom = FixDictionary.load("my-custom.xml");
 *
 * List<Integer> orderTags = dict.getMessageTags("D");
 * String tagName = dict.getTagName(55);
 * boolean isGroupStart = dict.isRepeatingGroupStart(453);
 * }</pre>
 */
public class FixDictionary {

    private static final Logger log = LoggerFactory.getLogger(FixDictionary.class);

    private final String version;
    private final Int2ObjectHashMap<FieldDef> fieldsById;
    private final Object2ObjectHashMap<String, FieldDef> fieldsByName;
    private final Object2ObjectHashMap<String, MessageDef> messages;
    private final Object2ObjectHashMap<String, GroupDef> groups;
    private final IntHashSet repeatingGroupStartTags;
    private final Int2ObjectHashMap<String> repeatingGroupMembership; // tag -> group name

    /**
     * Field definition including name, type, and enum values.
     */
    public static class FieldDef {
        private final int tag;
        private final String name;
        private final String type;
        private final Map<String, String> enumValues; // value -> description

        public FieldDef(int tag, String name, String type) {
            this.tag = tag;
            this.name = name;
            this.type = type;
            this.enumValues = new LinkedHashMap<>();
        }

        public int getTag() { return tag; }
        public String getName() { return name; }
        public String getType() { return type; }
        public Map<String, String> getEnumValues() { return Collections.unmodifiableMap(enumValues); }
        public boolean hasEnums() { return !enumValues.isEmpty(); }

        void addEnumValue(String value, String description) {
            enumValues.put(value, description);
        }

        @Override
        public String toString() {
            return "Field{" + tag + "=" + name + " (" + type + ")" +
                   (hasEnums() ? " enums=" + enumValues.size() : "") + "}";
        }
    }

    /**
     * Message definition with associated tags and groups.
     */
    public static class MessageDef {
        private final String msgType;
        private final String name;
        private final List<Integer> tags;
        private final List<String> groups; // group names used in this message

        public MessageDef(String msgType, String name) {
            this.msgType = msgType;
            this.name = name;
            this.tags = new ArrayList<>();
            this.groups = new ArrayList<>();
        }

        public String getMsgType() { return msgType; }
        public String getName() { return name; }
        public List<Integer> getTags() { return Collections.unmodifiableList(tags); }
        public List<String> getGroups() { return Collections.unmodifiableList(groups); }

        void addTag(int tag) { tags.add(tag); }
        void addGroup(String groupName) { groups.add(groupName); }

        @Override
        public String toString() {
            return "Message{" + msgType + "=" + name + ", tags=" + tags.size() +
                   ", groups=" + groups.size() + "}";
        }
    }

    /**
     * Repeating group definition with count tag and member fields.
     */
    public static class GroupDef {
        private final String name;
        private final int countTag;      // e.g., NoPartyIDs (453)
        private final int firstTag;      // first tag of group entry
        private final List<Integer> memberTags;
        private final List<String> nestedGroups;

        public GroupDef(String name, int countTag, int firstTag) {
            this.name = name;
            this.countTag = countTag;
            this.firstTag = firstTag;
            this.memberTags = new ArrayList<>();
            this.nestedGroups = new ArrayList<>();
        }

        public String getName() { return name; }
        public int getCountTag() { return countTag; }
        public int getFirstTag() { return firstTag; }
        public List<Integer> getMemberTags() { return Collections.unmodifiableList(memberTags); }
        public List<String> getNestedGroups() { return Collections.unmodifiableList(nestedGroups); }

        void addMemberTag(int tag) { memberTags.add(tag); }
        void addNestedGroup(String groupName) { nestedGroups.add(groupName); }

        @Override
        public String toString() {
            return "Group{" + name + ", count=" + countTag + ", first=" + firstTag +
                   ", members=" + memberTags.size() + "}";
        }
    }

    private FixDictionary(String version) {
        this.version = version;
        this.fieldsById = new Int2ObjectHashMap<>();
        this.fieldsByName = new Object2ObjectHashMap<>();
        this.messages = new Object2ObjectHashMap<>();
        this.groups = new Object2ObjectHashMap<>();
        this.repeatingGroupStartTags = new IntHashSet();
        this.repeatingGroupMembership = new Int2ObjectHashMap<>();
    }

    /**
     * Load a FIX dictionary from a resource file.
     *
     * @param resourceName the resource name (e.g., "FIX44.xml")
     * @return the loaded dictionary
     * @throws DictionaryLoadException if loading fails
     */
    public static FixDictionary load(String resourceName) {
        return load(resourceName, null);
    }

    /**
     * Load a FIX dictionary from a file path.
     *
     * @param file the dictionary file
     * @return the loaded dictionary
     * @throws DictionaryLoadException if loading fails
     */
    public static FixDictionary loadFromFile(File file) {
        return loadFromFile(file, null);
    }

    /**
     * Load a FIX dictionary from a resource file with a base directory for imports.
     *
     * @param resourceName the resource name
     * @param baseDir the base directory for resolving imports (null for classpath)
     * @return the loaded dictionary
     */
    public static FixDictionary load(String resourceName, File baseDir) {
        try {
            InputStream is = FixDictionary.class.getClassLoader()
                    .getResourceAsStream("fix-dictionaries/" + resourceName);
            if (is == null) {
                is = FixDictionary.class.getClassLoader().getResourceAsStream(resourceName);
            }
            if (is == null) {
                throw new DictionaryLoadException("Resource not found: " + resourceName);
            }

            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(is);
            is.close();

            return parseDocument(doc, resourceName, baseDir);
        } catch (DictionaryLoadException e) {
            throw e;
        } catch (Exception e) {
            throw new DictionaryLoadException("Failed to load dictionary: " + resourceName, e);
        }
    }

    /**
     * Load a FIX dictionary from a file with a base directory for imports.
     */
    public static FixDictionary loadFromFile(File file, File baseDir) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(file);

            if (baseDir == null) {
                baseDir = file.getParentFile();
            }

            return parseDocument(doc, file.getName(), baseDir);
        } catch (DictionaryLoadException e) {
            throw e;
        } catch (Exception e) {
            throw new DictionaryLoadException("Failed to load dictionary: " + file.getAbsolutePath(), e);
        }
    }

    private static FixDictionary parseDocument(Document doc, String sourceName, File baseDir) {
        Element root = doc.getDocumentElement();
        String version = root.getAttribute("version");

        FixDictionary dict = new FixDictionary(version);

        // Process imports first
        NodeList imports = root.getElementsByTagName("import");
        for (int i = 0; i < imports.getLength(); i++) {
            Element importEl = (Element) imports.item(i);
            String importFile = importEl.getAttribute("file");
            log.debug("Processing import: {} from {}", importFile, sourceName);

            FixDictionary imported;
            if (baseDir != null) {
                File importPath = new File(baseDir, importFile);
                if (importPath.exists()) {
                    imported = loadFromFile(importPath, baseDir);
                } else {
                    imported = load(importFile, baseDir);
                }
            } else {
                imported = load(importFile, null);
            }

            // Merge imported dictionary
            dict.merge(imported);
        }

        // Parse fields
        NodeList fields = root.getElementsByTagName("field");
        for (int i = 0; i < fields.getLength(); i++) {
            Element fieldEl = (Element) fields.item(i);
            int tag = Integer.parseInt(fieldEl.getAttribute("tag"));
            String name = fieldEl.getAttribute("name");
            String type = fieldEl.getAttribute("type");

            FieldDef field = new FieldDef(tag, name, type);

            // Parse enum values
            NodeList enums = fieldEl.getElementsByTagName("enum");
            for (int j = 0; j < enums.getLength(); j++) {
                Element enumEl = (Element) enums.item(j);
                String value = enumEl.getAttribute("value");
                String desc = enumEl.getAttribute("desc");
                field.addEnumValue(value, desc);
            }

            dict.fieldsById.put(tag, field);
            dict.fieldsByName.put(name, field);
        }

        // Parse groups
        NodeList groupsList = root.getElementsByTagName("group");
        for (int i = 0; i < groupsList.getLength(); i++) {
            Element groupEl = (Element) groupsList.item(i);
            String groupName = groupEl.getAttribute("name");
            int countTag = Integer.parseInt(groupEl.getAttribute("countTag"));
            int firstTag = Integer.parseInt(groupEl.getAttribute("firstTag"));

            GroupDef group = new GroupDef(groupName, countTag, firstTag);
            dict.repeatingGroupStartTags.add(countTag);

            // Parse member tags
            NodeList members = groupEl.getElementsByTagName("member");
            for (int j = 0; j < members.getLength(); j++) {
                Element memberEl = (Element) members.item(j);
                int memberTag = Integer.parseInt(memberEl.getAttribute("tag"));
                group.addMemberTag(memberTag);
                dict.repeatingGroupMembership.put(memberTag, groupName);
            }

            // Parse nested groups
            NodeList nestedGroups = groupEl.getElementsByTagName("nestedGroup");
            for (int j = 0; j < nestedGroups.getLength(); j++) {
                Element nestedEl = (Element) nestedGroups.item(j);
                String nestedName = nestedEl.getAttribute("name");
                group.addNestedGroup(nestedName);
            }

            dict.groups.put(groupName, group);
        }

        // Parse messages
        NodeList messagesList = root.getElementsByTagName("message");
        for (int i = 0; i < messagesList.getLength(); i++) {
            Element msgEl = (Element) messagesList.item(i);
            String msgType = msgEl.getAttribute("msgType");
            String name = msgEl.getAttribute("name");

            MessageDef message = new MessageDef(msgType, name);

            // Parse tags
            NodeList tags = msgEl.getElementsByTagName("tag");
            for (int j = 0; j < tags.getLength(); j++) {
                Element tagEl = (Element) tags.item(j);
                int tagId = Integer.parseInt(tagEl.getAttribute("id"));
                message.addTag(tagId);
            }

            // Parse group references
            NodeList groupRefs = msgEl.getElementsByTagName("groupRef");
            for (int j = 0; j < groupRefs.getLength(); j++) {
                Element groupRefEl = (Element) groupRefs.item(j);
                String groupRefName = groupRefEl.getAttribute("name");
                message.addGroup(groupRefName);
            }

            dict.messages.put(msgType, message);
        }

        log.info("Loaded FIX dictionary {}: {} fields, {} messages, {} groups",
                version, dict.fieldsById.size(), dict.messages.size(), dict.groups.size());

        return dict;
    }

    private void merge(FixDictionary other) {
        other.fieldsById.forEach((tag, field) -> {
            if (!fieldsById.containsKey(tag)) {
                fieldsById.put(tag, field);
                fieldsByName.put(field.getName(), field);
            }
        });

        other.messages.forEach((msgType, message) -> {
            if (!messages.containsKey(msgType)) {
                messages.put(msgType, message);
            }
        });

        other.groups.forEach((name, group) -> {
            if (!groups.containsKey(name)) {
                groups.put(name, group);
                repeatingGroupStartTags.add(group.getCountTag());
                group.getMemberTags().forEach(tag ->
                    repeatingGroupMembership.put(tag, name));
            }
        });
    }

    // ========== Public API ==========

    /**
     * Get the FIX version of this dictionary.
     */
    public String getVersion() {
        return version;
    }

    /**
     * Get all tags associated with a message type.
     *
     * @param msgType the message type (e.g., "D" for NewOrderSingle)
     * @return list of tag IDs, or empty list if message not found
     */
    public List<Integer> getMessageTags(String msgType) {
        MessageDef msg = messages.get(msgType);
        if (msg == null) {
            return Collections.emptyList();
        }

        List<Integer> allTags = new ArrayList<>(msg.getTags());

        // Include tags from referenced groups
        for (String groupName : msg.getGroups()) {
            GroupDef group = groups.get(groupName);
            if (group != null) {
                allTags.add(group.getCountTag());
                allTags.addAll(group.getMemberTags());
            }
        }

        return allTags;
    }

    /**
     * Get the name of a tag.
     *
     * @param tag the tag ID
     * @return the tag name, or null if not found
     */
    public String getTagName(int tag) {
        FieldDef field = fieldsById.get(tag);
        return field != null ? field.getName() : null;
    }

    /**
     * Get the tag ID by name.
     *
     * @param name the field name
     * @return the tag ID, or -1 if not found
     */
    public int getTagByName(String name) {
        FieldDef field = fieldsByName.get(name);
        return field != null ? field.getTag() : -1;
    }

    /**
     * Get field definition by tag.
     *
     * @param tag the tag ID
     * @return the field definition, or null if not found
     */
    public FieldDef getField(int tag) {
        return fieldsById.get(tag);
    }

    /**
     * Get field definition by name.
     *
     * @param name the field name
     * @return the field definition, or null if not found
     */
    public FieldDef getFieldByName(String name) {
        return fieldsByName.get(name);
    }

    /**
     * Check if a tag is the count field that starts a repeating group.
     *
     * @param tag the tag ID
     * @return true if this tag starts a repeating group
     */
    public boolean isRepeatingGroupStart(int tag) {
        return repeatingGroupStartTags.contains(tag);
    }

    /**
     * Check if a tag belongs to a repeating group element.
     *
     * @param tag the tag ID
     * @return true if this tag is a member of a repeating group
     */
    public boolean isRepeatingGroupMember(int tag) {
        return repeatingGroupMembership.containsKey(tag);
    }

    /**
     * Get the name of the repeating group that contains this tag.
     *
     * @param tag the tag ID
     * @return the group name, or null if not a group member
     */
    public String getRepeatingGroupName(int tag) {
        return repeatingGroupMembership.get(tag);
    }

    /**
     * Get the repeating group definition.
     *
     * @param name the group name
     * @return the group definition, or null if not found
     */
    public GroupDef getGroup(String name) {
        return groups.get(name);
    }

    /**
     * Get the repeating group definition by its count tag.
     *
     * @param countTag the count tag ID (e.g., 453 for NoPartyIDs)
     * @return the group definition, or null if not found
     */
    public GroupDef getGroupByCountTag(int countTag) {
        for (GroupDef group : groups.values()) {
            if (group.getCountTag() == countTag) {
                return group;
            }
        }
        return null;
    }

    /**
     * Get the message definition.
     *
     * @param msgType the message type
     * @return the message definition, or null if not found
     */
    public MessageDef getMessage(String msgType) {
        return messages.get(msgType);
    }

    /**
     * Get all message types in this dictionary.
     *
     * @return set of message type codes
     */
    public Set<String> getMessageTypes() {
        Set<String> types = new HashSet<>();
        messages.keySet().forEach(types::add);
        return types;
    }

    /**
     * Get all field definitions.
     *
     * @return collection of all fields
     */
    public Collection<FieldDef> getAllFields() {
        List<FieldDef> result = new ArrayList<>();
        fieldsById.values().forEach(result::add);
        return result;
    }

    /**
     * Get all group definitions.
     *
     * @return collection of all groups
     */
    public Collection<GroupDef> getAllGroups() {
        List<GroupDef> result = new ArrayList<>();
        groups.values().forEach(result::add);
        return result;
    }

    /**
     * Get enum values for a field.
     *
     * @param tag the tag ID
     * @return map of value to description, or empty map if no enums
     */
    public Map<String, String> getEnumValues(int tag) {
        FieldDef field = fieldsById.get(tag);
        return field != null ? field.getEnumValues() : Collections.emptyMap();
    }

    /**
     * Get the description for an enum value.
     *
     * @param tag the tag ID
     * @param value the enum value
     * @return the description, or null if not found
     */
    public String getEnumDescription(int tag, String value) {
        FieldDef field = fieldsById.get(tag);
        return field != null ? field.getEnumValues().get(value) : null;
    }

    /**
     * Exception thrown when dictionary loading fails.
     */
    public static class DictionaryLoadException extends RuntimeException {
        public DictionaryLoadException(String message) {
            super(message);
        }

        public DictionaryLoadException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
