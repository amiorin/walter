"""Pre-flight validation for the active Walter profile."""
from __future__ import annotations

import os
import re
import shutil
import subprocess
from pathlib import Path
from typing import Any, Callable, Literal, TypedDict

from big_config.core import Opts
from once.params import tofu_params

from . import ansible
from .interop import ok_alias, params_of, profile_of, read_bc_pars, status

CheckKind = Literal["schema", "tool", "credential", "ansible-data"]


class CheckError(TypedDict):
    check: CheckKind
    detail: str


class ValidateResult(TypedDict):
    ok: bool
    errors: list[CheckError]


class RunResult(TypedDict):
    ok: bool
    exit: int
    out: str
    err: str


Runner = Callable[[list[str], dict[str, str] | None], RunResult]

ssh_pubkey_rx = re.compile(r"^ssh-(ed25519|rsa|dss|ecdsa) [A-Za-z0-9+/=]+( .*)?$")
PLACEHOLDER = "REPLACE_ME"
PLACEHOLDER_MSG = "must replace REPLACE_ME with a real value"


def is_placeholder(v: Any) -> bool:
    return isinstance(v, str) and PLACEHOLDER in v


def blank_or_placeholder(v: Any) -> bool:
    return v is None or (isinstance(v, str) and (v.strip() == "" or is_placeholder(v)))


def real_value(v: Any) -> bool:
    return not blank_or_placeholder(v)


FieldCheck = Callable[[Any], str | None]


def string_value(v: Any) -> str | None:
    if not isinstance(v, str):
        return "should be a string"
    if is_placeholder(v):
        return PLACEHOLDER_MSG
    if v.strip() == "":
        return "must be a non-empty string"
    return None


non_empty_string = string_value


def int_value(v: Any) -> str | None:
    if is_placeholder(v):
        return PLACEHOLDER_MSG
    if type(v) is int:  # noqa: E721 - bool must not pass
        return None
    if isinstance(v, str) and re.fullmatch(r"^-?\d+$", v):
        return None
    return "should be an integer"


def boolean_value(v: Any) -> str | None:
    if isinstance(v, bool) or v in {"true", "false"}:
        return None
    return "should be a boolean"


def re_check(rx: re.Pattern[str], msg: str) -> FieldCheck:
    def check(v: Any) -> str | None:
        if not isinstance(v, str):
            return "should be a string"
        if is_placeholder(v):
            return PLACEHOLDER_MSG
        if rx.fullmatch(v) is None:
            return msg
        return None

    return check


Emit = Callable[[str, str], None]


def required(obj: dict[str, Any], key: str, check: FieldCheck, emit: Emit, prefix: str = "workflow/params") -> None:
    if key not in obj:
        emit(f"{prefix} → {key}", "missing required key")
        return
    msg = check(obj[key])
    if msg:
        emit(f"{prefix} → {key}", msg)


def check_base_params(params: dict[str, Any], emit: Emit) -> None:
    required(params, "package", non_empty_string, emit)
    required(params, "compute-pubkey", re_check(ssh_pubkey_rx, "must look like an SSH public key"), emit)
    if "compute-prevent-destroy" in params:
        msg = boolean_value(params["compute-prevent-destroy"])
        if msg:
            emit("workflow/params → compute-prevent-destroy", msg)


def check_backend(params: dict[str, Any], emit: Emit) -> None:
    match params.get("provider-backend"):
        case "s3":
            required(params, "s3-bucket", string_value, emit)
            required(params, "s3-region", string_value, emit)
        case "r2":
            required(params, "r2-bucket", non_empty_string, emit)
            required(params, "r2-endpoint", non_empty_string, emit)
            required(params, "r2-access-key-id", non_empty_string, emit)
            required(params, "r2-secret-access-key", non_empty_string, emit)
        case "local":
            pass
        case _:
            emit("workflow/params → provider-backend", "invalid dispatch value")


def check_compute(params: dict[str, Any], emit: Emit) -> None:
    match params.get("provider-compute"):
        case "oci":
            for k in [
                "oci-config-file-profile",
                "oci-subnet-id",
                "oci-compartment-id",
                "oci-availability-domain",
                "oci-display-name",
                "oci-shape",
                "oci-ssh-authorized-keys",
            ]:
                required(params, k, string_value, emit)
            for k in ["oci-ocpus", "oci-memory-in-gbs", "oci-boot-volume-size-in-gbs", "oci-boot-volume-vpus-per-gb"]:
                required(params, k, int_value, emit)
        case "hcloud":
            for k in ["hcloud-name", "hcloud-image", "hcloud-server-type", "hcloud-location", "hcloud-ssh-keys", "hcloud-token"]:
                required(params, k, string_value, emit)
        case "digitalocean":
            for k in [
                "digitalocean-name",
                "digitalocean-region",
                "digitalocean-size",
                "digitalocean-image",
                "digitalocean-vpc-uuid",
                "digitalocean-ssh-keys",
                "do-token",
            ]:
                required(params, k, string_value, emit)
        case "no-infra":
            for k in ["no-infra-compute-ip", "no-infra-compute-user", "no-infra-compute-sudoer", "no-infra-compute-uid"]:
                required(params, k, string_value, emit)
        case _:
            emit("workflow/params → provider-compute", "invalid dispatch value")


def schema_errors(opts: Opts) -> list[CheckError] | None:
    errors: list[CheckError] = []

    def emit(path: str, msg: str) -> None:
        errors.append({"check": "schema", "detail": f"{path}: {msg}"})

    profile_msg = string_value(profile_of(opts))
    if profile_msg:
        emit("render/profile", profile_msg)

    params = params_of(opts)
    if not isinstance(params, dict):
        emit("workflow/params", "missing required key")
    else:
        check_base_params(params, emit)
        check_compute(params, emit)
        check_backend(params, emit)
    return errors or None


class ToolSpec(TypedDict):
    cmd: str
    name: str
    hint: str


AWS_HINT = "https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html"

base_tools: list[ToolSpec] = [
    {"cmd": "tofu", "name": "OpenTofu", "hint": "https://opentofu.org/docs/intro/install/"},
    {"cmd": "ansible-playbook", "name": "Ansible", "hint": "pipx install ansible"},
    {"cmd": "ssh", "name": "OpenSSH", "hint": "your distro's openssh-client package"},
    {"cmd": "curl", "name": "curl", "hint": "your distro's curl package"},
]


def provider_tools(params: dict[str, Any]) -> list[ToolSpec]:
    tools: list[ToolSpec] = []
    compute = params.get("provider-compute")
    backend = params.get("provider-backend")
    if compute == "oci":
        tools.append({"cmd": "oci", "name": "OCI CLI", "hint": "pip install oci-cli"})
    if compute == "hcloud":
        tools.append({"cmd": "hcloud", "name": "hcloud", "hint": "https://github.com/hetznercloud/cli"})
    if compute == "digitalocean":
        tools.append({"cmd": "doctl", "name": "doctl", "hint": "https://docs.digitalocean.com/reference/doctl/how-to/install/"})
    if backend in {"s3", "r2"}:
        tools.append({"cmd": "aws", "name": "AWS CLI", "hint": AWS_HINT})
    return tools


def which(cmd: str) -> bool:
    return shutil.which(cmd) is not None


def tool_errors(params: dict[str, Any], which_fn: Callable[[str], bool] = which) -> list[CheckError]:
    return [
        {"check": "tool", "detail": f"{t['name']} not found on PATH. Install: {t['hint']}"}
        for t in [*base_tools, *provider_tools(params)]
        if not which_fn(t["cmd"])
    ]


RUN_TIMEOUT_MS = 30000


def run(args: list[str], extra_env: dict[str, str] | None = None) -> RunResult:
    try:
        env = {**os.environ, **(extra_env or {})}
        res = subprocess.run(
            args,
            input="",
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=RUN_TIMEOUT_MS / 1000,
            env=env,
            check=False,
        )
        return {"ok": res.returncode == 0, "exit": res.returncode, "out": res.stdout or "", "err": res.stderr or ""}
    except subprocess.TimeoutExpired:
        return {"ok": False, "exit": -1, "out": "", "err": f"command timed out after {RUN_TIMEOUT_MS}ms"}
    except Exception as exc:  # noqa: BLE001
        return {"ok": False, "exit": -1, "out": "", "err": str(exc)}


def trim_snippet(s: str | None) -> str | None:
    t = (s or "").strip()
    if t == "":
        return None
    return f"{t[:200]}…" if len(t) > 200 else t


def _call_runner(run_fn: Runner, args: list[str], extra_env: dict[str, str] | None = None) -> RunResult:
    try:
        return run_fn(args, extra_env)
    except TypeError:
        return run_fn(args)  # type: ignore[misc]


def bearer_check(label: str, url: str, token: str, run_fn: Runner) -> str | None:
    r = _call_runner(run_fn, ["curl", "-sf", "-o", "/dev/null", "-H", f"Authorization: Bearer {token}", url])
    if r["ok"]:
        return None
    snippet = trim_snippet(r.get("err"))
    return f"{label}: token rejected (curl exit {r['exit']})" + (f" — {snippet}" if snippet else "")


def cli_check(label: str, args: list[str], extra_env: dict[str, str] | None, run_fn: Runner) -> str | None:
    r = _call_runner(run_fn, args, extra_env)
    if r["ok"]:
        return None
    return f"{label}: {trim_snippet(r.get('err')) or 'command failed'}"


def oci_config_path() -> str:
    return os.environ.get("OCI_CLI_CONFIG_FILE") or os.environ.get("OCI_CONFIG_FILE") or str(Path.home() / ".oci" / "config")


def oci_config_error() -> str | None:
    p = oci_config_path()
    if not Path(p).exists():
        return f"OCI: config file not found at {p} — run 'oci setup config' to create one"
    return None


def classify_head_bucket_error(err: str) -> Literal["missing-bucket", "bad-credentials", "unknown"]:
    s = (err or "").lower()
    if "(404)" in s or "not found" in s or "nosuchbucket" in s:
        return "missing-bucket"
    if any(x in s for x in ["(401)", "(403)", "forbidden", "unauthorized", "invalidaccesskey", "signaturedoesnotmatch"]):
        return "bad-credentials"
    return "unknown"


def r2_errors(params: dict[str, Any], run_fn: Runner) -> list[str]:
    bucket = params.get("r2-bucket")
    endpoint = params.get("r2-endpoint")
    access_key = params.get("r2-access-key-id")
    secret_key = params.get("r2-secret-access-key")
    missing: list[str] = []
    if blank_or_placeholder(endpoint):
        missing.append("r2-endpoint")
    if blank_or_placeholder(bucket):
        missing.append("r2-bucket")
    if blank_or_placeholder(access_key):
        missing.append("r2-access-key-id")
    if blank_or_placeholder(secret_key):
        missing.append("r2-secret-access-key")
    if missing:
        return [f"R2: missing or placeholder credentials: {', '.join(missing)}"]
    if not which("aws"):
        return []
    r = _call_runner(
        run_fn,
        ["aws", "s3api", "head-bucket", "--bucket", str(bucket), "--endpoint-url", str(endpoint)],
        {"AWS_ACCESS_KEY_ID": str(access_key), "AWS_SECRET_ACCESS_KEY": str(secret_key), "AWS_DEFAULT_REGION": "auto"},
    )
    if r["ok"]:
        return []
    snippet = trim_snippet(r.get("err")) or "head-bucket failed"
    kind = classify_head_bucket_error(r.get("err", ""))
    if kind == "missing-bucket":
        return [f"R2 (bucket): {bucket} not found at {endpoint} — {snippet}"]
    if kind == "bad-credentials":
        return [f"R2 (auth): credentials rejected at {endpoint} — {snippet}"]
    return [f"R2: head-bucket on {bucket} at {endpoint} failed — {snippet}"]


CLOUD_COMPUTE_PROVIDERS = {"oci", "hcloud", "digitalocean"}


def cloud_compute(params: dict[str, Any]) -> bool:
    return params.get("provider-compute") in CLOUD_COMPUTE_PROVIDERS


def ssh_pubkey_identity(s: str | None) -> str | None:
    parts = (s or "").strip().split()
    if len(parts) >= 2 and parts[0] and parts[1]:
        return f"{parts[0]} {parts[1]}"
    return None


def ssh_agent_errors(params: dict[str, Any], env: dict[str, str | None], run_fn: Runner = run) -> list[str]:
    if not cloud_compute(params):
        return []
    compute_pubkey = params.get("compute-pubkey")
    sock = (env.get("SSH_AUTH_SOCK") or "").strip()
    if is_placeholder(compute_pubkey):
        return ["SSH agent: :compute-pubkey still contains REPLACE_ME"]
    if sock == "":
        return ["SSH agent: SSH_AUTH_SOCK is not set; start ssh-agent and run ssh-add for :compute-pubkey"]
    r = _call_runner(run_fn, ["ssh-add", "-L"], {"SSH_AUTH_SOCK": sock})
    wanted = ssh_pubkey_identity(compute_pubkey if isinstance(compute_pubkey, str) else None)
    agent_msg = f"{r.get('err', '')}\n{r.get('out', '')}"
    if wanted is None:
        return ["SSH agent: :compute-pubkey is not a parseable SSH public key"]
    if r["ok"]:
        loaded = {x for x in (ssh_pubkey_identity(line) for line in (r.get("out") or "").splitlines()) if x is not None}
        if wanted in loaded:
            return []
        return [f"SSH agent: :compute-pubkey is not loaded in ssh-agent at SSH_AUTH_SOCK={sock}"]
    if "no identities" in agent_msg.lower():
        return [f"SSH agent: :compute-pubkey is not loaded; the agent at SSH_AUTH_SOCK={sock} has no identities"]
    snippet = trim_snippet(r.get("err"))
    return [f"SSH agent: ssh-add -L failed for SSH_AUTH_SOCK={sock} (exit {r['exit']})" + (f" — {snippet}" if snippet else "")]


def credential_errors(params: dict[str, Any], env: dict[str, str | None] | None = None, run_fn: Runner = run) -> list[CheckError]:
    env = os.environ if env is None else env
    p_compute = params.get("provider-compute")
    p_backend = params.get("provider-backend")
    single = [
        bearer_check("Hetzner Cloud API", "https://api.hetzner.cloud/v1/server_types", params.get("hcloud-token"), run_fn)
        if p_compute == "hcloud" and real_value(params.get("hcloud-token"))
        else None,
        bearer_check("DigitalOcean API", "https://api.digitalocean.com/v2/account", params.get("do-token"), run_fn)
        if p_compute == "digitalocean" and real_value(params.get("do-token"))
        else None,
        (oci_config_error() or cli_check("OCI", ["oci", "iam", "region", "list", "--output", "json"], None, run_fn))
        if p_compute == "oci" and which("oci")
        else None,
        cli_check("AWS (S3 backend)", ["aws", "sts", "get-caller-identity"], None, run_fn)
        if p_backend == "s3" and which("aws")
        else None,
    ]
    multi = [*(r2_errors(params, run_fn) if p_backend == "r2" else []), *ssh_agent_errors(params, env, run_fn)]
    return [{"check": "credential", "detail": m} for m in [*(x for x in single if x is not None), *multi]]


def ansible_data_error(detail: str) -> CheckError:
    return {"check": "ansible-data", "detail": detail}


def ansible_data_errors(opts: Opts) -> list[CheckError]:
    try:
        params = params_of(tofu_params(opts))
        data = ansible.data_fn(params, None)
        hosts = data.get("hosts") or []
        users = data.get("users") or []
        active_users = [u for u in users if not u.get("remove")]
        config_key = (data.get("config") or {}).get("ssh_key")
        compute_key = params.get("compute-pubkey")
        repos = data.get("repos") or []
        packages = data.get("packages") or []
        messages: list[str] = []
        if not hosts:
            messages.append("Ansible hosts: at least one host is required")
        for i, host in enumerate(hosts):
            if blank_or_placeholder(host):
                messages.append(f"Ansible hosts[{i}]: host must be a real value")
        if not active_users:
            messages.append("Ansible users: at least one active user is required")
        for i, user in enumerate(users):
            if blank_or_placeholder(user.get("name")) or blank_or_placeholder(user.get("uid")):
                messages.append(f"Ansible users[{i}]: :name and :uid must be real values")
        if not any(u.get("name") == "ubuntu" for u in active_users):
            messages.append("Ansible users: active users must include ubuntu")
        if blank_or_placeholder(config_key):
            messages.append("Ansible config: :ssh_key must come from :compute-pubkey")
        elif not isinstance(config_key, str) or ssh_pubkey_rx.fullmatch(config_key) is None:
            messages.append("Ansible config: :ssh_key must look like an SSH public key")
        elif real_value(compute_key) and str(config_key).strip() != str(compute_key).strip():
            messages.append("Ansible config: :ssh_key must match :compute-pubkey")
        for i, repo in enumerate(repos):
            if any(blank_or_placeholder(repo.get(k)) for k in ["org", "repo", "branch", "user"]):
                messages.append(f"Ansible repos[{i}]: :org, :repo, :branch, and :user must be real values")
        for i, package in enumerate(packages):
            if not isinstance(package, (list, tuple)) or len(package) < 2 or blank_or_placeholder(package[0]) or blank_or_placeholder(package[1]):
                messages.append(f"Ansible packages[{i}]: package and CLI names must be real values")
        return [ansible_data_error(m) for m in messages]
    except Exception as exc:  # noqa: BLE001
        return [ansible_data_error(f"failed to build Walter Ansible data — {exc}")]


def validate_report(opts: Opts, env: dict[str, str | None] | None = None) -> ValidateResult:
    """Validate the merged active Walter profile."""
    env = os.environ if env is None else env
    merged = read_bc_pars(opts, env)
    params = params_of(merged)
    errors = [
        *(schema_errors(merged) or []),
        *tool_errors(params),
        *credential_errors(params, env),
        *ansible_data_errors(merged),
    ]
    return {"ok": len(errors) == 0, "errors": errors}


def group_name(k: CheckKind) -> str:
    return {"schema": "Schema", "tool": "Tools", "credential": "Credentials", "ansible-data": "Ansible data"}.get(k, str(k))


def print_report(result: ValidateResult) -> None:
    if result["ok"]:
        print("All checks passed.")
        return
    n = len(result["errors"])
    print(f"Validation failed ({n} issue{'' if n == 1 else 's'}):")
    for k in ["schema", "tool", "credential", "ansible-data"]:
        es = [e for e in result["errors"] if e["check"] == k]
        if not es:
            continue
        print("")
        print(f"  {group_name(k)}:")
        for e in es:
            print(f"    - {e['detail']}")


def validate(_step_fns: Any, opts: Opts, report_fn: Callable[[Opts], ValidateResult] = validate_report) -> Opts:
    """Workflow step for ``walter package validate``."""
    result = report_fn(opts)
    print_report(result)
    base = {**opts, "validation/result": result}
    return ok_alias(base) if result["ok"] else status(base, 1, "validation failed")


# TypeScript-style aliases.
schemaErrors = schema_errors
providerTools = provider_tools
toolErrors = tool_errors
credentialErrors = credential_errors
sshAgentErrors = ssh_agent_errors
ansibleDataErrors = ansible_data_errors
validateReport = validate_report
