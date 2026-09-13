"""Compile/test the production bridge with cached Kotlin jars and a normal JDK.

No Gradle invocation, Android SDK, APK, emulator, or network download.
Prints a runtime.json path usable with tools/test_mysterium_bridge_pc.py.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", type=Path, default=Path.home() / ".gradle/caches/modules-2/files-2.1")
    parser.add_argument("--kotlin-version", default="2.4.10")
    args = parser.parse_args()
    repository = Path(__file__).resolve().parents[2]
    coords = [("org.jetbrains.kotlin", name, args.kotlin_version) for name in
              ("kotlin-compiler-embeddable", "kotlin-stdlib", "kotlin-script-runtime",
               "kotlin-daemon-embeddable", "kotlin-build-tools-api")]
    coords += [("org.jetbrains.kotlin", "kotlin-reflect", "1.6.10"),
               ("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.8.0")]
    jars = []
    for group, artifact, version in coords:
        matches = list((args.cache / group / artifact / version).rglob("*.jar"))
        if not matches:
            raise SystemExit(f"Required cached compiler dependency missing: {group}:{artifact}:{version}")
        jars.append(str(matches[0]))
    annotations = list((args.cache / "org.jetbrains/annotations").rglob("*.jar"))
    if not annotations:
        raise SystemExit("Required cached org.jetbrains:annotations jar missing")
    jars.append(str(annotations[0]))
    cp = os.pathsep.join(jars)
    folder = Path(tempfile.mkdtemp(prefix="mysterium-jvm-"))
    output = folder / "bridge.jar"
    subprocess.run(["java", "-cp", cp, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                    "-no-stdlib", "-no-reflect", "-classpath", cp, "-jvm-target", "17", "-d", str(output),
                    str(repository / "joyntv/src/main/java/com/andreassamitsch/joyntv/JoynMysteriumProxyBridge.kt"),
                    str(Path(__file__).with_name("BridgePcTest.kt"))], check=True)
    runtime = str(output) + os.pathsep + cp
    subprocess.run(["java", "-cp", runtime, "com.andreassamitsch.joyntv.BridgePcTestKt"], check=True)
    target = folder / "runtime.json"
    target.write_text(json.dumps({"classpath": runtime}), encoding="utf-8")
    print(f"Runtime config: {target}")


if __name__ == "__main__":
    main()
