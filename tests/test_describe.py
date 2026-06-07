from __future__ import annotations

from big_config import render as bc_render
from big_config import workflow as bc_workflow

from walter.describe import describe, describe_report

TEST_COMPUTE_PUBKEY = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIHDKdUkY+SfRm6ttOz2EEZ2+i/zm+o1mpMOdMeGUr0t4 test@example.com"

BASE_OPTS = {
    bc_render.PROFILE: "walter-test",
    bc_workflow.PARAMS: {
        "provider-compute": "no-infra",
        "provider-backend": "local",
        "package": "walter",
        "ip": "203.0.113.10",
        "user": "ubuntu",
        "sudoer": "root",
        "compute-pubkey": TEST_COMPUTE_PUBKEY,
    },
}


def identity(opts):
    return opts


def test_describe_report_summarizes_reachable_workstation():
    calls = []

    def run_fn(args, opts=None):
        calls.append((args, opts))
        return {"ok": True, "exit": 0, "out": "", "err": ""}

    result = describe_report(BASE_OPTS, run_fn, identity)
    assert result["profile"] == "walter-test"
    assert result["providers"] == {"compute": "no-infra", "backend": "local"}
    assert result["compute"]["running"] is True
    assert result["compute"]["detail"] == "ssh ok"
    assert result["workstation"]["hosts"] == ["203.0.113.10"]
    assert result["workstation"]["sudoer"] == "root"
    assert result["workstation"]["repoCount"] > 0
    assert result["workstation"]["packageCount"] > 0
    assert calls[0][0][0] == "ssh"


def test_describe_report_soft_fails_unreachable_ssh():
    def run_fn(_args, _opts=None):
        return {"ok": False, "exit": 255, "out": "", "err": "connection refused"}

    result = describe_report(BASE_OPTS, run_fn, identity)
    assert result["compute"]["running"] is False
    assert "connection refused" in result["compute"]["detail"]
    assert result["fatalError"] is False


def test_describe_workflow_step_sets_exit_status(capsys):
    result = describe([], {}, lambda _opts: {"profile": "test", "providers": {}, "compute": {}, "workstation": {}, "fatalError": False})
    assert result["exit"] == 0
    assert result["describe/result"]["profile"] == "test"
    assert "Profile: test" in capsys.readouterr().out
