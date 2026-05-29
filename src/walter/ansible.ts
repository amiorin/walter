/** Walter-specific Ansible data and generated files. */

export function dataFn(data: Record<string, any>): Record<string, any> {
  const sudoer = data.sudoer ?? "root";
  const mainUser = "ubuntu";
  const hosts = [data.ip ?? "77.42.91.213"];
  const users = [
    {
      name: mainUser,
      uid: data.uid ?? "1000",
      doomemacs: "dd72eac1971616a6ebe81067cca33b14c148cbcd",
      remove: false,
    },
  ];
  const config = {
    users: users.filter((u) => !u.remove),
    remove_users: users.filter((u) => u.remove),
    atuin_login: "{{ lookup('ansible.builtin.env', 'ATUIN_LOGIN') }}",
    ssh_key: data["compute-pubkey"] ?? "REPLACE_ME",
  };

  const repos: Record<string, any>[] = [];
  for (const [repo, worktrees] of [
    ["dotfiles-v3", []],
    ["albertomiorin.com", ["albertomiorin"]],
    ["big-container", []],
    ["alice", []],
  ] as const) {
    repos.push({ user: mainUser, org: "amiorin", repo, branch: "main", worktrees });
  }
  for (const [repo, worktrees] of [
    ["basecamp-once", []],
    ["big-config", []],
    ["once", []],
    ["once-ai", []],
    ["once-bigconfig", []],
    ["once-bigconfig-marketplace", []],
    ["once-caddy-redirect", []],
    ["once-forms", []],
    ["walter", []],
  ] as const) {
    repos.push({ user: mainUser, org: "bigconfig-ai", repo, branch: "main", worktrees });
  }

  const packageNames = [
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
  ];
  const packages = [["ripgrep", "rg"], ...packageNames.map((x) => [x, x])];

  return { ...data, repos, sudoer, hosts, users, config, packages };
}

function cljJson(value: any, indent = 0): string {
  const sp = " ".repeat(indent);
  const child = " ".repeat(indent + 2);
  if (Array.isArray(value)) {
    if (value.length === 0) return "[ ]";
    return [
      "[",
      ...value.map((v, i) => `${child}${cljJson(v, indent + 2)}${i < value.length - 1 ? "," : ""}`),
      `${sp}]`,
    ].join("\n");
  }
  if (value && typeof value === "object") {
    const entries = Object.entries(value);
    if (entries.length === 0) return "{ }";
    return [
      "{",
      ...entries.map(([k, v], i) => `${child}${JSON.stringify(String(k))} : ${cljJson(v, indent + 2)}${i < entries.length - 1 ? "," : ""}`),
      `${sp}}`,
    ].join("\n");
  }
  if (typeof value === "string") return JSON.stringify(value);
  if (value === true) return "true";
  if (value === false) return "false";
  if (value == null) return "null";
  return String(value);
}

export function config(data: Record<string, any>): string {
  const cfg = data.config ?? {};
  const lines: string[] = ["users:"];
  for (const user of cfg.users ?? []) {
    lines.push(`- name: ${user.name}`);
    lines.push(`  uid: '${user.uid}'`);
    lines.push(`  doomemacs: ${user.doomemacs}`);
    lines.push(`  remove: ${user.remove ? "true" : "false"}`);
  }
  const removeUsers = cfg.remove_users ?? [];
  lines.push(removeUsers.length === 0 ? "remove_users: []" : "remove_users:");
  for (const user of removeUsers) lines.push(`- name: ${user.name}`);
  const atuinLogin = String(cfg.atuin_login ?? "").replace(/'/g, "''");
  lines.push(`atuin_login: '${atuinLogin}'`);
  lines.push(`ssh_key: ${cfg.ssh_key}`);
  return `${lines.join("\n")}\n`;
}

export function packages(data: Record<string, any>): string {
  const lines: string[] = [];
  for (const [pkg, cli] of data.packages ?? []) {
    lines.push(`- name: Add devbox package ${pkg}`);
    lines.push("  args:");
    lines.push(`    creates: .local/share/devbox/global/default/.devbox/nix/profile/default/bin/${cli}`);
    lines.push(`  ansible.builtin.shell: . /etc/profile.d/nix.sh && devbox global add --disable-plugin ${pkg}`);
  }
  return `${lines.join("\n")}\n`;
}

export function sshConfig(data: Record<string, any>): string {
  const lines: string[] = [];
  for (const host of data.hosts ?? []) {
    const block = `Host ${host}\\n  Hostname ${host}.afrino-bushi.ts.net\\n  User ubuntu\\n  ForwardAgent yes `;
    lines.push(`- name: Add a new host entry using blockinfile for ${host}`);
    lines.push("  ansible.builtin.blockinfile:");
    lines.push("    path: ~/.ssh/config");
    lines.push("    create: true");
    lines.push(`    block: "${block}"`);
    lines.push(`    marker: '# {mark} ANSIBLE MANAGED BLOCK FOR ${host}'`);
    lines.push("    state: present");
  }
  return `${lines.join("\n")}\n`;
}

export function inventory(data: Record<string, any>): string {
  const { sudoer } = data;
  const hosts = data.hosts ?? [];
  const users = (data.users ?? [])
    .filter((u: any) => !u.remove)
    .flatMap((u: any) => hosts.map((host: string) => ({ ...u, host })));
  const admins = [{ ansible_user: sudoer }].flatMap((a) =>
    hosts.map((host: string) => ({ ...a, host, name: sudoer })),
  );
  const usersHosts: Record<string, any> = {};
  for (const u of users) {
    usersHosts[`${u.name}@${u.host}`] = {
      ansible_host: u.host,
      ansible_user: u.name,
      uid: u.uid,
    };
  }
  const adminsHosts: Record<string, any> = {};
  for (const a of admins) {
    adminsHosts[`root@${a.host}`] = { ansible_host: a.host, ansible_user: a.name };
  }
  return cljJson({ all: { children: { admin: { hosts: adminsHosts }, users: { hosts: usersHosts } } } });
}

export function repos(data: Record<string, any>): string {
  const lines: string[] = [];
  for (const r of data.repos ?? []) {
    const whenP = `inventory_hostname.startswith("${r.user}")`;
    lines.push(`- name: Clone repo ${r.org}/${r.repo}`);
    lines.push(`  ansible.builtin.shell: ssh -o StrictHostKeyChecking=accept-new git@github.com || true && git clone git@github.com:${r.org}/${r.repo} ${r.repo}/${r.branch}`);
    lines.push("  args:");
    lines.push("    chdir: code/personal");
    lines.push(`    creates: ${r.repo}/${r.branch}`);
    lines.push(`  when: ${whenP}`);
    for (const worktree of r.worktrees ?? []) {
      lines.push(`- name: Create the worktree ${worktree} for repo ${r.org}/${r.repo}`);
      lines.push(`  ansible.builtin.shell: git fetch --all --tags && git worktree add ../${worktree} ${worktree}`);
      lines.push("  args:");
      lines.push(`    chdir: code/personal/${r.repo}/${r.branch}`);
      lines.push(`    creates: ../${worktree}`);
      lines.push(`  when: ${whenP}`);
    }
  }
  return `${lines.join("\n")}\n`;
}

export function render(target: string, data: Record<string, any>): string {
  switch (target) {
    case "packages": return packages(data);
    case "repos": return repos(data);
    case "ssh-config": return sshConfig(data);
    case "inventory": return inventory(data);
    case "config": return config(data);
    default: throw new Error(`unknown render target: ${target}`);
  }
}
