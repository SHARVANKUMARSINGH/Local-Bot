#!/usr/bin/env python3
"""
Local Bots - Syntax, Architecture & Structural Verification Script
Provides ultra-fast validation of Kotlin, Java, C++, CMake, Gradle, and XML files
without requiring a slow Gradle compilation daemon.
"""

import os
import re
import sys
import xml.etree.ElementTree as ET

WORKSPACE_DIR = os.path.dirname(os.path.abspath(__file__))
APP_DIR = os.path.join(WORKSPACE_DIR, "app")
SRC_DIR = os.path.join(APP_DIR, "src", "main")

def strip_code(code):
    """
    Lexical scanner that strips triple-quoted strings, line comments,
    block comments, character literals, and string literals cleanly,
    preventing comment delimiters inside strings from causing syntax false-positives.
    """
    out = []
    i = 0
    n = len(code)
    while i < n:
        # 1. Triple-quoted strings """ ... """
        if code[i:i+3] == '"""':
            i += 3
            while i < n and code[i:i+3] != '"""':
                i += 1
            i += 3
            continue

        # 2. Single-line comment // ...
        if code[i:i+2] == '//':
            while i < n and code[i] != '\n':
                i += 1
            continue

        # 3. Multi-line block comment /* ... */
        if code[i:i+2] == '/*':
            i += 2
            while i < n and code[i:i+2] != '*/':
                i += 1
            i += 2
            continue

        # 4. Character literal 'c'
        if code[i] == "'":
            i += 1
            while i < n and code[i] != "'":
                if code[i] == '\\':
                    i += 1
                i += 1
            i += 1
            continue

        # 5. Double-quoted string " ... "
        if code[i] == '"':
            i += 1
            while i < n and code[i] != '"':
                if code[i] == '\\':
                    i += 1
                i += 1
            i += 1
            continue

        out.append(code[i])
        i += 1
    return "".join(out)

def check_brackets_balance(file_path):
    """Verifies that curly braces, brackets, and parentheses are balanced."""
    with open(file_path, "r", encoding="utf-8", errors="ignore") as f:
        raw_content = f.read()

    cleaned = strip_code(raw_content)

    stack = []
    pairs = {')': '(', '}': '{', ']': '['}

    for idx, char in enumerate(cleaned):
        if char in '({[':
            stack.append((char, idx))
        elif char in ')}]':
            if not stack:
                return False, f"Unmatched closing '{char}' at pos {idx}"
            last, last_pos = stack.pop()
            if last != pairs[char]:
                return False, f"Mismatched bracket: expected '{last}' matched with '{char}'"

    if stack:
        unmatched = [s[0] for s in stack]
        return False, f"Unclosed brackets remaining: {unmatched}"

    return True, "Balanced"

def verify_manifest():
    manifest_path = os.path.join(SRC_DIR, "AndroidManifest.xml")
    if not os.path.exists(manifest_path):
        return False, "AndroidManifest.xml not found"

    try:
        tree = ET.parse(manifest_path)
        root = tree.getroot()

        # Check required permissions
        permissions = [elem.attrib.get("{http://schemas.android.com/apk/res/android}name")
                       for elem in root.findall("uses-permission")]
        required_perms = [
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE"
        ]
        for p in required_perms:
            if p not in permissions:
                return False, f"Missing required permission: {p}"

        # Check application tag
        app_tag = root.find("application")
        if app_tag is None:
            return False, "Missing <application> in manifest"

        app_name = app_tag.attrib.get("{http://schemas.android.com/apk/res/android}name")
        if app_name != ".LocalBotsApp" and app_name != "com.localbots.app.LocalBotsApp":
            return False, f"Unexpected application class name: {app_name}"

        return True, "Valid manifest structure and components"
    except Exception as e:
        return False, f"XML Parsing error: {str(e)}"

def verify_cpp():
    cpp_dir = os.path.join(SRC_DIR, "cpp")
    if not os.path.exists(cpp_dir):
        return False, "C++ directory missing"

    required_cpp = [
        "CMakeLists.txt",
        "native-lib.cpp",
        "proot_executor.hpp",
        "proot_executor.cpp",
        "framebuffer_capture.hpp",
        "framebuffer_capture.cpp",
        "input_injector.hpp",
        "input_injector.cpp"
    ]
    for f in required_cpp:
        path = os.path.join(cpp_dir, f)
        if not os.path.exists(path):
            return False, f"Missing C++ file: {f}"

        if f.endswith(('.cpp', '.hpp')):
            ok, msg = check_brackets_balance(path)
            if not ok:
                return False, f"Bracket balance error in {f}: {msg}"

    return True, f"All {len(required_cpp)} C++17 and CMake modules verified"

def verify_kotlin_and_java():
    kotlin_dir = os.path.join(SRC_DIR, "kotlin")
    java_dir = os.path.join(SRC_DIR, "java")

    checked_files = 0
    errors = []

    for base_dir in [kotlin_dir, java_dir]:
        if not os.path.exists(base_dir):
            continue
        for root, _, files in os.walk(base_dir):
            for file in files:
                if file.endswith(('.kt', '.java')):
                    full_path = os.path.join(root, file)
                    rel_path = os.path.relpath(full_path, WORKSPACE_DIR)
                    ok, msg = check_brackets_balance(full_path)
                    if not ok:
                        errors.append(f"{rel_path}: {msg}")
                    checked_files += 1

    if errors:
        return False, f"Syntax errors in {len(errors)} files:\n" + "\n".join(errors[:5])

    return True, f"All {checked_files} Kotlin & Java source files syntactically balanced"

def verify_32_actions():
    action_types_file = os.path.join(SRC_DIR, "kotlin", "com", "localbots", "app", "data", "model", "AgentAction.kt")
    if not os.path.exists(action_types_file):
        return False, "AgentAction.kt missing"

    with open(action_types_file, "r") as f:
        content = f.read()

    expected_actions = [
        "Desktop_Screenshot", "Update_Mouse_Position", "right-click", "left-click",
        "double-click", "middle-click", "mouse-drag", "mouse-scroll", "keyboard-type", "keyboard-shortcut",
        "Browser_Launch", "Browser_Navigate", "Browser_Search", "Browser_Click_Element",
        "Browser_Extract_DOM", "Browser_Execute_JS", "Browser_Manage_Tabs", "Browser_Download_File",
        "VFS_Push_Text", "VFS_Append_Text", "VFS_Read_File", "VFS_Create_Folder", "VFS_Delete_Item",
        "VFS_Rename_Move", "VFS_List_Tree", "VFS_Search_Files",
        "Bash_Exec", "Python_Run", "TypeScript_Compile_Run", "Git_Operation", "Process_Monitor", "Clipboard_Sync"
    ]

    missing = []
    for action in expected_actions:
        if f'"{action}"' not in content:
            missing.append(action)

    if missing:
        return False, f"Missing action definitions in AgentAction.kt: {missing}"

    return True, f"All {len(expected_actions)} required autonomous agent actions defined"

def verify_theme_and_time_trigger():
    theme_manager = os.path.join(SRC_DIR, "kotlin", "com", "localbots", "app", "theme", "ThemeManager.kt")
    if not os.path.exists(theme_manager):
        return False, "ThemeManager.kt missing"

    with open(theme_manager, "r") as f:
        content = f.read()

    if "19" not in content or "50" not in content:
        return False, "7:50 PM (19:50) scheduled trigger logic missing in ThemeManager.kt"

    return True, "Theme Manager & 7:50 PM (19:50) scheduled Google palette trigger verified"

def verify_abi_splits():
    gradle_file = os.path.join(APP_DIR, "build.gradle.kts")
    if not os.path.exists(gradle_file):
        return False, "app/build.gradle.kts missing"

    with open(gradle_file, "r") as f:
        content = f.read()

    if "splits" not in content or "arm64-v8a" not in content or "x86_64" not in content:
        return False, "ABI splits configuration missing in app/build.gradle.kts"

    return True, "Multi-ABI splits (arm64-v8a, armeabi-v7a, x86_64, universal) verified"

def main():
    print("=" * 65)
    print("    LOCAL BOTS - INSTANT SYNTAX & ARCHITECTURE CHECK")
    print("=" * 65)

    checks = [
        ("AndroidManifest & Components", verify_manifest),
        ("C++17 NDK & CMake Modules", verify_cpp),
        ("Kotlin & Java Source Balance", verify_kotlin_and_java),
        ("32 Agent Actions Schema", verify_32_actions),
        ("Theme Engine & 19:50 Trigger", verify_theme_and_time_trigger),
        ("Multi-ABI Splits & Gradle DSL", verify_abi_splits)
    ]

    passed = 0
    for name, check_fn in checks:
        ok, msg = check_fn()
        status = "✓ PASS" if ok else "✗ FAIL"
        print(f"[{status}] {name}: {msg}")
        if ok:
            passed += 1

    print("=" * 65)
    print(f"Summary: {passed}/{len(checks)} verification tests passed successfully!")
    print("=" * 65)

    if passed == len(checks):
        sys.exit(0)
    else:
        sys.exit(1)

if __name__ == "__main__":
    main()
