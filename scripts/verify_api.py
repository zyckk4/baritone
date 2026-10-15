#!/usr/bin/env python3
"""Compare exported JVM API descriptors and packaged Fabric/native metadata.

Local, reproducible binary checks; this does not launch Minecraft or prove addon behavior.
Only the Python standard library is required. From the repository root:
  python scripts/verify_api.py --baseline official-api-fabric.jar --candidate fork-api-fabric.jar

The inputs must be the official and fork API Fabric artifacts for Minecraft 1.21.11.
All public/protected descriptors and generic signatures must remain unchanged.
A JSON report is printed; --report optionally saves it. Exit code 1 means validation failed.
"""
from __future__ import annotations
import argparse
import hashlib
import io
import json
from pathlib import Path
import struct
import sys
import zipfile

PUBLIC, PROTECTED, STATIC, FINAL, INTERFACE, ABSTRACT = 1, 4, 8, 16, 512, 1024

class Reader:
    def __init__(self, data):
        self.data, self.offset = data, 0
    def take(self, count):
        end = self.offset + count
        if end > len(self.data):
            raise ValueError("truncated classfile")
        result = self.data[self.offset:end]
        self.offset = end
        return result
    def u1(self): return self.take(1)[0]
    def u2(self): return struct.unpack(">H", self.take(2))[0]
    def u4(self): return struct.unpack(">I", self.take(4))[0]


def parse_class(data):
    r = Reader(data)
    if r.u4() != 0xCAFEBABE:
        raise ValueError("invalid classfile magic")
    minor, major, count = r.u2(), r.u2(), r.u2()
    cp = [None] * count
    i = 1
    while i < count:
        tag = r.u1()
        if tag == 1:
            # JVM names/descriptors/attribute names used below are ASCII; other string constants
            # may contain modified UTF-8 and are not interpreted by these checks.
            cp[i] = r.take(r.u2()).decode("utf-8", errors="replace")
        elif tag in (3, 4): r.take(4)
        elif tag in (5, 6): r.take(8); i += 1
        elif tag in (7, 8, 16, 19, 20): cp[i] = (tag, r.u2())
        elif tag in (9, 10, 11, 12, 17, 18): r.take(4)
        elif tag == 15: r.take(3)
        else: raise ValueError(f"unknown constant-pool tag {tag}")
        i += 1
    def class_name(index):
        return cp[cp[index][1]] if index else None
    def attributes():
        result = {}
        for _ in range(r.u2()):
            name = cp[r.u2()]
            result[name] = r.take(r.u4())
        return result
    def members():
        result = {}
        for _ in range(r.u2()):
            access, name, descriptor = r.u2(), cp[r.u2()], cp[r.u2()]
            attrs = attributes()
            signature = cp[struct.unpack(">H", attrs["Signature"])[0]] if "Signature" in attrs else None
            result[(name, descriptor)] = {"access": access, "signature": signature}
        return result
    access, this_class, super_class = r.u2(), r.u2(), r.u2()
    interfaces = [class_name(r.u2()) for _ in range(r.u2())]
    fields, methods, attrs = members(), members(), attributes()
    if r.offset != len(data):
        raise ValueError("unparsed bytes after classfile attributes")
    signature = cp[struct.unpack(">H", attrs["Signature"])[0]] if "Signature" in attrs else None
    return {"name": class_name(this_class), "super": class_name(super_class), "interfaces": interfaces,
            "access": access, "signature": signature, "major": major, "minor": minor,
            "fields": fields, "methods": methods}


def classes_in(archive):
    result = {}
    for filename in archive.namelist():
        if filename.endswith(".class"):
            parsed = parse_class(archive.read(filename))
            if parsed["name"] in result:
                raise ValueError("duplicate class " + parsed["name"])
            result[parsed["name"]] = parsed
    return result


def sha256(data): return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", type=Path, required=True, help="official API Fabric JAR for Minecraft 1.21.11")
    parser.add_argument("--candidate", type=Path, required=True, help="fork API Fabric JAR to validate")
    parser.add_argument("--report", type=Path, help="optional path for the JSON report")
    args = parser.parse_args()
    errors, signature_changes = [], []
    crc_checks = {}
    report = {"baseline": str(args.baseline.resolve()), "candidate": str(args.candidate.resolve()),
              "errors": errors, "generic_signature_changes": signature_changes, "zip_crc_checks": crc_checks,
              "scope": "JVM classfile API/metadata comparison, not runtime addon or server validation"}
    def check(condition, message):
        if not condition: errors.append(message)
    def validate_zip(archive, label):
        names = archive.namelist()
        if len(names) != len(set(names)):
            crc_checks[label] = False
            raise ValueError(label + " contains duplicate ZIP entry names")
        damaged = archive.testzip()
        crc_checks[label] = damaged is None
        if damaged is not None:
            raise ValueError(label + " ZIP CRC check failed: " + damaged)

    def compare_signature(old, new, label, kind):
        if old != new:
            signature_changes.append({"kind": kind, "symbol": label, "before": old, "after": new})
            errors.append("changed " + kind + " generic Signature: " + label)

    def flags_compatible(old, new, label, kind):
        if old & PUBLIC: check(bool(new & PUBLIC), label + " lost public visibility")
        elif old & PROTECTED: check(bool(new & (PUBLIC | PROTECTED)), label + " lost protected visibility")
        check(bool(old & STATIC) == bool(new & STATIC), label + " changed static/instance linkage")
        if not old & FINAL: check(not bool(new & FINAL), label + " became final")
        if kind in ("class", "method") and not old & ABSTRACT:
            check(not bool(new & ABSTRACT), label + " became abstract")
        if kind == "class": check(bool(old & INTERFACE) == bool(new & INTERFACE), label + " changed class/interface kind")
    try:
        baseline_bytes, candidate_bytes = args.baseline.read_bytes(), args.candidate.read_bytes()
        report["baseline_sha256"], report["candidate_sha256"] = sha256(baseline_bytes), sha256(candidate_bytes)
        with zipfile.ZipFile(io.BytesIO(baseline_bytes)) as old_zip, zipfile.ZipFile(io.BytesIO(candidate_bytes)) as new_zip:
            validate_zip(old_zip, "baseline")
            validate_zip(new_zip, "candidate")
            old, new = classes_in(old_zip), classes_in(new_zip)
            exported = {name: cls for name, cls in old.items() if name.startswith("baritone/api/") and cls["access"] & PUBLIC}
            member_counts = {"public_fields": 0, "protected_fields": 0, "public_methods": 0, "protected_methods": 0}
            for name, cls in sorted(exported.items()):
                current = new.get(name)
                check(current is not None, "missing API class " + name)
                if current is None: continue
                flags_compatible(cls["access"], current["access"], name, "class")
                compare_signature(cls["signature"], current["signature"], name, "class")
                check(cls["super"] == current["super"], name + " changed superclass")
                check(set(cls["interfaces"]).issubset(current["interfaces"]), name + " removed implemented interface")
                for kind in ("fields", "methods"):
                    for (member_name, descriptor), member in cls[kind].items():
                        if not member["access"] & (PUBLIC | PROTECTED): continue
                        visibility = "public" if member["access"] & PUBLIC else "protected"
                        member_counts[visibility + "_" + kind] += 1
                        label = name + "." + member_name + descriptor
                        match = current[kind].get((member_name, descriptor))
                        check(match is not None, "missing exported descriptor " + label)
                        if match is None: continue
                        flags_compatible(member["access"], match["access"], label, kind[:-1])
                        compare_signature(member["signature"], match["signature"], label, kind[:-1])
            report["api"] = {"baseline_api_classfiles": sum(name.startswith("baritone/api/") for name in old),
                             "candidate_api_classfiles": sum(name.startswith("baritone/api/") for name in new),
                             "public_classes_compared": len(exported), **member_counts}
            majors = {}
            for cls in new.values(): majors[str(cls["major"])] = majors.get(str(cls["major"]), 0) + 1
            report["candidate_top_level_class_versions"] = majors
            check(set(majors) == {"65"}, "top-level classes must all target Java 21 classfile version 65: " + str(majors))
            mod = json.loads(new_zip.read("fabric.mod.json"))
            report["fabric"] = {key: mod.get(key) for key in ("id", "version", "environment")}
            report["fabric"]["minecraft"] = mod.get("depends", {}).get("minecraft")
            check(mod.get("id") == "baritone", "Fabric mod id is not baritone")
            mc = mod.get("depends", {}).get("minecraft")
            check(mc in ("1.21.11", ["1.21.11"]), "Fabric Minecraft dependency is not exactly 1.21.11")
            required_fields = {"elytraPathLookahead": "Ljava/lang/Integer;", "elytraStandingTakeoff": "Ljava/lang/Boolean;", "elytraUseCache": "Ljava/lang/Boolean;"}
            settings = new.get("baritone/api/Settings", {}).get("fields", {})
            settings_report = {}
            for name, generic_type in required_fields.items():
                match = settings.get((name, "Lbaritone/api/Settings$Setting;"))
                check(match is not None and bool(match["access"] & PUBLIC), "required setting was stripped/renamed: " + name)
                if match:
                    check(match["signature"] == "Lbaritone/api/Settings$Setting<" + generic_type + ">;", "wrong generic setting type: " + name)
                    settings_report[name] = match["signature"]
            report["required_settings"] = settings_report
            get_path = new.get("baritone/api/process/IElytraProcess", {}).get("methods", {}).get(("getPath", "()Ljava/util/List;"))
            check(get_path is not None and bool(get_path["access"] & PUBLIC), "IElytraProcess.getPath() public descriptor missing")
            if get_path:
                check(get_path["signature"] == "()Ljava/util/List<Lbaritone/api/utils/BetterBlockPos;>;", "IElytraProcess.getPath generic return type changed")
            report["getPath"] = "()Ljava/util/List;" if get_path else None
            native_path = "META-INF/jars/nether-pathfinder-1.6.jar"
            check(native_path in {item.get("file") for item in mod.get("jars", [])}, "native 1.6 nested JAR is not declared to Fabric")
            old_native_bytes, new_native_bytes = old_zip.read(native_path), new_zip.read(native_path)
            with zipfile.ZipFile(io.BytesIO(old_native_bytes)) as a, zipfile.ZipFile(io.BytesIO(new_native_bytes)) as b:
                validate_zip(a, "baseline_native")
                validate_zip(b, "candidate_native")
                a_files = {n: a.read(n) for n in a.namelist() if not n.endswith("/")}
                b_files = {n: b.read(n) for n in b.namelist() if not n.endswith("/")}
                check(a_files.keys() == b_files.keys(), "embedded native 1.6 file inventory changed")
                changed = sorted(n for n in a_files.keys() & b_files.keys() if a_files[n] != b_files[n])
                check(not changed, "embedded native 1.6 entries differ from official release: " + str(changed))
                native_mod = json.loads(b_files["fabric.mod.json"])
                check(native_mod.get("version") == "1.6", "embedded native metadata version is not 1.6")
                check("natives.zip.xz" in b_files, "embedded native binary archive missing")
                native_classes = classes_in(b)
                check(all(c["major"] <= 65 for c in native_classes.values()), "embedded native Java wrapper requires newer than Java 21")
                report["native"] = {"path": native_path, "version": native_mod.get("version"),
                                    "nested_jar_sha256": sha256(new_native_bytes),
                                    "file_count": len(b_files), "all_uncompressed_entries_match_official": a_files == b_files,
                                    "native_binary_archive_sha256": sha256(b_files["natives.zip.xz"])}
    except (OSError, ValueError, KeyError, zipfile.BadZipFile, struct.error) as exc:
        errors.append(type(exc).__name__ + ": " + str(exc))
    report["passed"] = not errors
    rendered = json.dumps(report, ensure_ascii=False, indent=2)
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(rendered + "\n", encoding="utf-8")
    print(rendered)
    return 0 if report["passed"] else 1

if __name__ == "__main__":
    sys.exit(main())
