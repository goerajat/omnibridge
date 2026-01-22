# FIX Dictionary XML Format

This document describes the simplified FIX dictionary XML format used by the FIX engine. The format supports:

- Field definitions with tag ID, name, type, and enum values
- Repeating group definitions
- Message definitions with associated tags and groups
- **Import support** for extending standard dictionaries with custom fields

## XML Schema

### Root Element

```xml
<fix-dictionary version="FIX.4.4">
  <import file="FIX44.xml"/>  <!-- Optional imports -->
  <fields>...</fields>
  <groups>...</groups>
  <messages>...</messages>
</fix-dictionary>
```

### Fields Section

Define fields with tag ID, name, type, and optional enum values:

```xml
<fields>
  <field tag="35" name="MsgType" type="String">
    <enum value="D" desc="NewOrderSingle"/>
    <enum value="8" desc="ExecutionReport"/>
    <enum value="9" desc="OrderCancelReject"/>
  </field>
  <field tag="55" name="Symbol" type="String"/>
  <field tag="54" name="Side" type="char">
    <enum value="1" desc="Buy"/>
    <enum value="2" desc="Sell"/>
  </field>
  <field tag="38" name="OrderQty" type="Qty"/>
  <field tag="44" name="Price" type="Price"/>
</fields>
```

**Field Attributes:**
- `tag` (required): The FIX tag number
- `name` (required): The field name
- `type` (required): The data type (String, char, int, Price, Qty, etc.)

**Enum Attributes:**
- `value` (required): The enum value
- `desc` (required): Description of the value

### Groups Section

Define repeating groups with count tag, first tag, and member tags:

```xml
<groups>
  <group name="Parties" countTag="453" firstTag="448">
    <member tag="448"/>  <!-- PartyID -->
    <member tag="447"/>  <!-- PartyIDSource -->
    <member tag="452"/>  <!-- PartyRole -->
    <nestedGroup name="PtysSubGrp"/>  <!-- Optional nested groups -->
  </group>

  <group name="NoAllocs" countTag="78" firstTag="79">
    <member tag="79"/>   <!-- AllocAccount -->
    <member tag="661"/>  <!-- AllocAcctIDSource -->
    <member tag="80"/>   <!-- AllocQty -->
  </group>
</groups>
```

**Group Attributes:**
- `name` (required): The group name
- `countTag` (required): The NumInGroup field that indicates count
- `firstTag` (required): The first field in each group entry

**Member Attributes:**
- `tag` (required): Tag ID of the group member field

### Messages Section

Define messages with associated tags and group references:

```xml
<messages>
  <message msgType="D" name="NewOrderSingle">
    <tag id="11"/>   <!-- ClOrdID -->
    <tag id="55"/>   <!-- Symbol -->
    <tag id="54"/>   <!-- Side -->
    <tag id="38"/>   <!-- OrderQty -->
    <tag id="44"/>   <!-- Price -->
    <tag id="40"/>   <!-- OrdType -->
    <groupRef name="Parties"/>
  </message>

  <message msgType="8" name="ExecutionReport">
    <tag id="37"/>   <!-- OrderID -->
    <tag id="17"/>   <!-- ExecID -->
    <tag id="150"/>  <!-- ExecType -->
    <tag id="39"/>   <!-- OrdStatus -->
    <groupRef name="Parties"/>
  </message>
</messages>
```

**Message Attributes:**
- `msgType` (required): The FIX message type code
- `name` (required): The message name

**Tag Attributes:**
- `id` (required): The tag ID

**GroupRef Attributes:**
- `name` (required): Reference to a group defined in the groups section

## Import Support

Custom dictionaries can import standard FIX dictionaries and add/override definitions:

### Example: Custom Dictionary

```xml
<?xml version="1.0" encoding="UTF-8"?>
<fix-dictionary version="CUSTOM-FIX.4.4">
  <!-- Import standard FIX 4.4 dictionary -->
  <import file="FIX44.xml"/>

  <!-- Add custom fields -->
  <fields>
    <field tag="9001" name="CustomField1" type="String"/>
    <field tag="9002" name="CustomOrderType" type="char">
      <enum value="A" desc="AlgoOrder"/>
      <enum value="B" desc="BracketOrder"/>
    </field>
  </fields>

  <!-- Add custom groups -->
  <groups>
    <group name="CustomAllocs" countTag="9010" firstTag="9011">
      <member tag="9011"/>
      <member tag="9012"/>
    </group>
  </groups>

  <!-- Add custom messages or extend existing ones -->
  <messages>
    <message msgType="UD1" name="CustomOrder">
      <tag id="11"/>     <!-- ClOrdID -->
      <tag id="55"/>     <!-- Symbol -->
      <tag id="9001"/>   <!-- CustomField1 -->
      <tag id="9002"/>   <!-- CustomOrderType -->
      <groupRef name="CustomAllocs"/>
    </message>
  </messages>
</fix-dictionary>
```

### Import Resolution

When loading a dictionary with imports:

1. Imports are processed first, in order of appearance
2. Imported definitions are merged into the dictionary
3. Local definitions override imported ones (same tag/name)
4. Import paths are resolved relative to:
   - The directory containing the importing file (if loading from file)
   - The classpath `fix-dictionaries/` directory (if loading from resources)

## Java API Usage

### Loading a Dictionary

```java
// Load from classpath resources
FixDictionary dict = FixDictionary.load("FIX44.xml");

// Load custom dictionary that imports standard
FixDictionary custom = FixDictionary.load("my-custom.xml");

// Load from file system
FixDictionary fileDict = FixDictionary.loadFromFile(new File("/path/to/dict.xml"));
```

### Querying Fields

```java
// Get field by tag
FixDictionary.FieldDef field = dict.getField(55);
System.out.println(field.getName());  // "Symbol"
System.out.println(field.getType());  // "String"

// Get tag by name
int tag = dict.getTagByName("Symbol");  // 55

// Get all enum values for a field
Map<String, String> sideEnums = dict.getEnumValues(54);
// {"1" -> "Buy", "2" -> "Sell", ...}
```

### Querying Messages

```java
// Get tags for a message type
List<Integer> orderTags = dict.getMessageTags("D");  // NewOrderSingle

// Get message definition
FixDictionary.MessageDef msg = dict.getMessage("D");
System.out.println(msg.getName());     // "NewOrderSingle"
System.out.println(msg.getTags());     // [11, 55, 54, 38, ...]
System.out.println(msg.getGroups());   // ["Parties", ...]

// Get all message types
Set<String> msgTypes = dict.getMessageTypes();
```

### Querying Groups

```java
// Check if a tag starts a repeating group
boolean isGroupStart = dict.isRepeatingGroupStart(453);  // true (NoPartyIDs)

// Check if a tag is a member of a repeating group
boolean isMember = dict.isRepeatingGroupMember(448);  // true (PartyID)

// Get the group name for a member tag
String groupName = dict.getRepeatingGroupName(448);  // "Parties"

// Get group definition
FixDictionary.GroupDef group = dict.getGroup("Parties");
System.out.println(group.getCountTag());   // 453
System.out.println(group.getFirstTag());   // 448
System.out.println(group.getMemberTags()); // [448, 447, 452]

// Get group by count tag
GroupDef parties = dict.getGroupByCountTag(453);
```

## Standard Dictionaries

The following standard dictionaries are included:

| File | Version | Fields | Messages | Groups |
|------|---------|--------|----------|--------|
| FIX42.xml | FIX.4.2 | 405 | 46 | - |
| FIX44.xml | FIX.4.4 | 912 | 93 | 11 |

## Generating Dictionaries

To generate dictionary files from the official FIX Repository:

```bash
# From the connectivity project root
mvn exec:java -pl fix-config \
  -Dexec.mainClass="com.fixengine.config.dictionary.FixDictionaryGenerator" \
  -Dexec.args="/path/to/fix_repository ./output FIX.4.2 FIX.4.4"
```

This will parse the FIX Repository XML files and generate simplified dictionary files.
