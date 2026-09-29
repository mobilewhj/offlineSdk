#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat >&2 <<'EOF'
Usage: run.sh GROUP ARTIFACT VERSION [GIT_REF] [OUTPUT_DIR]

GROUP, ARTIFACT and VERSION must come from the published JitPack POM.
GIT_REF defaults to VERSION. OUTPUT_DIR must not exist; by default a new
directory is created under TMPDIR. The directory is kept as build evidence.
EOF
}

if (( $# < 3 || $# > 5 )); then
  usage
  exit 2
fi

group=$1
artifact=$2
version=$3
source_ref=${4:-$version}
for part in "$group" "$artifact" "$version"; do
  if [[ ! $part =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]]; then
    echo "Invalid Maven coordinate part: $part" >&2
    exit 2
  fi
done
if [[ ! $source_ref =~ ^[A-Za-z0-9][A-Za-z0-9._/-]*$ ]]; then
  echo "Invalid Git ref: $source_ref" >&2
  exit 2
fi

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
repo_root=$(cd "$script_dir/../.." && pwd -P)
source_commit=$(git -C "$repo_root" rev-parse --verify "${source_ref}^{commit}")
coordinate="$group:$artifact:$version"

if (( $# == 5 )); then
  output_dir=$5
  if [[ -e $output_dir ]]; then
    echo "Output directory already exists: $output_dir" >&2
    exit 2
  fi
  mkdir -p "$output_dir"
else
  output_dir=$(mktemp -d "${TMPDIR:-/tmp}/offline-sdk-remote-consumer.XXXXXX")
fi
output_dir=$(cd "$output_dir" && pwd -P)
project_dir="$output_dir/project"
mkdir "$project_dir"

cat > "$output_dir/context.txt" <<EOF
SOURCE_REF=$source_ref
SOURCE_COMMIT=$source_commit
REMOTE_COORDINATE=$coordinate
PROJECT_DIR=$project_dir
EOF
echo "Remote smoke evidence: $output_dir"

# The host comes from the exact release commit. It has no SDK project module.
git -C "$repo_root" archive --format=tar "$source_commit" \
  app gradle gradlew build.gradle.kts gradle.properties \
  scripts/aar-consumer-smoke/src | tar -xf - -C "$project_dir"

smoke_source="$project_dir/scripts/aar-consumer-smoke/src/com/offline/tool/sample/OfflineSdkAarSmoke.kt"
smoke_target="$project_dir/app/src/main/java/com/offline/tool/sample/OfflineSdkAarSmoke.kt"
if [[ -e $smoke_target ]]; then
  echo "Smoke source would overwrite Demo source: $smoke_target" >&2
  exit 1
fi
cp "$smoke_source" "$smoke_target"

# Only this temporary copy of the Demo changes its dependency declaration.
python3 - "$project_dir/app/build.gradle.kts" "$coordinate" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
coordinate = sys.argv[2]
source = 'implementation(project(":offlineSdk"))'
contents = path.read_text()
if contents.count(source) != 1:
    raise SystemExit("Expected exactly one SDK project dependency in the Demo")
path.write_text(contents.replace(source, f'implementation("{coordinate}")'))
PY

# The SDK coordinate is reserved for JitPack. No local Maven repository,
# composite build, or dependency substitution is present in this host.
cat > "$project_dir/settings.gradle.kts" <<EOF
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        exclusiveContent {
            forRepository {
                maven { url = uri("https://jitpack.io") }
            }
            filter { includeGroup("$group") }
        }
    }
}
rootProject.name = "offlineSdkRemoteConsumer"
include(":app")
EOF

# Android Studio's local SDK location is not committed; use it if present.
if [[ -f $repo_root/local.properties ]]; then
  cp "$repo_root/local.properties" "$project_dir/local.properties"
fi

cd "$project_dir"
./gradlew --gradle-user-home "$output_dir/gradle-home" \
  --refresh-dependencies --no-daemon --console=plain --info \
  -I "$script_dir/verify-remote.init.gradle" \
  -PremoteSmokeGroup="$group" \
  -PremoteSmokeArtifact="$artifact" \
  -PremoteSmokeVersion="$version" \
  :app:verifyRemoteSdkResolution \
  :app:dependencyInsight --configuration debugRuntimeClasspath --dependency "$artifact" \
  :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:lintDebug \
  :app:compileDebugAndroidTestKotlin \
  2>&1 | tee "$output_dir/build.log"

grep '^REMOTE_' "$output_dir/build.log" > "$output_dir/resolution.txt"
python3 - "$project_dir/app/build/test-results/testDebugUnitTest" "$output_dir/test-summary.txt" <<'PY'
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

files = sorted(Path(sys.argv[1]).glob("TEST-*.xml"))
if not files:
    raise SystemExit("Demo unit test XML is missing")
total = dict(tests=0, failures=0, errors=0, skipped=0)
for file in files:
    root = ET.parse(file).getroot()
    for key in total:
        total[key] += int(root.attrib.get(key, "0"))
summary = " ".join(f"{key}={value}" for key, value in total.items())
Path(sys.argv[2]).write_text(summary + "\n")
print(f"REMOTE_DEMO_TESTS={summary}")
if total["failures"] or total["errors"] or not total["tests"]:
    raise SystemExit("Demo unit tests did not pass")
PY

echo "Remote smoke passed; evidence: $output_dir"
