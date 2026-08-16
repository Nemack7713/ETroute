import tempfile
import unittest
from pathlib import Path

from etroute_p4a import (
    AbiStatus,
    P4aCapabilityBackend,
    P4aRecipeClassifier,
    P4aRecipePlanner,
    PackageKind,
    RecipeStrategy,
)


class P4aRecipeSubsystemTests(unittest.TestCase):
    def setUp(self):
        self.classifier = P4aRecipeClassifier()
        self.planner = P4aRecipePlanner()

    def test_pure_python_uses_pip(self):
        with tempfile.TemporaryDirectory() as tmp:
            Path(tmp, "module.py").write_text("VALUE = 1\n", encoding="utf-8")
            plan = self.planner.plan(self.classifier.inspect(tmp, "demo"), ["arm64-v8a"])
            self.assertEqual(plan.strategy, RecipeStrategy.PIP)
            self.assertFalse(plan.recipe_required)

    def test_pyproject_uses_pyproject_recipe(self):
        with tempfile.TemporaryDirectory() as tmp:
            Path(tmp, "pyproject.toml").write_text('[build-system]\nrequires=["setuptools"]\nbuild-backend="setuptools.build_meta"\n', encoding="utf-8")
            plan = self.planner.plan(self.classifier.inspect(tmp, "modern"), ["arm64-v8a"])
            self.assertEqual(plan.strategy, RecipeStrategy.PYPROJECT_RECIPE)
            self.assertEqual(plan.abi_assessments[0].status, AbiStatus.SUPPORTED)

    def test_cython_has_priority_over_pyproject(self):
        with tempfile.TemporaryDirectory() as tmp:
            Path(tmp, "pyproject.toml").write_text("[build-system]\n", encoding="utf-8")
            Path(tmp, "engine.pyx").write_text("cdef int value = 1\n", encoding="utf-8")
            inspection = self.classifier.inspect(tmp, "cython_demo")
            plan = self.planner.plan(inspection, ["arm64-v8a"])
            self.assertEqual(inspection.kind, PackageKind.CYTHON)
            self.assertEqual(plan.strategy, RecipeStrategy.CYTHON_RECIPE)
            self.assertEqual(plan.abi_assessments[0].status, AbiStatus.UNKNOWN)

    def test_native_extension_uses_compiled_components(self):
        with tempfile.TemporaryDirectory() as tmp:
            Path(tmp, "native.c").write_text("int answer(void){return 42;}\n", encoding="utf-8")
            plan = self.planner.plan(self.classifier.inspect(tmp, "native_demo"), ["arm64-v8a"])
            self.assertEqual(plan.strategy, RecipeStrategy.COMPILED_COMPONENTS_RECIPE)

    def test_android_mk_uses_ndk_recipe(self):
        with tempfile.TemporaryDirectory() as tmp:
            Path(tmp, "Android.mk").write_text("LOCAL_PATH := $(call my-dir)\n", encoding="utf-8")
            plan = self.planner.plan(self.classifier.inspect(tmp, "ndk_demo"), ["arm64-v8a"])
            self.assertEqual(plan.strategy, RecipeStrategy.NDK_RECIPE)

    def test_backend_generates_private_recipe_and_manifest(self):
        with tempfile.TemporaryDirectory() as tmp, tempfile.TemporaryDirectory() as output:
            Path(tmp, "engine.pyx").write_text("cdef int value = 1\n", encoding="utf-8")
            result = P4aCapabilityBackend().resolve("p4a.recipe.generate", {"project": tmp, "package": "engine-demo", "abis": ["arm64-v8a"], "output": output})
            recipe_dir = Path(result.value["recipe_directory"])
            self.assertTrue(result.available)
            self.assertTrue(Path(recipe_dir, "__init__.py").exists())
            self.assertTrue(Path(recipe_dir, "etroute-recipe.json").exists())


if __name__ == "__main__":
    unittest.main()
