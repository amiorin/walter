from __future__ import annotations

import shutil
from pathlib import Path

import pytest
from big_config import EXIT
from big_config import render as bc_render
from big_config import workflow as bc_workflow

from walter import ansible
from walter import cli
from walter import options
from walter import validation as v
from walter.package import walter_star

TEST_COMPUTE_PUBKEY = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIHDKdUkY+SfRm6ttOz2EEZ2+i/zm+o1mpMOdMeGUr0t4 test@example.com"


def with_creds(opts):
    out = dict(opts)
    params = dict(out[bc_workflow.PARAMS])
    params.update(
        {
            "compute-pubkey": TEST_COMPUTE_PUBKEY,
            "hcloud-token": "stub",
            "hcloud-name": "walter",
            "hcloud-image": "ubuntu-24.04",
            "hcloud-server-type": "cx23",
            "hcloud-location": "hel1",
            "hcloud-ssh-keys": "stub-key",
            "do-token": "stub",
            "digitalocean-name": "walter",
            "digitalocean-region": "ams3",
            "digitalocean-size": "s-1vcpu-1gb-35gb-intel",
            "digitalocean-image": "ubuntu-25-10-x64",
            "digitalocean-vpc-uuid": "stub-vpc",
            "digitalocean-ssh-keys": "stub-key",
            "oci-config-file-profile": "DEFAULT",
            "oci-subnet-id": "stub-subnet",
            "oci-compartment-id": "stub-compartment",
            "oci-availability-domain": "stub-ad",
            "oci-display-name": "walter",
            "oci-shape": "VM.Standard.A1.Flex",
            "oci-ocpus": 1,
            "oci-memory-in-gbs": 4,
            "oci-boot-volume-size-in-gbs": 50,
            "oci-boot-volume-vpus-per-gb": 30,
            "oci-ssh-authorized-keys": "~/.ssh/id_ed25519.pub",
            "no-infra-compute-ip": "192.0.2.10",
            "no-infra-compute-user": "ubuntu",
            "no-infra-compute-sudoer": "root",
            "no-infra-compute-uid": "1000",
            "r2-bucket": "stub-bucket",
            "r2-endpoint": "https://stub.r2.cloudflarestorage.com",
            "r2-access-key-id": "stub",
            "r2-secret-access-key": "stub",
            "s3-bucket": "stub-bucket",
            "s3-region": "eu-west-1",
        }
    )
    out[bc_workflow.PARAMS] = params
    out["params"] = params
    return out


def profile(provider_compute, provider_backend):
    return with_creds(
        {
            bc_render.PROFILE: "walter",
            bc_workflow.PARAMS: {
                "package": "walter",
                "provider-compute": provider_compute,
                "provider-backend": provider_backend,
            },
        }
    )


def test_active_profiles_pass_schema_with_stub_creds():
    for p in [with_creds(options.walter), profile("hcloud", "r2"), profile("digitalocean", "s3"), profile("no-infra", "local")]:
        assert v.schema_errors(p) is None


def test_placeholder_credential_is_reported():
    p = profile("hcloud", "local")
    p[bc_workflow.PARAMS]["hcloud-token"] = "REPLACE_ME"
    errors = v.schema_errors(p)
    assert errors
    assert any("hcloud-token" in e["detail"] and "REPLACE_ME" in e["detail"] for e in errors)


def test_env_string_scalars_are_accepted():
    p = profile("oci", "local")
    p[bc_workflow.PARAMS]["oci-ocpus"] = "2"
    assert v.schema_errors(p) is None
    p = profile("no-infra", "local")
    p[bc_workflow.PARAMS]["compute-prevent-destroy"] = "false"
    assert v.schema_errors(p) is None


def test_validate_workflow_step_sets_exit_status(capsys):
    ok = v.validate([], {}, lambda _opts: {"ok": True, "errors": []})
    assert ok[EXIT] == 0
    bad = v.validate([], {}, lambda _opts: {"ok": False, "errors": [{"check": "schema", "detail": "bad"}]})
    assert bad[EXIT] == 1
    assert bad["err"] == "validation failed"


def test_provider_tools_picks_right_clis():
    assert {t["cmd"] for t in v.provider_tools({"provider-compute": "hcloud", "provider-backend": "s3"})} == {"hcloud", "aws"}
    assert {t["cmd"] for t in v.provider_tools({"provider-compute": "no-infra", "provider-backend": "local"})} == set()


def test_tool_errors_honors_injected_which_fn():
    errors = v.tool_errors(profile("hcloud", "s3")[bc_workflow.PARAMS], lambda cmd: cmd != "tofu")
    assert len(errors) == 1
    assert "OpenTofu" in errors[0]["detail"]


def test_ssh_agent_check_cloud_and_no_infra():
    params = {"provider-compute": "hcloud", "compute-pubkey": TEST_COMPUTE_PUBKEY}
    assert "SSH_AUTH_SOCK" in v.ssh_agent_errors(params, {})[0]
    assert v.ssh_agent_errors({**params, "provider-compute": "no-infra"}, {}) == []

    key_id_line = " ".join(TEST_COMPUTE_PUBKEY.split()[:2])

    def run_fn(args, extra_env=None):
        assert args == ["ssh-add", "-L"]
        assert extra_env == {"SSH_AUTH_SOCK": "/tmp/agent.sock"}
        return {"ok": True, "exit": 0, "out": f"{key_id_line} other-comment\n", "err": ""}

    assert v.ssh_agent_errors(params, {"SSH_AUTH_SOCK": "/tmp/agent.sock"}, run_fn) == []


def test_r2_head_bucket_errors_are_classified():
    assert v.classify_head_bucket_error("An error occurred (404): Not Found") == "missing-bucket"
    assert v.classify_head_bucket_error("An error occurred (403): Forbidden") == "bad-credentials"
    assert v.classify_head_bucket_error("connection reset") == "unknown"


def test_ansible_data_checks_compute_pubkey():
    assert v.ansible_data_errors(profile("no-infra", "local")) == []
    p = profile("no-infra", "local")
    del p[bc_workflow.PARAMS]["compute-pubkey"]
    errors = v.ansible_data_errors(p)
    assert errors
    assert any(":ssh_key" in e["detail"] for e in errors)


def test_cli_exposes_only_package_validate(capsys):
    assert "validate" in cli.PACKAGE_COMMANDS
    assert "walter package validate" in cli.HELP
    with pytest.raises(SystemExit):
        cli.main(["validate"], options.bb)


def test_ansible_render_matches_reference_generated_files():
    data = ansible.data_fn({"ip": "192.168.0.1", "compute-pubkey": "REPLACE_ME"}, None)
    ref = Path(__file__).resolve().parents[2] / "clojure/.dist/walter-7b467017/io/github/bigconfig-ai/walter/tools/ansible"
    if not ref.exists():
        pytest.skip("Clojure reference artifact not present")
    assert ansible.inventory(data) == (ref / "inventory.json").read_text()
    assert ansible.config(data) == (ref / "default.config.yml").read_text()
    assert ansible.packages(data) == (ref / "roles/users/tasks/packages.yml").read_text()
    assert ansible.repos(data) == (ref / "roles/users/tasks/repos.yml").read_text()
    assert ansible.ssh_config(data) == (ref / "roles/users/tasks/ssh-config.yml").read_text()


def test_package_build_matches_clojure_reference():
    root = Path(__file__).resolve().parents[1]
    ref = root.parent / "clojure/.dist/walter-7b467017"
    if not ref.exists():
        pytest.skip("Clojure reference artifact not present")
    target = root / ".dist/walter-7b467017"
    shutil.rmtree(target, ignore_errors=True)
    try:
        walter_star(["build"], options.bb)
    except SystemExit as exc:
        assert exc.code == 0
    ref_files = sorted(p.relative_to(ref) for p in ref.rglob("*") if p.is_file())
    target_files = sorted(p.relative_to(target) for p in target.rglob("*") if p.is_file())
    assert target_files == ref_files
    for rel in ref_files:
        assert (target / rel).read_bytes() == (ref / rel).read_bytes()
