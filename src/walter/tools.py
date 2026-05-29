"""Walter-owned tool workflows."""
from __future__ import annotations

from pathlib import Path
from typing import Callable

from big_config import ENV
from big_config import render as bc_render
from big_config import workflow as bc_workflow
from big_config.core import Opts, StepFn
from big_config.step_fns import exit_step_fn, print_error_step_fn
from big_config.utils import keyword_to_path
from once import tools as once_tools

from . import ansible as a

END = "big-config.workflow/end"

step_fns: list[StepFn] = [bc_workflow.print_step_fn, exit_step_fn(END), print_error_step_fn(END)]

delimiters = {"tag-open": "<", "tag-close": ">", "filter-open": "{", "filter-close": "}"}

ANSIBLE = "io.github.bigconfig-ai.walter.tools/ansible"

_ORIGINAL_ONCE_KEYWORD_TO_PATH = once_tools.keyword_to_path


def _resource_template_path(module_file: str, template: str) -> str:
    rel = keyword_to_path(template)
    start = Path(module_file).resolve().parent
    candidates: list[Path] = []
    for parent in [start, *start.parents]:
        candidates.extend([parent / "resources" / rel, parent / "src" / "resources" / rel])
    for candidate in candidates:
        if candidate.exists() and candidate.is_dir():
            return str(candidate)
    return rel


def _patch_once_resource_paths() -> None:
    def patched(template: str) -> str:
        if str(template).startswith("io.github.bigconfig-ai.once."):
            return _resource_template_path(once_tools.__file__, template)
        return _ORIGINAL_ONCE_KEYWORD_TO_PATH(template)

    once_tools.keyword_to_path = patched


_patch_once_resource_paths()


def template_path(template: str) -> str:
    return _resource_template_path(__file__, template)


def ansible(sfns: list[StepFn], opts: Opts) -> Opts:
    prepared = bc_workflow.prepare(
        {
            bc_workflow.NAME: ANSIBLE,
            bc_render.TEMPLATES: [
                {
                    "template": template_path(ANSIBLE),
                    "overwrite": True,
                    "data-fn": a.data_fn,
                    "transform": [
                        [".", "raw"],
                        [a.render, "roles/users/tasks", {"packages": "packages.yml", "repos": "repos.yml", "ssh-config": "ssh-config.yml"}, "raw"],
                        [a.render, {"inventory": "inventory.json", "config": "default.config.yml"}, "raw"],
                    ],
                }
            ],
        },
        opts,
    )
    return bc_workflow.run_steps(sfns, prepared)


def ansible_star(args: str | list[str], opts: Opts | None = None) -> Opts:
    parsed = bc_workflow.parse_args(args)
    return ansible(step_fns, {**parsed, ENV: "shell", **(opts or {})})


ansibleStar = ansible_star
