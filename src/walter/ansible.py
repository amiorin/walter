"""Walter-specific Ansible data and generated files."""
from __future__ import annotations

import json
from typing import Any


def data_fn(data: dict[str, Any], _opts: dict[str, Any] | None = None) -> dict[str, Any]:
    compute_pubkey = data.get("compute-pubkey")
    sudoer = data.get("sudoer") or "root"
    main_user = "ubuntu"
    hosts = [data.get("ip") or "77.42.91.213"]
    users = [
        {
            "name": main_user,
            "uid": data.get("uid") or "1000",
            "doomemacs": "dd72eac1971616a6ebe81067cca33b14c148cbcd",
            "remove": False,
        }
    ]
    active_users = [u for u in users if not u.get("remove")]
    remove_users = [u for u in users if u.get("remove")]
    config = {
        "users": active_users,
        "remove_users": remove_users,
        "atuin_login": "{{ lookup('ansible.builtin.env', 'ATUIN_LOGIN') }}",
        "ssh_key": compute_pubkey or "REPLACE_ME",
    }

    repos: list[dict[str, Any]] = []
    for repo, worktrees in [
        ("dotfiles-v3", []),
        ("albertomiorin.com", ["albertomiorin"]),
        ("big-container", []),
        ("alice", []),
    ]:
        repos.append({"user": main_user, "org": "amiorin", "repo": repo, "branch": "main", "worktrees": worktrees})
    for repo, worktrees in [
        ("basecamp-once", []),
        ("big-config", []),
        ("once", []),
        ("once-ai", []),
        ("once-bigconfig", []),
        ("once-bigconfig-marketplace", []),
        ("once-caddy-redirect", []),
        ("once-forms", []),
        ("walter", []),
    ]:
        repos.append({"user": main_user, "org": "bigconfig-ai", "repo": repo, "branch": "main", "worktrees": worktrees})

    package_names = [
        "fish",
        "emacs",
        "zellij",
        "starship",
        "direnv",
        "gh",
        "fd",
        "fzf",
        "atuin",
        "just",
        "git",
        "cmake",
        "libtool",
        "socat",
        "zoxide",
        "pixi",
        "eza",
        "zip",
        "unzip",
        "d2",
        "clojure-lsp",
        "btop",
        "clj-kondo",
    ]
    packages = [["ripgrep", "rg"], *[[x, x] for x in package_names]]

    return {
        **data,
        "repos": repos,
        "sudoer": sudoer,
        "hosts": hosts,
        "users": users,
        "config": config,
        "packages": packages,
    }


def _json_string(value: str) -> str:
    return json.dumps(value, ensure_ascii=False)


def clj_json(value: Any, indent: int = 0) -> str:
    """Pretty JSON with Cheshire's spacing."""
    sp = " " * indent
    child = " " * (indent + 2)
    if isinstance(value, dict):
        if not value:
            return "{ }"
        items = list(value.items())
        lines = ["{"]
        for i, (k, v) in enumerate(items):
            comma = "," if i < len(items) - 1 else ""
            lines.append(f"{child}{_json_string(str(k))} : {clj_json(v, indent + 2)}{comma}")
        lines.append(f"{sp}}}")
        return "\n".join(lines)
    if isinstance(value, list):
        if not value:
            return "[ ]"
        lines = ["["]
        for i, v in enumerate(value):
            comma = "," if i < len(value) - 1 else ""
            lines.append(f"{child}{clj_json(v, indent + 2)}{comma}")
        lines.append(f"{sp}]")
        return "\n".join(lines)
    if isinstance(value, str):
        return _json_string(value)
    if value is True:
        return "true"
    if value is False:
        return "false"
    if value is None:
        return "null"
    return str(value)


def _yaml_bool(v: bool) -> str:
    return "true" if v else "false"


def config(data: dict[str, Any]) -> str:
    cfg = data.get("config") or {}
    lines: list[str] = ["users:"]
    for user in cfg.get("users") or []:
        lines.append(f"- name: {user.get('name')}")
        lines.append(f"  uid: '{user.get('uid')}'")
        lines.append(f"  doomemacs: {user.get('doomemacs')}")
        lines.append(f"  remove: {_yaml_bool(bool(user.get('remove')))}")
    remove_users = cfg.get("remove_users") or []
    lines.append("remove_users: []" if not remove_users else "remove_users:")
    for user in remove_users:
        lines.append(f"- name: {user.get('name')}")
    atuin_login = str(cfg.get("atuin_login") or "")
    atuin_login = atuin_login.replace("'", "''")
    lines.append(f"atuin_login: '{atuin_login}'")
    lines.append(f"ssh_key: {cfg.get('ssh_key')}")
    return "\n".join(lines) + "\n"


def packages(data: dict[str, Any]) -> str:
    lines: list[str] = []
    for package, cli in data.get("packages") or []:
        lines.extend(
            [
                f"- name: Add devbox package {package}",
                "  args:",
                f"    creates: .local/share/devbox/global/default/.devbox/nix/profile/default/bin/{cli}",
                f"  ansible.builtin.shell: . /etc/profile.d/nix.sh && devbox global add --disable-plugin {package}",
            ]
        )
    return "\n".join(lines) + "\n"


def ssh_config(data: dict[str, Any]) -> str:
    lines: list[str] = []
    for host in data.get("hosts") or []:
        block = f"Host {host}\\n  Hostname {host}.afrino-bushi.ts.net\\n  User ubuntu\\n  ForwardAgent yes "
        lines.extend(
            [
                f"- name: Add a new host entry using blockinfile for {host}",
                "  ansible.builtin.blockinfile:",
                "    path: ~/.ssh/config",
                "    create: true",
                f"    block: \"{block}\"",
                f"    marker: '# {{mark}} ANSIBLE MANAGED BLOCK FOR {host}'",
                "    state: present",
            ]
        )
    return "\n".join(lines) + "\n"


def inventory(data: dict[str, Any]) -> str:
    sudoer = data.get("sudoer")
    hosts = data.get("hosts") or []
    users = [
        {**u, "host": host}
        for u in data.get("users") or []
        if not u.get("remove")
        for host in hosts
    ]
    admins = [
        {**a, "host": host, "name": sudoer}
        for a in [{"ansible_user": sudoer}]
        for host in hosts
    ]
    users_hosts = {
        f"{u.get('name')}@{u.get('host')}": {
            "ansible_host": u.get("host"),
            "ansible_user": u.get("name"),
            "uid": u.get("uid"),
        }
        for u in users
    }
    admins_hosts = {
        f"root@{a.get('host')}": {"ansible_host": a.get("host"), "ansible_user": a.get("name")}
        for a in admins
    }
    return clj_json({"all": {"children": {"admin": {"hosts": admins_hosts}, "users": {"hosts": users_hosts}}}})


def repos(data: dict[str, Any]) -> str:
    lines: list[str] = []
    for repo_data in data.get("repos") or []:
        user = repo_data.get("user")
        org = repo_data.get("org")
        repo = repo_data.get("repo")
        branch = repo_data.get("branch")
        when_p = f'inventory_hostname.startswith("{user}")'
        lines.extend(
            [
                f"- name: Clone repo {org}/{repo}",
                f"  ansible.builtin.shell: ssh -o StrictHostKeyChecking=accept-new git@github.com || true && git clone git@github.com:{org}/{repo} {repo}/{branch}",
                "  args:",
                "    chdir: code/personal",
                f"    creates: {repo}/{branch}",
                f"  when: {when_p}",
            ]
        )
        for worktree in repo_data.get("worktrees") or []:
            lines.extend(
                [
                    f"- name: Create the worktree {worktree} for repo {org}/{repo}",
                    f"  ansible.builtin.shell: git fetch --all --tags && git worktree add ../{worktree} {worktree}",
                    "  args:",
                    f"    chdir: code/personal/{repo}/{branch}",
                    f"    creates: ../{worktree}",
                    f"  when: {when_p}",
                ]
            )
    return "\n".join(lines) + "\n"


def render(target: str, data: dict[str, Any]) -> str:
    if target == "packages":
        return packages(data)
    if target == "repos":
        return repos(data)
    if target == "ssh-config":
        return ssh_config(data)
    if target == "inventory":
        return inventory(data)
    if target == "config":
        return config(data)
    raise ValueError(f"unknown render target: {target}")
