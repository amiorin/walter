"""Walter parameter composition."""
from __future__ import annotations

from big_config import workflow as bc_workflow
from big_config.core import Opts
from once.params import tofu_params

from .interop import read_bc_pars, sync_aliases, to_bc_opts

START_STEP = "io.github.bigconfig-ai.walter.package/start-create-or-delete"


def opts_fn(opts: Opts) -> Opts:
    """Apply BC_PAR_* overrides, then merge compute outputs from Once's Tofu stage."""
    return sync_aliases(tofu_params(read_bc_pars(opts)))


def walter_opts(opts: Opts) -> Opts:
    """``opts_fn`` after stamping Walter's deterministic create/delete prefix."""
    return opts_fn(bc_workflow.new_prefix(to_bc_opts(opts), START_STEP))


optsFn = opts_fn
walterOpts = walter_opts
