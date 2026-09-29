# JitPack remote consumer smoke

Run this after a candidate tag is published and its JitPack POM is available. Read
the actual `groupId`, `artifactId`, and `version` from that POM; do not copy the
group used by the local `build/repo` publication.

```bash
scripts/remote-consumer-smoke/run.sh GROUP ARTIFACT VERSION [GIT_REF] [OUTPUT_DIR]
```

`GIT_REF` defaults to `VERSION`. The script archives the tagged Demo and the
existing AAR smoke source into a new isolated directory. That directory includes
only `:app`. It directly declares the supplied Maven coordinate and reserves
that group for JitPack. Its dependency repositories are Google, Maven Central,
and JitPack. It does not use the SDK project, `build/repo`, `mavenLocal`, or
dependency substitution. A fresh Gradle user home and `--refresh-dependencies`
prevent reuse of a locally published SDK artifact.

The script runs Demo Debug, Release with R8, unit tests, lint, and Android test
source compilation. It keeps `context.txt`, `build.log`, `resolution.txt`,
`test-summary.txt`, and the isolated project (including JUnit XML) in the output
directory. `resolution.txt` records the resolved module, AAR file, and SHA-256.
The optional `OUTPUT_DIR` must not already exist. A failed build keeps its logs.

For release evidence, independently download the JitPack POM, AAR, and sources
JAR over HTTPS. Confirm that the consumed AAR SHA-256 equals the downloaded AAR;
compare the sources JAR contents to the tagged SDK source and the remote AAR's
public API to the accepted API inventory. Record the remote artifact URLs and
their hashes alongside this smoke output.
