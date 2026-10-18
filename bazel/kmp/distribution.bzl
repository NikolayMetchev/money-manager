"""`jvm_distribution`: Gradle's `installDist` layout for a JVM program (#1013).

`<name>/lib/` holds the runtime classpath, in classpath order, and `<name>/bin/` the start scripts Gradle's
application plugin writes: `<name>` for Unix and `<name>.bat` for Windows. The desktop installer bundles it
(jpackage's `--add-launcher`), and `pkg_tar` over it gives the archive.
"""

load("@rules_java//java/common:java_info.bzl", "JavaInfo")

_UNIX = """#!/bin/sh
# Starts {name} on the JVM: JAVA_HOME's java, else the one on PATH, with $DEFAULT_JVM_OPTS, $JAVA_OPTS and
# ${opts_var}.
app_path=$0
while [ -h "$app_path" ]; do
    ls=$(ls -ld "$app_path")
    link=${{ls#*' -> '}}
    case $link in
        /*) app_path=$link ;;
        *) app_path=${{app_path%/*}}/$link ;;
    esac
done
APP_HOME=$(cd -P "${{app_path%/*}}/.." > /dev/null && pwd -P) || exit
DEFAULT_JVM_OPTS={jvm_flags}
CLASSPATH={unix_classpath}
if [ -n "$JAVA_HOME" ]; then
    JAVACMD=$JAVA_HOME/bin/java
    [ -x "$JAVACMD" ] || {{ echo "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME" >&2; exit 1; }}
else
    JAVACMD=java
    command -v java > /dev/null 2>&1 || {{ echo "ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH." >&2; exit 1; }}
fi
# shellcheck disable=SC2086 # the options are word-split, as Gradle's script does
exec "$JAVACMD" $DEFAULT_JVM_OPTS $JAVA_OPTS ${opts_var} -classpath "$CLASSPATH" {main_class} "$@"
"""

_WINDOWS = """@rem Starts {name} on the JVM: JAVA_HOME's java, else the one on PATH, with %DEFAULT_JVM_OPTS%, %JAVA_OPTS%
@rem and %{opts_var}%.
@if "%DEBUG%"=="" @echo off
setlocal
set APP_HOME=%~dp0..
set DEFAULT_JVM_OPTS={jvm_flags}
set CLASSPATH={windows_classpath}
set JAVA_EXE=java.exe
if defined JAVA_HOME set JAVA_EXE=%JAVA_HOME%\\bin\\java.exe
"%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %{opts_var}% -classpath "%CLASSPATH%" {main_class} %*
if %ERRORLEVEL% neq 0 exit /b %ERRORLEVEL%
endlocal
"""

def jar_name(jar):
    """Each jar's name in `lib/`: a Maven jar keeps its own; a module's is named after its path, as Gradle's are
    (`app/db/read:read_sqldelight_jvm` is `app-db-read-sqldelight-jvm.jar`)."""
    if jar.owner.workspace_name:
        return jar.basename
    package = jar.owner.package
    leaf = package.rpartition("/")[2]
    stem = jar.basename.removesuffix(".jar").removeprefix(leaf + "_").removeprefix(leaf)
    return package.replace("/", "-") + ("-" + stem.lstrip("_").replace("_", "-") if stem.lstrip("_") else "") + ".jar"

def _impl(ctx):
    jars = depset(transitive = [d[JavaInfo].transitive_runtime_jars for d in ctx.attr.deps]).to_list()
    outputs = []
    names = []
    seen = {}
    for jar in jars:
        if jar.owner.workspace_name == "rules_kotlin+" and jar.basename == "empty.jar":
            # rules_kotlin's placeholder in the toolchain's runtime libraries: no entries.
            continue
        name = jar_name(jar)
        if name in seen:
            fail("%s: %s and %s are both lib/%s" % (ctx.label, seen[name].short_path, jar.short_path, name))
        seen[name] = jar
        names.append(name)
        out = ctx.actions.declare_file("%s/lib/%s" % (ctx.label.name, name))
        ctx.actions.symlink(output = out, target_file = jar)
        outputs.append(out)
    values = {
        "jvm_flags": " ".join(ctx.attr.jvm_flags),
        "main_class": ctx.attr.main_class,
        "name": ctx.label.name,
        "opts_var": ctx.label.name.upper().replace("-", "_") + "_OPTS",
        "unix_classpath": ":".join(["$APP_HOME/lib/" + n for n in names]),
        "windows_classpath": ";".join(["%APP_HOME%\\lib\\" + n for n in names]),
    }
    unix = ctx.actions.declare_file("%s/bin/%s" % (ctx.label.name, ctx.label.name))
    ctx.actions.write(unix, _UNIX.format(**values), is_executable = True)
    windows = ctx.actions.declare_file("%s/bin/%s.bat" % (ctx.label.name, ctx.label.name))
    ctx.actions.write(windows, _WINDOWS.format(**values).replace("\n", "\r\n"))
    files = [unix, windows] + outputs
    return [DefaultInfo(files = depset(files), executable = unix, runfiles = ctx.runfiles(files))]

jvm_distribution = rule(
    implementation = _impl,
    doc = "A JVM program as Gradle's `installDist` lays it out: `<name>/bin/` start scripts, `<name>/lib/` jars.",
    attrs = {
        "deps": attr.label_list(providers = [JavaInfo], doc = "The program's libraries; their runtime closure is lib/."),
        "jvm_flags": attr.string_list(doc = "DEFAULT_JVM_OPTS."),
        "main_class": attr.string(mandatory = True),
    },
    executable = True,
)
