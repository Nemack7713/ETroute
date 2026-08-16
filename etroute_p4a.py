"""ETroute planning layer for python-for-android private recipes.

This module plans and scaffolds recipes without invoking a p4a build. Native
ABI compatibility remains unknown until a real cross-build runs.
"""
from __future__ import annotations

import argparse
import json
import re
from dataclasses import asdict, dataclass, field
from enum import Enum
from pathlib import Path
from typing import Any, Iterable


class PackageKind(str, Enum):
    PURE_PYTHON = "pure_python"
    PYPROJECT = "pyproject"
    CYTHON = "cython"
    COMPILED_EXTENSION = "compiled_extension"
    NDK = "ndk"


class RecipeStrategy(str, Enum):
    PIP = "pip"
    PYPROJECT_RECIPE = "PyProjectRecipe"
    CYTHON_RECIPE = "CythonRecipe"
    COMPILED_COMPONENTS_RECIPE = "CompiledComponentsPythonRecipe"
    NDK_RECIPE = "NDKRecipe"


class AbiStatus(str, Enum):
    SUPPORTED = "supported"
    UNKNOWN = "unknown"


@dataclass(frozen=True)
class Evidence:
    kind: str
    path: str
    detail: str


@dataclass
class PackageInspection:
    package_name: str
    root: str
    kind: PackageKind
    evidence: list[Evidence] = field(default_factory=list)
    has_pyproject: bool = False
    has_setup_py: bool = False
    has_cython: bool = False
    has_native_sources: bool = False
    has_android_mk: bool = False
    has_application_mk: bool = False
    pyproject_build_backend: str | None = None


@dataclass(frozen=True)
class AbiAssessment:
    abi: str
    status: AbiStatus
    reason: str


@dataclass
class RecipePlan:
    package: str
    package_kind: PackageKind
    strategy: RecipeStrategy
    reason: str
    recipe_required: bool
    generated_recipe_supported: bool
    abi_assessments: list[AbiAssessment] = field(default_factory=list)
    evidence: list[Evidence] = field(default_factory=list)


@dataclass
class CapabilityResult:
    capability: str
    available: bool
    backend: str
    value: Any


class P4aRecipeClassifier:
    NATIVE_SUFFIXES = {".c", ".cc", ".cpp", ".cxx", ".h", ".hpp", ".so"}
    CYTHON_SUFFIXES = {".pyx", ".pxd", ".pxi"}
    IGNORED_PARTS = {".git", ".gradle", ".idea", ".venv", "venv", "build", "dist", "__pycache__"}

    def inspect(self, project_root: str | Path, package_name: str | None = None) -> PackageInspection:
        root = Path(project_root).resolve()
        if not root.exists():
            raise FileNotFoundError(root)
        if not root.is_dir():
            raise NotADirectoryError(root)

        package_name = package_name or self._infer_name(root)
        pyproject = root / "pyproject.toml"
        setup_py = root / "setup.py"
        android_mk = list(root.rglob("Android.mk"))
        application_mk = list(root.rglob("Application.mk"))
        cython_files = self._collect(root, self.CYTHON_SUFFIXES)
        native_files = self._collect(root, self.NATIVE_SUFFIXES)
        evidence: list[Evidence] = []

        if pyproject.exists():
            evidence.append(Evidence("pyproject", str(pyproject), "pyproject.toml detected"))
        if setup_py.exists():
            evidence.append(Evidence("setup_py", str(setup_py), "setup.py detected"))
        evidence.extend(Evidence("cython", str(p), "Cython source detected") for p in cython_files[:10])
        evidence.extend(Evidence("native", str(p), "native source or binary detected") for p in native_files[:10])
        evidence.extend(Evidence("android_mk", str(p), "Android NDK makefile detected") for p in android_mk)
        evidence.extend(Evidence("application_mk", str(p), "Android NDK application makefile detected") for p in application_mk)

        if android_mk or application_mk:
            kind = PackageKind.NDK
        elif cython_files:
            kind = PackageKind.CYTHON
        elif native_files:
            kind = PackageKind.COMPILED_EXTENSION
        elif pyproject.exists():
            kind = PackageKind.PYPROJECT
        else:
            kind = PackageKind.PURE_PYTHON

        return PackageInspection(
            package_name=package_name,
            root=str(root),
            kind=kind,
            evidence=evidence,
            has_pyproject=pyproject.exists(),
            has_setup_py=setup_py.exists(),
            has_cython=bool(cython_files),
            has_native_sources=bool(native_files),
            has_android_mk=bool(android_mk),
            has_application_mk=bool(application_mk),
            pyproject_build_backend=self._read_build_backend(pyproject) if pyproject.exists() else None,
        )

    def _collect(self, root: Path, suffixes: Iterable[str]) -> list[Path]:
        wanted = {s.lower() for s in suffixes}
        return [p for p in root.rglob("*") if p.is_file() and not any(part in self.IGNORED_PARTS for part in p.parts) and p.suffix.lower() in wanted]

    @staticmethod
    def _infer_name(root: Path) -> str:
        return re.sub(r"[^A-Za-z0-9_.-]+", "-", root.name).strip("-") or "package"

    @staticmethod
    def _read_build_backend(pyproject: Path) -> str | None:
        text = pyproject.read_text(encoding="utf-8", errors="replace")
        match = re.search(r'build-backend\s*=\s*["\']([^"\']+)["\']', text)
        return match.group(1) if match else None


class P4aRecipePlanner:
    KNOWN_ABIS = {"arm64-v8a", "armeabi-v7a", "x86_64", "x86"}

    def plan(self, inspection: PackageInspection, abis: Iterable[str]) -> RecipePlan:
        choices = {
            PackageKind.PURE_PYTHON: (RecipeStrategy.PIP, False, False, "No compiled components detected; use normal p4a/pip packaging."),
            PackageKind.PYPROJECT: (RecipeStrategy.PYPROJECT_RECIPE, True, True, "Modern pyproject package detected; scaffold a PyProjectRecipe."),
            PackageKind.CYTHON: (RecipeStrategy.CYTHON_RECIPE, True, True, "Cython sources detected; Android cross-compilation is required."),
            PackageKind.COMPILED_EXTENSION: (RecipeStrategy.COMPILED_COMPONENTS_RECIPE, True, True, "Native extension sources detected; use CompiledComponentsPythonRecipe as the initial strategy."),
            PackageKind.NDK: (RecipeStrategy.NDK_RECIPE, True, True, "Android.mk/Application.mk detected; use an NDK-oriented private recipe."),
        }
        strategy, required, generated, reason = choices[inspection.kind]
        return RecipePlan(
            package=inspection.package_name,
            package_kind=inspection.kind,
            strategy=strategy,
            reason=reason,
            recipe_required=required,
            generated_recipe_supported=generated,
            abi_assessments=[self._assess_abi(inspection, abi) for abi in abis],
            evidence=inspection.evidence,
        )

    def _assess_abi(self, inspection: PackageInspection, abi: str) -> AbiAssessment:
        if abi not in self.KNOWN_ABIS:
            return AbiAssessment(abi, AbiStatus.UNKNOWN, "ABI is not recognized by the ETroute planner.")
        if inspection.kind in {PackageKind.PURE_PYTHON, PackageKind.PYPROJECT}:
            return AbiAssessment(abi, AbiStatus.SUPPORTED, "No source-tree evidence currently proves this ABI incompatible.")
        return AbiAssessment(abi, AbiStatus.UNKNOWN, "Native compilation is involved; confirm compatibility with a real p4a build.")


class PrivateRecipeGenerator:
    IMPORTS = {
        RecipeStrategy.PYPROJECT_RECIPE: "from pythonforandroid.recipe import PyProjectRecipe",
        RecipeStrategy.CYTHON_RECIPE: "from pythonforandroid.recipe import CythonRecipe",
        RecipeStrategy.COMPILED_COMPONENTS_RECIPE: "from pythonforandroid.recipe import CompiledComponentsPythonRecipe",
        RecipeStrategy.NDK_RECIPE: "from pythonforandroid.recipe import NDKRecipe",
    }

    def generate(self, plan: RecipePlan, output_root: str | Path, version: str = "0.1.0", source_url: str | None = None) -> Path:
        if not plan.recipe_required or not plan.generated_recipe_supported:
            raise ValueError(f"No generated private recipe is appropriate for {plan.package}")
        recipe_name = plan.package.lower().replace("-", "_").replace(".", "_")
        recipe_dir = Path(output_root) / recipe_name
        recipe_dir.mkdir(parents=True, exist_ok=True)
        base = plan.strategy.value
        class_name = "".join(p.capitalize() for p in recipe_name.split("_") if p) + "Recipe"
        url = source_url or f"https://example.invalid/{plan.package}-{{version}}.tar.gz"
        body = [
            "# Generated by ETroute; review package metadata before production use.",
            self.IMPORTS[plan.strategy], "", "",
            f"class {class_name}({base}):", f'    version = "{version}"', f'    url = "{url}"', "",
            "    def get_recipe_env(self, arch):", "        env = super().get_recipe_env(arch)",
            "        # Add package-specific Android compiler/include/library environment here.", "        return env", "", "",
            f"recipe = {class_name}()", "",
        ]
        (recipe_dir / "__init__.py").write_text("\n".join(body), encoding="utf-8")
        manifest = {
            "schema": 1,
            "package": plan.package,
            "classification": plan.package_kind.value,
            "strategy": plan.strategy.value,
            "recipe_required": plan.recipe_required,
            "reason": plan.reason,
            "abis": [{"abi": a.abi, "status": a.status.value, "reason": a.reason} for a in plan.abi_assessments],
        }
        (recipe_dir / "etroute-recipe.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
        return recipe_dir


class P4aCapabilityBackend:
    BACKEND_NAME = "p4a_recipe_planner"

    def __init__(self) -> None:
        self.classifier = P4aRecipeClassifier()
        self.planner = P4aRecipePlanner()
        self.generator = PrivateRecipeGenerator()

    def resolve(self, capability: str, payload: dict[str, Any]) -> CapabilityResult:
        if capability not in {"p4a.recipe.plan", "p4a.recipe.generate"}:
            return CapabilityResult(capability, False, self.BACKEND_NAME, {"error": "unsupported_capability"})
        inspection = self.classifier.inspect(payload["project"], payload.get("package"))
        plan = self.planner.plan(inspection, payload.get("abis", ["arm64-v8a"]))
        value = self._plan_dict(plan)
        if capability == "p4a.recipe.generate":
            if not plan.recipe_required:
                value = {"generated": False, "reason": "Pure Python package does not require a private recipe.", "plan": value}
            else:
                recipe_dir = self.generator.generate(plan, payload.get("output", "p4a-recipes"), payload.get("version", "0.1.0"), payload.get("url"))
                value = {"generated": True, "recipe_directory": str(recipe_dir), "plan": value}
        return CapabilityResult(capability, True, self.BACKEND_NAME, value)

    @staticmethod
    def _plan_dict(plan: RecipePlan) -> dict[str, Any]:
        return {
            "package": plan.package,
            "package_kind": plan.package_kind.value,
            "strategy": plan.strategy.value,
            "reason": plan.reason,
            "recipe_required": plan.recipe_required,
            "generated_recipe_supported": plan.generated_recipe_supported,
            "abi_assessments": [{"abi": a.abi, "status": a.status.value, "reason": a.reason} for a in plan.abi_assessments],
            "evidence": [asdict(e) for e in plan.evidence],
        }


def main() -> None:
    parser = argparse.ArgumentParser(description="Plan or generate an ETroute python-for-android private recipe.")
    sub = parser.add_subparsers(dest="command", required=True)
    for command in ("plan", "generate"):
        p = sub.add_parser(command)
        p.add_argument("project")
        p.add_argument("--package")
        p.add_argument("--abi", action="append", dest="abis")
        if command == "generate":
            p.add_argument("--output", default="p4a-recipes")
            p.add_argument("--version", default="0.1.0")
            p.add_argument("--url")
    args = parser.parse_args()
    payload = {k: v for k, v in vars(args).items() if k != "command" and v is not None}
    capability = "p4a.recipe.plan" if args.command == "plan" else "p4a.recipe.generate"
    print(json.dumps(asdict(P4aCapabilityBackend().resolve(capability, payload)), indent=2, default=str))


if __name__ == "__main__":
    main()
