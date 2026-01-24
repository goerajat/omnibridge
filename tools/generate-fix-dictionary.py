#!/usr/bin/env python3
"""
Generate simplified FIX dictionary XML files from the FIX Repository.

Usage:
    python generate-fix-dictionary.py <fix-repository-path> <output-dir> [versions...]

Example:
    python generate-fix-dictionary.py C:/fix_repository FIX.4.2 FIX.4.4
"""

import os
import sys
import xml.etree.ElementTree as ET
from xml.dom import minidom
from collections import defaultdict


def parse_fields(base_path):
    """Parse Fields.xml and return a dict of tag -> field info."""
    fields = {}
    tree = ET.parse(os.path.join(base_path, 'Fields.xml'))
    root = tree.getroot()

    for field in root.findall('Field'):
        tag = int(field.find('Tag').text)
        name = field.find('Name').text
        field_type = field.find('Type').text
        fields[tag] = {
            'tag': tag,
            'name': name,
            'type': field_type,
            'enums': {}
        }

    return fields


def parse_enums(base_path, fields):
    """Parse Enums.xml and add enum values to fields."""
    tree = ET.parse(os.path.join(base_path, 'Enums.xml'))
    root = tree.getroot()

    for enum in root.findall('Enum'):
        tag = int(enum.find('Tag').text)
        value = enum.find('Value').text
        symbolic_name = enum.find('SymbolicName')
        desc = enum.find('Description')

        description = symbolic_name.text if symbolic_name is not None else (desc.text if desc is not None else value)

        if tag in fields:
            fields[tag]['enums'][value] = description


def parse_messages(base_path):
    """Parse Messages.xml and return a dict of componentId -> message info."""
    messages = {}
    tree = ET.parse(os.path.join(base_path, 'Messages.xml'))
    root = tree.getroot()

    for msg in root.findall('Message'):
        component_id = msg.find('ComponentID').text
        msg_type = msg.find('MsgType').text
        name = msg.find('Name').text

        messages[component_id] = {
            'componentId': component_id,
            'msgType': msg_type,
            'name': name,
            'tags': [],
            'groups': []
        }

    return messages


def parse_components(base_path):
    """Parse Components.xml and return a dict of componentId -> component info."""
    components = {}
    tree = ET.parse(os.path.join(base_path, 'Components.xml'))
    root = tree.getroot()

    for comp in root.findall('Component'):
        component_id = comp.find('ComponentID').text
        name = comp.find('Name').text
        comp_type = comp.find('ComponentType').text

        components[component_id] = {
            'componentId': component_id,
            'name': name,
            'type': comp_type,
            'tags': [],
            'groups': []
        }

    return components


def parse_msg_contents(base_path, messages, components, fields):
    """Parse MsgContents.xml and associate tags with messages/components."""
    tree = ET.parse(os.path.join(base_path, 'MsgContents.xml'))
    root = tree.getroot()

    # Group contents by component ID
    component_contents = defaultdict(list)

    for content in root.findall('MsgContent'):
        component_id = content.find('ComponentID').text
        tag_text = content.find('TagText').text
        indent = int(content.find('Indent').text) if content.find('Indent') is not None else 0
        position = int(content.find('Position').text) if content.find('Position') is not None else 0

        component_contents[component_id].append({
            'tagText': tag_text,
            'indent': indent,
            'position': position
        })

    # Sort by position
    for comp_id in component_contents:
        component_contents[comp_id].sort(key=lambda x: x['position'])

    # Process each component's contents
    for comp_id, contents in component_contents.items():
        target = messages.get(comp_id) or components.get(comp_id)
        if target is None:
            continue

        for item in contents:
            tag_text = item['tagText']
            indent = item['indent']

            # Skip standard header/trailer
            if tag_text in ('StandardHeader', 'StandardTrailer'):
                continue

            # Check if it's a numeric tag
            if tag_text.isdigit():
                tag = int(tag_text)
                if tag in fields:
                    field = fields[tag]
                    # Check if this is a NumInGroup field (repeating group start)
                    if field['type'] == 'NumInGroup':
                        target['groups'].append({
                            'countTag': tag,
                            'countName': field['name'],
                            'indent': indent
                        })
                    else:
                        target['tags'].append({
                            'tag': tag,
                            'indent': indent
                        })
            else:
                # It's a component reference
                if tag_text in [c['name'] for c in components.values()]:
                    # Find the component and get its ID
                    ref_comp = next((c for c in components.values() if c['name'] == tag_text), None)
                    if ref_comp:
                        target['groups'].append({
                            'componentRef': ref_comp['componentId'],
                            'componentName': tag_text,
                            'indent': indent
                        })

    return component_contents


def identify_repeating_groups(components, component_contents, fields):
    """Identify repeating groups and their member fields."""
    groups = {}

    for comp_id, comp in components.items():
        if comp['type'] == 'BlockRepeating':
            contents = component_contents.get(comp_id, [])
            if not contents:
                continue

            # Find the count tag (first NumInGroup field)
            count_tag = None
            first_tag = None
            member_tags = []

            for item in contents:
                tag_text = item['tagText']
                if tag_text.isdigit():
                    tag = int(tag_text)
                    if tag in fields:
                        field = fields[tag]
                        if field['type'] == 'NumInGroup' and count_tag is None:
                            count_tag = tag
                        elif item['indent'] >= 1:  # Member of the group
                            if first_tag is None:
                                first_tag = tag
                            member_tags.append(tag)

            if count_tag and first_tag:
                groups[comp['name']] = {
                    'name': comp['name'],
                    'countTag': count_tag,
                    'firstTag': first_tag,
                    'memberTags': member_tags
                }

    return groups


def generate_xml(version, fields, messages, components, groups, output_path):
    """Generate the simplified FIX dictionary XML."""
    root = ET.Element('fix-dictionary')
    root.set('version', version)

    # Add fields section
    fields_el = ET.SubElement(root, 'fields')
    for tag in sorted(fields.keys()):
        field = fields[tag]
        field_el = ET.SubElement(fields_el, 'field')
        field_el.set('tag', str(field['tag']))
        field_el.set('name', field['name'])
        field_el.set('type', field['type'])

        # Add enums
        for value, desc in sorted(field['enums'].items()):
            enum_el = ET.SubElement(field_el, 'enum')
            enum_el.set('value', value)
            enum_el.set('desc', desc[:100] if desc else value)  # Truncate long descriptions

    # Add groups section
    groups_el = ET.SubElement(root, 'groups')
    for name in sorted(groups.keys()):
        group = groups[name]
        group_el = ET.SubElement(groups_el, 'group')
        group_el.set('name', group['name'])
        group_el.set('countTag', str(group['countTag']))
        group_el.set('firstTag', str(group['firstTag']))

        for tag in group['memberTags']:
            member_el = ET.SubElement(group_el, 'member')
            member_el.set('tag', str(tag))

    # Add messages section
    messages_el = ET.SubElement(root, 'messages')
    for comp_id in sorted(messages.keys(), key=lambda x: messages[x]['msgType']):
        msg = messages[comp_id]
        msg_el = ET.SubElement(messages_el, 'message')
        msg_el.set('msgType', msg['msgType'])
        msg_el.set('name', msg['name'])

        # Add tags
        for tag_info in msg['tags']:
            tag_el = ET.SubElement(msg_el, 'tag')
            tag_el.set('id', str(tag_info['tag']))

        # Add group references
        for group_info in msg['groups']:
            if 'countTag' in group_info:
                # Direct group (NumInGroup field)
                count_tag = group_info['countTag']
                count_name = group_info['countName']
                # Find matching group definition
                for group_name, group_def in groups.items():
                    if group_def['countTag'] == count_tag:
                        group_ref_el = ET.SubElement(msg_el, 'groupRef')
                        group_ref_el.set('name', group_name)
                        break
            elif 'componentName' in group_info:
                # Component reference
                comp_name = group_info['componentName']
                if comp_name in groups:
                    group_ref_el = ET.SubElement(msg_el, 'groupRef')
                    group_ref_el.set('name', comp_name)

    # Pretty print
    xml_str = ET.tostring(root, encoding='unicode')
    dom = minidom.parseString(xml_str)
    pretty_xml = dom.toprettyxml(indent='  ')

    # Remove extra blank lines
    lines = [line for line in pretty_xml.split('\n') if line.strip()]
    pretty_xml = '\n'.join(lines)

    with open(output_path, 'w', encoding='utf-8') as f:
        f.write(pretty_xml)

    print(f"Generated {output_path}")
    print(f"  Fields: {len(fields)}")
    print(f"  Messages: {len(messages)}")
    print(f"  Groups: {len(groups)}")


def process_version(repo_path, version, output_dir):
    """Process a single FIX version."""
    base_path = os.path.join(repo_path, version, 'Base')

    if not os.path.exists(base_path):
        print(f"Warning: {base_path} not found, skipping {version}")
        return

    print(f"\nProcessing {version}...")

    # Parse all files
    fields = parse_fields(base_path)
    parse_enums(base_path, fields)
    messages = parse_messages(base_path)
    components = parse_components(base_path)
    component_contents = parse_msg_contents(base_path, messages, components, fields)
    groups = identify_repeating_groups(components, component_contents, fields)

    # Generate output
    output_name = version.replace('.', '') + '.xml'
    output_path = os.path.join(output_dir, output_name)
    generate_xml(version, fields, messages, components, groups, output_path)


def main():
    if len(sys.argv) < 3:
        print("Usage: python generate-fix-dictionary.py <fix-repository-path> <output-dir> [versions...]")
        print("Example: python generate-fix-dictionary.py C:/fix_repository ./output FIX.4.2 FIX.4.4")
        sys.exit(1)

    repo_path = sys.argv[1]
    output_dir = sys.argv[2]
    versions = sys.argv[3:] if len(sys.argv) > 3 else ['FIX.4.0', 'FIX.4.1', 'FIX.4.2', 'FIX.4.3', 'FIX.4.4']

    # Handle nested directory structure
    inner_path = os.path.join(repo_path, os.path.basename(repo_path))
    if os.path.exists(inner_path):
        repo_path = inner_path

    if not os.path.exists(repo_path):
        print(f"Error: Repository path not found: {repo_path}")
        sys.exit(1)

    os.makedirs(output_dir, exist_ok=True)

    for version in versions:
        process_version(repo_path, version, output_dir)

    print("\nDone!")


if __name__ == '__main__':
    main()
