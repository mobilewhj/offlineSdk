#!/usr/bin/env python3
"""比较本轮固定候选的公开 JVM 签名及源码身份；不代替完整 ABI 验证。"""

from collections import Counter
from datetime import datetime, timezone
from hashlib import sha256
from pathlib import Path
import difflib
import json
import re
import shlex
import subprocess
import zipfile


ROOT = Path(__file__).resolve().parents[3]
REPORT = ROOT / "build/reports/main-flow-refactor"
BASELINE = ROOT / "build/reports/main-flow-baseline"
CURRENT = ROOT / "build/repo/com/github/mobilewhj/offlineSdk/offlineSdk/0.3.0"
SOURCE_ROOT = Path("offlineSdk/src/main/java")
JAVAP = Path("/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/javap")
CLASS = "com.offline.tool.ManagedOfflineSdk"
MANAGER = "com/offline/tool/ManagedOfflineSdk.kt"
TYPES = "com/offline/tool/ManagedOfflineTypes.kt"


def digest(data):
    return sha256(data).hexdigest()


def source_entries(path):
    with zipfile.ZipFile(path) as archive:
        return {name: archive.read(name) for name in archive.namelist() if name.endswith((".kt", ".java"))}


def filesystem_sources(path):
    return {
        file.relative_to(path).as_posix(): file.read_bytes()
        for file in path.rglob("*") if file.is_file() and file.suffix in (".kt", ".java")
    }


def compare_sources(archive, filesystem):
    names = sorted(set(archive) | set(filesystem))
    return {
        "archive_count": len(archive),
        "filesystem_count": len(filesystem),
        "matches": sum(archive.get(name) == filesystem.get(name) for name in names),
        "missing_from_archive": sorted(set(filesystem) - set(archive)),
        "extra_in_archive": sorted(set(archive) - set(filesystem)),
        "files": [
            {
                "path": name,
                "archive_sha256": digest(archive[name]) if name in archive else None,
                "filesystem_sha256": digest(filesystem[name]) if name in filesystem else None,
                "matches": archive.get(name) == filesystem.get(name),
            }
            for name in names
        ],
    }


commands = [f"cd {shlex.quote(str(ROOT))}", f"python3 {shlex.quote(str(Path(__file__).relative_to(ROOT)))}"]
artifacts = {}
signatures = {}
removed_bridges = {}
for label, directory in (("before", BASELINE / "artifacts"), ("after", CURRENT)):
    aar = directory / "offlineSdk-0.3.0.aar"
    sources = directory / "offlineSdk-0.3.0-sources.jar"
    classes = REPORT / f"api-{label}-classes.jar"
    with zipfile.ZipFile(aar) as archive:
        classes.write_bytes(archive.read("classes.jar"))
    command = [str(JAVAP), "-public", "-s", "-classpath", str(classes), CLASS]
    commands.append(shlex.join(command))
    raw = subprocess.run(command, check=True, capture_output=True, text=True).stdout
    (REPORT / f"api-{label}-raw.txt").write_text(raw)
    # 只过滤编译器为内部协程/嵌套类生成的 access$ 桥接；保留 Kotlin 默认参数入口。
    entries = re.findall(r"^  public[^\n]+;\n    descriptor: [^\n]+", raw, re.MULTILINE)
    removed_bridges[label] = [entry.splitlines()[0].strip() for entry in entries if " access$" in entry]
    kept = [entry for entry in entries if " access$" not in entry]
    signatures[label] = kept
    (REPORT / f"api-{label}.txt").write_text("\n\n".join(kept) + "\n")
    artifacts[label] = {
        "aar": {"path": str(aar.relative_to(ROOT)), "sha256": digest(aar.read_bytes())},
        "sources": {"path": str(sources.relative_to(ROOT)), "sha256": digest(sources.read_bytes())},
        "classes_sha256": digest(classes.read_bytes()),
    }

before_sources = source_entries(BASELINE / "artifacts/offlineSdk-0.3.0-sources.jar")
after_sources = source_entries(CURRENT / "offlineSdk-0.3.0-sources.jar")
before_match = compare_sources(before_sources, filesystem_sources(BASELINE / "workspace" / SOURCE_ROOT))
after_match = compare_sources(after_sources, filesystem_sources(ROOT / SOURCE_ROOT))
source_changes = [
    {"path": name,
     "before_sha256": digest(before_sources[name]) if name in before_sources else None,
     "after_sha256": digest(after_sources[name]) if name in after_sources else None,
     "unchanged": before_sources.get(name) == after_sources.get(name)}
    for name in sorted(set(before_sources) | set(after_sources))
]
method_counts = Counter(re.search(r"([\w.$]+)\(", entry).group(1) for entry in signatures["after"])
expected = Counter({CLASS: 2, "getState": 1, "startupDecision": 1, "prepareFirst": 1,
                    "setConditions": 1, "requestCheck": 1, "preparePage": 1, "commitPage": 1,
                    "shutdown": 1, "prepareFirst$default": 1, "commitPage$default": 1})
checks = {
    "baseline_sources_match_frozen_workspace": all(file["matches"] for file in before_match["files"]),
    "baseline_manager_matches_frozen_workspace": before_sources[MANAGER] == (BASELINE / "workspace" / SOURCE_ROOT / MANAGER).read_bytes(),
    "current_sources_match_workspace": all(file["matches"] for file in after_match["files"]),
    "current_manager_contains_decideCandidate": b"decideCandidate" in after_sources[MANAGER],
    "manager_public_jvm_signatures_equal": signatures["before"] == signatures["after"],
    "expected_public_surface_retained": method_counts == expected,
    "public_types_source_unchanged": before_sources[TYPES] == after_sources[TYPES],
    "only_manager_production_source_changed": [file["path"] for file in source_changes if not file["unchanged"]] == [MANAGER],
}
(REPORT / "api-signature.diff").write_text("".join(difflib.unified_diff(
    (REPORT / "api-before.txt").read_text().splitlines(keepends=True),
    (REPORT / "api-after.txt").read_text().splitlines(keepends=True),
    fromfile="api-before.txt", tofile="api-after.txt",
)))
(REPORT / "api-commands.txt").write_text("\n".join(commands) + "\n")
report = {
    "generated_at_utc": datetime.now(timezone.utc).isoformat(),
    "status": "passed" if all(checks.values()) else "failed",
    "scope": "固定基线 AAR 与本轮 AAR 的 ManagedOfflineSdk 公开 JVM 签名比较；同时校验 sources JAR 与源码身份。",
    "limits": [
        "javap -public -s 比较声明、泛型显示及 JVM 描述符；过滤 access$ 编译器内部桥接。",
        "保留主构造器、带 DefaultConstructorMarker 的默认参数构造器、state getter、7 个公开方法及 2 个 $default 入口。",
        "不是完整 ABI 工具验证：未穷尽 Kotlin 元数据、全部类的二进制属性、跨版本编译器兼容性或运行时链接。",
        "其他 14 个生产源文件逐字节未变，仅证明源码身份不变，不单独声称全库二进制 ABI 已验证。",
    ],
    "artifacts": artifacts,
    "checks": checks,
    "baseline_sources_vs_frozen_workspace": before_match,
    "current_sources_vs_workspace": after_match,
    "production_source_comparison": source_changes,
    "manager_public_signature_entry_count": len(signatures["after"]),
    "method_counts": dict(method_counts),
    "filtered_synthetic_bridges": removed_bridges,
    "commands": commands,
    "script_sha256": digest(Path(__file__).read_bytes()),
}
(REPORT / "api-compatibility.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
print(json.dumps({"status": report["status"], "checks": checks, "entries": len(signatures["after"]),
                  "baseline_source_count": len(before_sources), "current_source_count": len(after_sources),
                  "before_aar_sha256": artifacts["before"]["aar"]["sha256"],
                  "after_aar_sha256": artifacts["after"]["aar"]["sha256"]}, ensure_ascii=False, indent=2))
raise SystemExit(0 if all(checks.values()) else 1)
