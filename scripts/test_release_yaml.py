import os
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CLASSES = (
    "org.yaml.snakeyaml.TypeDescription",
    "org.yaml.snakeyaml.introspector.PropertySubstitute",
)


class ReleaseYamlTest(unittest.TestCase):
    def test_yaml_package_reflection_survives_release_obfuscation(self):
        cache = Path.home() / ".gradle/caches/modules-2/files-2.1"
        r8 = next((cache / "com.android.tools/r8/9.1.57").glob("*/*.jar"))
        yaml = next((cache / "org.yaml/snakeyaml/2.2").glob("*/*.jar"))
        java = Path(os.path.realpath(subprocess.check_output(["which", "java"], text=True).strip())).parents[1]
        with tempfile.TemporaryDirectory(prefix="release-yaml-") as directory:
            work = Path(directory)
            source = work / "YamlReleaseProbe.java"
            source.write_text('''
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.TypeDescription;
import org.yaml.snakeyaml.introspector.PropertySubstitute;
public class YamlReleaseProbe {
    public static void main(String[] args) {
        Class<?>[] types = { TypeDescription.class, PropertySubstitute.class };
        for (Class<?> type : types) {
            if (type.getPackage() == null || type.getPackage().getName().isEmpty()) {
                throw new AssertionError("Android returns null Package for repackaged " + type.getName());
            }
        }
        java.util.Map<?, ?> value = new Yaml().load("name: fixture\\ncontent: preserved\\n");
        if (!"preserved".equals(value.get("content"))) throw new AssertionError(value);
        System.out.println("MINIFIED_YAML_PARSE_OK");
    }
}
''')
            subprocess.run(["javac", "-cp", str(yaml), str(source)], check=True, capture_output=True)
            probe = work / "probe.jar"
            subprocess.run(["jar", "cf", str(probe), "-C", str(work), "YamlReleaseProbe.class"], check=True, capture_output=True)
            config = work / "rules.pro"
            rules = (ROOT / "app/proguard-rules.pro").read_text()
            selected = [line for line in rules.splitlines() if any(name in line for name in CLASSES) and line.startswith("-keepnames")]
            config.write_text("-keep class YamlReleaseProbe { public static void main(java.lang.String[]); }\n"
                              "-dontwarn **\n-repackageclasses ''\n" + "\n".join(selected) + "\n")
            output = work / "minified.jar"
            build = subprocess.run(["java", "-cp", str(r8), "com.android.tools.r8.R8", "--classfile", "--release",
                                    "--lib", str(java), "--pg-conf", str(config), "--output", str(output),
                                    str(probe), str(yaml)], capture_output=True, text=True, timeout=120)
            self.assertEqual(0, build.returncode, build.stderr)
            result = subprocess.run(["java", "-cp", str(output), "YamlReleaseProbe"], capture_output=True, text=True, timeout=30)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertIn("MINIFIED_YAML_PARSE_OK", result.stdout)


if __name__ == "__main__":
    unittest.main()
