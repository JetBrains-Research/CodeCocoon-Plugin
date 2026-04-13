#!/usr/bin/env python3

"""
Metamorphic Text Transformer
============================================================================
Transforms problem statements and interface descriptions using LLM
to reflect renamed classes/methods from the memory file.

Usage: ./transform_metamorphic_texts.py <memory_file> <problem_statement> <interface_desc> <output_file>
============================================================================
"""

import json
import os
import sys
import requests


def load_memory_file(memory_file_path):
    """Load and parse the memory file."""
    with open(memory_file_path, 'r') as f:
        memory_data = json.load(f)
    return memory_data.get('entries', {})


def create_prompt(problem_statement, interface_desc, rename_map):
    """Create the LLM prompt."""
    # Build rename mappings string
    rename_list = []
    for old_name, new_name in rename_map.items():
        old_simple = old_name.split('.')[-1]
        rename_list.append(f"- {old_simple} -> {new_name}")

    rename_mappings = "\n".join(rename_list)

    system_prompt = """You are a technical documentation assistant helping to update code descriptions after refactoring transformations have been applied.

You will be given:
1. An original problem statement
2. An original interface description
3. A mapping of old class/method names to new names

Your task:
- Update the problem statement and interface description to use the NEW names
- Keep the meaning and structure exactly the same
- Only change the class, method, and variable names according to the mapping
- Preserve all formatting, punctuation, and sentence structure
- If a name doesn't appear in the mapping, leave it unchanged

Important:
- Do NOT add new information
- Do NOT remove information
- Do NOT rephrase or rewrite the content
- ONLY update the names that appear in the rename mapping

Respond ONLY with a JSON object in this exact format:
{
  "problemStatement": "updated problem statement here",
  "interfaceDescription": "updated interface description here"
}"""

    user_prompt = f"""## Original Problem Statement
{problem_statement}

## Original Interface Description
{interface_desc}

## Rename Mapping (OldName -> NewName)
{rename_mappings}

Now, update the problem statement and interface description with the new names.
Respond with ONLY the JSON object, no additional text."""

    return system_prompt, user_prompt


def call_grazie_api(system_prompt, user_prompt, token):
    """Call the Grazie API."""
    url = "https://api.grazie.aws.intellij.net/chat/v1/completions"

    headers = {
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/json"
    }

    payload = {
        "model": "gpt-4o",
        "messages": [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt}
        ],
        "temperature": 0.3
    }

    response = requests.post(url, headers=headers, json=payload)

    if response.status_code != 200:
        print(f"ERROR: API request failed with status {response.status_code}")
        print(response.text)
        sys.exit(1)

    response_json = response.json()
    content = response_json['choices'][0]['message']['content']

    return content


def extract_json(content):
    """Extract JSON from potential markdown code blocks."""
    # Remove markdown code blocks if present
    if '```json' in content:
        content = content.split('```json')[1].split('```')[0].strip()
    elif '```' in content:
        content = content.split('```')[1].split('```')[0].strip()

    return content.strip()


def main():
    if len(sys.argv) != 5:
        print("Usage: ./transform_metamorphic_texts.py <memory_file> <problem_statement> <interface_desc> <output_file>")
        sys.exit(1)

    memory_file = sys.argv[1]
    problem_statement = sys.argv[2]
    interface_desc = sys.argv[3]
    output_file = sys.argv[4]

    # Check if memory file exists
    if not os.path.exists(memory_file):
        print(f"ERROR: Memory file not found: {memory_file}")
        sys.exit(1)

    # Check if GRAZIE_TOKEN is set
    token = os.getenv('GRAZIE_TOKEN')
    if not token:
        print("ERROR: GRAZIE_TOKEN environment variable not set")
        sys.exit(1)

    try:
        # Load memory file
        print(f"Loading memory file: {memory_file}")
        rename_map = load_memory_file(memory_file)

        if not rename_map:
            print("WARNING: No rename entries found in memory file")
            # Return original texts unchanged
            result = {
                "problemStatement": problem_statement,
                "interfaceDescription": interface_desc
            }
            with open(output_file, 'w') as f:
                json.dump(result, f, indent=2)
            print(f"SUCCESS: Original texts written to: {output_file}")
            return

        print(f"Found {len(rename_map)} rename entries")

        # Create prompt
        system_prompt, user_prompt = create_prompt(problem_statement, interface_desc, rename_map)

        # Call API
        print("Calling Grazie API...")
        content = call_grazie_api(system_prompt, user_prompt, token)

        # Extract JSON
        json_content = extract_json(content)

        # Parse and validate
        result = json.loads(json_content)

        # Ensure required fields exist
        if 'problemStatement' not in result or 'interfaceDescription' not in result:
            print("ERROR: API response missing required fields")
            print(f"Response: {json_content}")
            sys.exit(1)

        # Write to output file
        with open(output_file, 'w') as f:
            json.dump(result, f, indent=2)

        print(f"SUCCESS: Transformed texts written to: {output_file}")

    except Exception as e:
        print(f"ERROR: {str(e)}")
        import traceback
        traceback.print_exc()
        sys.exit(1)


if __name__ == '__main__':
    main()
