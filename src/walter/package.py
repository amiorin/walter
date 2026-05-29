"""High-level Walter build/create/delete workflows and package entry point."""
from __future__ import annotations

from big_config import ENV
from big_config import workflow as bc_workflow
from big_config.core import Opts, StepFn, workflow
from big_config.step_fns import exit_step_fn, print_error_step_fn
from once import tools as once_tools

from . import tools as walter_tools
from .interop import PARAMS, sync_aliases, to_bc_opts
from .params import opts_fn
from .validation import validate

START = "io.github.bigconfig-ai.walter.package/start"
END = "io.github.bigconfig-ai.walter.package/end"
PIPELINE_START = "io.github.bigconfig-ai.walter.package/start-create-or-delete"
PIPELINE_END = "io.github.bigconfig-ai.walter.package/end-create-or-delete"

step_fns: list[StepFn] = [bc_workflow.print_step_fn, exit_step_fn(END), print_error_step_fn(END)]

TOFU_APPLY = "render tofu:init tofu:apply:-auto-approve"
TOFU_DESTROY = "render tofu:init tofu:destroy:-auto-approve"
ANSIBLE_RUN = "render ansible-playbook:main.yml"

PIPELINE_TOOLS = [
    (once_tools.TOFU, once_tools.tofu),
    (walter_tools.ANSIBLE, walter_tools.ansible),
    (once_tools.ANSIBLE_LOCAL, once_tools.ansible_local),
]

for step, fn in PIPELINE_TOOLS:
    bc_workflow.register_workflow_step(step, fn)

create = bc_workflow.workflow_star(
    {
        "first_step": PIPELINE_START,
        "last_step": PIPELINE_END,
        "pipeline": [
            once_tools.TOFU,
            [TOFU_APPLY, opts_fn],
            walter_tools.ANSIBLE,
            [ANSIBLE_RUN, opts_fn],
            once_tools.ANSIBLE_LOCAL,
            [ANSIBLE_RUN, opts_fn],
        ],
    }
)

build = bc_workflow.workflow_star(
    {
        "first_step": PIPELINE_START,
        "last_step": PIPELINE_END,
        "pipeline": [
            once_tools.TOFU,
            ["render", opts_fn],
            walter_tools.ANSIBLE,
            ["render", opts_fn],
            once_tools.ANSIBLE_LOCAL,
            ["render", opts_fn],
        ],
    }
)

delete_workflow = bc_workflow.workflow_star(
    {
        "first_step": PIPELINE_START,
        "last_step": PIPELINE_END,
        "pipeline": [once_tools.TOFU, [TOFU_DESTROY, opts_fn]],
    }
)

TOOL_OPTS_KEYS = [
    f"{once_tools.TOFU}-opts",
    f"{walter_tools.ANSIBLE}-opts",
    f"{once_tools.ANSIBLE_LOCAL}-opts",
]


def walter(sfns: list[StepFn], opts: Opts) -> Opts:
    """Run a validate / build / create / delete workflow."""
    opts = to_bc_opts(opts)
    with_fns: Opts = {
        bc_workflow.CREATE_FN: create,
        bc_workflow.BUILD_FN: build,
        bc_workflow.DELETE_FN: delete_workflow,
        bc_workflow.VALIDATE_FN: validate,
        **opts,
    }
    merged = bc_workflow.merge_params(TOOL_OPTS_KEYS, opts.get(PARAMS) or {}, with_fns)

    def wire(step: str, resolved_step_fns: list[StepFn]):
        if step == START:
            return (lambda o: bc_workflow.run_steps(resolved_step_fns, o)), END
        return (lambda o: o), None

    wf = workflow({"first_step": START, "wire_fn": wire})
    return sync_aliases(wf(sfns, merged))


def walter_star(args: str | list[str], opts: Opts | None = None) -> Opts:
    """CLI-ready package entry point."""
    parsed = bc_workflow.parse_args(args)
    return walter(step_fns, {**parsed, ENV: "shell", **to_bc_opts(opts or {})})


# Compatibility aliases.
deleteWorkflow = delete_workflow
walterStar = walter_star
