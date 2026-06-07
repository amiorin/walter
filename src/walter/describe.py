"""Walter post-provisioning describe report."""
from __future__ import annotations

import os
import subprocess
from collections.abc import Callable, Mapping
from typing import Any

from big_config.core import Opts, ok

from . import ansible
from .interop import PARAMS, PROFILE, status, sync_aliases, to_bc_opts
from .params import walter_opts

RUN_TIMEOUT = 30
SSH_PROBE_TIMEOUT = 10

RunFn = Callable[[list[str], Mapping[str, Any] | None], dict[str, Any]]
WalterOptsFn = Callable[[Opts], Opts]


def run(args: list[str], opts: Mapping[str, Any] | None = None) -> dict[str, Any]:
    opts = opts or {}
    timeout = float(opts.get("timeout", opts.get("timeout-ms", RUN_TIMEOUT * 1000)))
    if timeout > 1000:
        timeout = timeout / 1000
    extra_env = opts.get("extra-env") or opts.get("extra_env")
    try:
        proc = subprocess.run(
            args,
            input=b"",
            capture_output=True,
            check=False,
            timeout=timeout,
            env=None if extra_env is None else {**os.environ, **extra_env},
        )
        out = proc.stdout.decode(errors="replace")
        err = proc.stderr.decode(errors="replace")
        return {"ok": proc.returncode == 0, "exit": proc.returncode, "out": out, "err": err}
    except subprocess.TimeoutExpired:
        return {"ok": False, "exit": -1, "out": "", "err": f"command timed out after {int(timeout * 1000)}ms"}
    except Exception as exc:  # noqa: BLE001
        return {"ok": False, "exit": -1, "out": "", "err": str(exc)}


def trim_snippet(s: Any) -> str | None:
    text = str(s or "").strip()
    if not text:
        return None
    return text[:200] + ("…" if len(text) > 200 else "")


def result_detail(label: str, result: Mapping[str, Any]) -> str:
    snippet = trim_snippet(result.get("err")) or trim_snippet(result.get("out"))
    suffix = f" — {snippet}" if snippet else ""
    return f"{label} failed (exit {result.get('exit', -1)}){suffix}"


def provider_summary(params: Mapping[str, Any]) -> dict[str, Any]:
    return {"compute": params.get("provider-compute"), "backend": params.get("provider-backend")}


def compute_target(params: Mapping[str, Any]) -> dict[str, Any]:
    ip = params.get("ip")
    if (
        params.get("provider-compute") == "no-infra"
        and (not ip or ip == "192.168.0.1")
        and params.get("no-infra-compute-ip")
    ):
        ip = params.get("no-infra-compute-ip")
    return {
        "ip": ip,
        "user": params.get("user")
        or params.get("no-infra-compute-user")
        or params.get("sudoer")
        or params.get("no-infra-compute-sudoer")
        or "root",
    }


def ssh_base_args(compute: Mapping[str, Any]) -> list[str]:
    return [
        "ssh",
        "-o",
        "BatchMode=yes",
        "-o",
        "ConnectTimeout=5",
        "-o",
        "StrictHostKeyChecking=accept-new",
        f"{compute.get('user')}@{compute.get('ip')}",
    ]


def ssh_run(run_fn: RunFn, compute: Mapping[str, Any], remote_args: list[str]) -> dict[str, Any]:
    return run_fn([*ssh_base_args(compute), *remote_args], {"timeout-ms": SSH_PROBE_TIMEOUT * 1000})


def compute_status(run_fn: RunFn, params: Mapping[str, Any]) -> dict[str, Any]:
    target = compute_target(params)
    ip = target.get("ip")
    if not ip:
        return {**target, "running": False, "detail": "missing IP address"}
    result = ssh_run(run_fn, target, ["true"])
    ok = bool(result.get("ok"))
    detail = "ssh ok" if ok else result_detail("ssh", result) + ("; no Tofu output found or host is down" if ip == "192.168.0.1" else "")
    return {**target, "running": ok, "detail": detail}


def resolve_walter_opts(opts: Opts, walter_opts_fn: WalterOptsFn) -> tuple[Opts, str | None]:
    try:
        return walter_opts_fn(opts), None
    except Exception as exc:  # noqa: BLE001
        return opts, f"could not resolve OpenTofu parameters: {exc}"


def workstation_summary(params: Mapping[str, Any]) -> dict[str, Any]:
    data = ansible.data_fn(dict(params), None)
    return {
        "hosts": list(data.get("hosts") or []),
        "sudoer": data.get("sudoer"),
        "users": [u.get("name") for u in data.get("users") or []],
        "repoCount": len(data.get("repos") or []),
        "packageCount": len(data.get("packages") or []),
    }


def describe_report(opts: Opts, run_fn: RunFn = run, walter_opts_fn: WalterOptsFn = walter_opts) -> dict[str, Any]:
    """Build a Walter describe report from opts."""
    resolved, resolve_detail = resolve_walter_opts(to_bc_opts(opts), walter_opts_fn)
    resolved = to_bc_opts(resolved)
    params = dict(resolved.get(PARAMS) or {})
    compute = compute_status(run_fn, params)
    if resolve_detail:
        compute["detail"] = f"{compute.get('detail', '')}; {resolve_detail}"
    return {
        "profile": resolved.get(PROFILE),
        "providers": provider_summary(params),
        "compute": compute,
        "workstation": workstation_summary(params),
        "fatalError": False,
    }


def present(x: Any) -> str:
    return "unknown" if x is None or str(x).strip() == "" else str(x)


def join_present(xs: Any) -> str:
    values = [str(x) for x in (xs or []) if str(x).strip()]
    return ", ".join(values) if values else "unknown"


def print_report(result: Mapping[str, Any]) -> None:
    providers = result.get("providers") or {}
    compute = result.get("compute") or {}
    workstation = result.get("workstation") or {}
    print(f"Profile: {present(result.get('profile'))}")
    print()
    print("Providers:")
    print(f"  Compute: {present(providers.get('compute'))}")
    print(f"  Backend: {present(providers.get('backend'))}")
    print()
    print("Compute:")
    print(f"  IP: {present(compute.get('ip'))}")
    print(f"  SSH user: {present(compute.get('user'))}")
    status_text = "running" if compute.get("running") else "not reachable"
    detail = f" ({compute.get('detail')})" if compute.get("detail") else ""
    print(f"  Status: {status_text}{detail}")
    print()
    print("Workstation:")
    print(f"  Hosts: {join_present(workstation.get('hosts'))}")
    print(f"  Sudoer: {present(workstation.get('sudoer'))}")
    print(f"  Users: {join_present(workstation.get('users'))}")
    print(f"  Repositories: {workstation.get('repoCount', 0)}")
    print(f"  Packages: {workstation.get('packageCount', 0)}")


def describe(_step_fns: Any, opts: Opts, report_fn: Callable[[Opts], dict[str, Any]] = describe_report) -> Opts:
    """Workflow step for ``walter package describe``."""
    result = report_fn(opts)
    print_report(result)
    base = {**sync_aliases(opts), "describe/result": result}
    return status(base, 1, "describe failed") if result.get("fatalError") else sync_aliases(ok(base))


# TypeScript-style alias.
describeReport = describe_report
