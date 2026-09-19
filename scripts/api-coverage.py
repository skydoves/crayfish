#!/usr/bin/env python3
"""Reports how much of the published API surface the tests actually touch.

Line coverage is unavailable here: Kover 0.9.1 refuses to configure a project using AGP's
`com.android.kotlin.multiplatform.library` plugin. This measures something a library arguably cares
about more anyway — whether every public declaration is named by at least one test — and it reads
the surface from the binary-compatibility-validator dump, which is the same file that gates API
changes, so the two can never drift apart.
"""
import pathlib, re, sys, collections

ROOT = pathlib.Path(__file__).resolve().parent.parent

def declarations(api_file):
    """Public class names and their public member names, from a BCV dump."""
    out = collections.OrderedDict()
    current = None
    for line in api_file.read_text().splitlines():
        m = re.match(r"public\s+.*?class\s+(\S+)", line)
        if m:
            current = m.group(1).split("/")[-1].replace("$", ".")
            out.setdefault(current, set())
            continue
        if current:
            m = re.match(r"\s+public\s+.*?(?:fun|field)\s+([A-Za-z_][A-Za-z0-9_]*)", line)
            if m:
                name = m.group(1)
                out[current].add(name)
                # An accessor is reached through the property. Kotlin generates `getForDisplay`
                # for `ForDisplay` and `getWidth` for `width`, so both spellings have to be tried —
                # lowercasing unconditionally hid a companion whose members the tests use 24 times.
                stripped = re.sub(r"^(get|set)", "", name)
                if stripped and stripped != name:
                    out[current].add(stripped)
                    out[current].add(stripped[0].lower() + stripped[1:])
    return out

def test_text():
    parts = []
    for module in ("crayfish", "crayfish-landscapist"):
        for d in ("commonTest", "desktopTest", "appleTest", "androidDeviceTest"):
            base = ROOT / module / "src" / d
            if base.exists():
                parts += [p.read_text() for p in base.rglob("*.kt")]
    return "\n".join(parts)

def main():
    tests = test_text()
    total = touched = 0
    untouched = []
    # The denominator, reported below. A gate that examines nothing must say so rather than pass:
    # this one exited 0 with the api directory absent, because the loop simply found no files.
    examined = []
    for module in ("crayfish", "crayfish-landscapist"):
        api_dir = ROOT / module / "api"
        if not api_dir.is_dir():
            print(f"FAIL: {module}/api does not exist; run ./gradlew :{module}:apiDump")
            return 1
        for api in sorted(api_dir.rglob("*.api")):
            if "klib" in api.name:
                continue
            examined.append(str(api.relative_to(ROOT)))
            decls = declarations(api)
            for cls, members in decls.items():
                simple = cls.split(".")[-1]

                # Names the compiler invents, resolved to what a caller can actually write.
                if simple == "Companion" or simple == "DefaultImpls":
                    # Reached only through its owner: `DecodeBudget.ForDisplay`, never
                    # `DecodeBudget.Companion`.
                    names = {cls.split(".")[0]}
                elif cls.startswith("ComposableSingletons"):
                    # Pure Compose compiler output with no source form at all.
                    continue
                elif simple.endswith("Kt"):
                    # A file facade. Its "members" are the top-level declarations, and those are
                    # exactly what a caller writes — `Cropper(...)`, `rememberCropState(...)` — so
                    # unlike a class's properties they are legitimate evidence.
                    names = members or {simple}
                else:
                    # A real type. Its own name, never its members: matching a property called
                    # `value` or `size` made the metric unfalsifiable, and a deliberately untested
                    # probe class scored 100%.
                    names = {simple}

                total += 1
                if any(re.search(rf"\b{re.escape(n)}\b", tests) for n in names):
                    touched += 1
                else:
                    untouched.append(f"{module}:{cls}")
    pct = 100.0 * touched / max(1, total)
    # Report what was examined, so "0 problems" can never be read without its denominator.
    print(f"examined {len(examined)} api dump(s): {', '.join(examined)}")
    if total == 0:
        print("FAIL: the dumps declare no public types at all, so this measured nothing")
        return 1
    print(f"public types touched by tests: {touched}/{total} ({pct:.1f}%)")
    for name in untouched:
        print(f"  untouched: {name}")
    return 0 if not untouched else 1

sys.exit(main())
