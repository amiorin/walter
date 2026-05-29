/** Pre-flight validation for the active Walter profile. */
import { existsSync } from "node:fs";
import { homedir } from "node:os";
import { spawnSync } from "node:child_process";
import { readBcPars } from "big-config/workflow";
import { tofuParams } from "once/dist/src/once/params.js";
import * as ansible from "./ansible.js";
import { okAlias, paramsOf, profileOf, status, toBcOpts } from "./interop.js";
const sshPubkeyRx = /^ssh-(ed25519|rsa|dss|ecdsa) [A-Za-z0-9+/=]+( .*)?$/;
const PLACEHOLDER = "REPLACE_ME";
const PLACEHOLDER_MSG = "must replace REPLACE_ME with a real value";
function isPlaceholder(v) {
    return typeof v === "string" && v.includes(PLACEHOLDER);
}
function blankOrPlaceholder(v) {
    return v == null || (typeof v === "string" && (v.trim() === "" || isPlaceholder(v)));
}
function realValue(v) {
    return !blankOrPlaceholder(v);
}
const stringValue = (v) => {
    if (typeof v !== "string")
        return "should be a string";
    if (isPlaceholder(v))
        return PLACEHOLDER_MSG;
    if (v.trim() === "")
        return "must be a non-empty string";
    return null;
};
const nonEmptyString = stringValue;
const intValue = (v) => {
    if (isPlaceholder(v))
        return PLACEHOLDER_MSG;
    if (typeof v === "number" && Number.isInteger(v))
        return null;
    if (typeof v === "string" && /^-?\d+$/.test(v))
        return null;
    return "should be an integer";
};
const booleanValue = (v) => {
    if (typeof v === "boolean" || v === "true" || v === "false")
        return null;
    return "should be a boolean";
};
function reCheck(rx, msg) {
    return (v) => {
        if (typeof v !== "string")
            return "should be a string";
        if (isPlaceholder(v))
            return PLACEHOLDER_MSG;
        if (!rx.test(v))
            return msg;
        return null;
    };
}
function required(obj, key, check, emit, prefix = "workflow/params") {
    if (!(key in obj)) {
        emit(`${prefix} → ${key}`, "missing required key");
        return;
    }
    const msg = check(obj[key]);
    if (msg)
        emit(`${prefix} → ${key}`, msg);
}
function checkBaseParams(params, emit) {
    required(params, "package", nonEmptyString, emit);
    required(params, "compute-pubkey", reCheck(sshPubkeyRx, "must look like an SSH public key"), emit);
    if ("compute-prevent-destroy" in params) {
        const msg = booleanValue(params["compute-prevent-destroy"]);
        if (msg)
            emit("workflow/params → compute-prevent-destroy", msg);
    }
}
function checkBackend(params, emit) {
    switch (params["provider-backend"]) {
        case "s3":
            required(params, "s3-bucket", stringValue, emit);
            required(params, "s3-region", stringValue, emit);
            break;
        case "r2":
            required(params, "r2-bucket", nonEmptyString, emit);
            required(params, "r2-endpoint", nonEmptyString, emit);
            required(params, "r2-access-key-id", nonEmptyString, emit);
            required(params, "r2-secret-access-key", nonEmptyString, emit);
            break;
        case "local":
            break;
        default:
            emit("workflow/params → provider-backend", "invalid dispatch value");
    }
}
function checkCompute(params, emit) {
    switch (params["provider-compute"]) {
        case "oci":
            for (const k of ["oci-config-file-profile", "oci-subnet-id", "oci-compartment-id", "oci-availability-domain", "oci-display-name", "oci-shape", "oci-ssh-authorized-keys"]) {
                required(params, k, stringValue, emit);
            }
            for (const k of ["oci-ocpus", "oci-memory-in-gbs", "oci-boot-volume-size-in-gbs", "oci-boot-volume-vpus-per-gb"]) {
                required(params, k, intValue, emit);
            }
            break;
        case "hcloud":
            for (const k of ["hcloud-name", "hcloud-image", "hcloud-server-type", "hcloud-location", "hcloud-ssh-keys", "hcloud-token"]) {
                required(params, k, stringValue, emit);
            }
            break;
        case "digitalocean":
            for (const k of ["digitalocean-name", "digitalocean-region", "digitalocean-size", "digitalocean-image", "digitalocean-vpc-uuid", "digitalocean-ssh-keys", "do-token"]) {
                required(params, k, stringValue, emit);
            }
            break;
        case "no-infra":
            for (const k of ["no-infra-compute-ip", "no-infra-compute-user", "no-infra-compute-sudoer", "no-infra-compute-uid"]) {
                required(params, k, stringValue, emit);
            }
            break;
        default:
            emit("workflow/params → provider-compute", "invalid dispatch value");
    }
}
export function schemaErrors(opts) {
    const errors = [];
    const emit = (path, msg) => errors.push({ check: "schema", detail: `${path}: ${msg}` });
    const profileMsg = stringValue(profileOf(opts));
    if (profileMsg)
        emit("render/profile", profileMsg);
    const params = paramsOf(opts);
    if (!params || typeof params !== "object")
        emit("workflow/params", "missing required key");
    else {
        checkBaseParams(params, emit);
        checkCompute(params, emit);
        checkBackend(params, emit);
    }
    return errors.length ? errors : null;
}
const AWS_HINT = "https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html";
export const baseTools = [
    { cmd: "tofu", name: "OpenTofu", hint: "https://opentofu.org/docs/intro/install/" },
    { cmd: "ansible-playbook", name: "Ansible", hint: "pipx install ansible" },
    { cmd: "ssh", name: "OpenSSH", hint: "your distro's openssh-client package" },
    { cmd: "curl", name: "curl", hint: "your distro's curl package" },
];
export function providerTools(params) {
    const tools = [];
    const compute = params["provider-compute"];
    const backend = params["provider-backend"];
    if (compute === "oci")
        tools.push({ cmd: "oci", name: "OCI CLI", hint: "pip install oci-cli" });
    if (compute === "hcloud")
        tools.push({ cmd: "hcloud", name: "hcloud", hint: "https://github.com/hetznercloud/cli" });
    if (compute === "digitalocean")
        tools.push({ cmd: "doctl", name: "doctl", hint: "https://docs.digitalocean.com/reference/doctl/how-to/install/" });
    if (backend === "s3" || backend === "r2")
        tools.push({ cmd: "aws", name: "AWS CLI", hint: AWS_HINT });
    return tools;
}
export function which(cmd) {
    const res = spawnSync("which", [cmd], { stdio: "ignore" });
    return !res.error && res.status === 0;
}
export function toolErrors(params, whichFn = which) {
    return [...baseTools, ...providerTools(params)]
        .filter((t) => !whichFn(t.cmd))
        .map((t) => ({ check: "tool", detail: `${t.name} not found on PATH. Install: ${t.hint}` }));
}
const RUN_TIMEOUT_MS = 30000;
export function run(args, extraEnv) {
    try {
        const [cmd, ...rest] = args;
        const res = spawnSync(cmd, rest, {
            input: "",
            encoding: "utf8",
            timeout: RUN_TIMEOUT_MS,
            env: { ...process.env, ...(extraEnv ?? {}) },
        });
        if (res.error)
            return { ok: false, exit: -1, out: res.stdout || "", err: res.stderr || res.error.message };
        const statusCode = res.status ?? 0;
        return { ok: statusCode === 0, exit: statusCode, out: res.stdout || "", err: res.stderr || "" };
    }
    catch (e) {
        return { ok: false, exit: -1, out: "", err: String(e?.message ?? e) };
    }
}
function trimSnippet(s) {
    const t = (s ?? "").trim();
    if (t === "")
        return null;
    return t.length > 200 ? `${t.slice(0, 200)}…` : t;
}
function bearerCheck(label, url, token, runFn) {
    const r = runFn(["curl", "-sf", "-o", "/dev/null", "-H", `Authorization: Bearer ${token}`, url]);
    if (r.ok)
        return null;
    const snippet = trimSnippet(r.err);
    return `${label}: token rejected (curl exit ${r.exit})${snippet ? ` — ${snippet}` : ""}`;
}
function cliCheck(label, args, extraEnv, runFn) {
    const r = runFn(args, extraEnv);
    if (r.ok)
        return null;
    return `${label}: ${trimSnippet(r.err) || "command failed"}`;
}
function ociConfigPath() {
    return process.env.OCI_CLI_CONFIG_FILE || process.env.OCI_CONFIG_FILE || `${homedir()}/.oci/config`;
}
function ociConfigError() {
    const p = ociConfigPath();
    if (!existsSync(p))
        return `OCI: config file not found at ${p} — run 'oci setup config' to create one`;
    return null;
}
export function classifyHeadBucketError(err) {
    const s = (err || "").toLowerCase();
    if (s.includes("(404)") || s.includes("not found") || s.includes("nosuchbucket"))
        return "missing-bucket";
    if (["(401)", "(403)", "forbidden", "unauthorized", "invalidaccesskey", "signaturedoesnotmatch"].some((x) => s.includes(x)))
        return "bad-credentials";
    return "unknown";
}
export function r2Errors(params, runFn) {
    const bucket = params["r2-bucket"];
    const endpoint = params["r2-endpoint"];
    const accessKey = params["r2-access-key-id"];
    const secretKey = params["r2-secret-access-key"];
    const missing = [];
    if (blankOrPlaceholder(endpoint))
        missing.push("r2-endpoint");
    if (blankOrPlaceholder(bucket))
        missing.push("r2-bucket");
    if (blankOrPlaceholder(accessKey))
        missing.push("r2-access-key-id");
    if (blankOrPlaceholder(secretKey))
        missing.push("r2-secret-access-key");
    if (missing.length)
        return [`R2: missing or placeholder credentials: ${missing.join(", ")}`];
    if (!which("aws"))
        return [];
    const r = runFn(["aws", "s3api", "head-bucket", "--bucket", String(bucket), "--endpoint-url", String(endpoint)], {
        AWS_ACCESS_KEY_ID: String(accessKey),
        AWS_SECRET_ACCESS_KEY: String(secretKey),
        AWS_DEFAULT_REGION: "auto",
    });
    if (r.ok)
        return [];
    const snippet = trimSnippet(r.err) || "head-bucket failed";
    const kind = classifyHeadBucketError(r.err);
    if (kind === "missing-bucket")
        return [`R2 (bucket): ${bucket} not found at ${endpoint} — ${snippet}`];
    if (kind === "bad-credentials")
        return [`R2 (auth): credentials rejected at ${endpoint} — ${snippet}`];
    return [`R2: head-bucket on ${bucket} at ${endpoint} failed — ${snippet}`];
}
const CLOUD_COMPUTE_PROVIDERS = new Set(["oci", "hcloud", "digitalocean"]);
function cloudCompute(params) {
    return CLOUD_COMPUTE_PROVIDERS.has(params["provider-compute"]);
}
function sshPubkeyIdentity(s) {
    const parts = (s ?? "").trim().split(/\s+/);
    if (parts.length >= 2 && parts[0] && parts[1])
        return `${parts[0]} ${parts[1]}`;
    return null;
}
export function sshAgentErrors(params, env, runFn = run) {
    if (!cloudCompute(params))
        return [];
    const computePubkey = params["compute-pubkey"];
    const sock = (env.SSH_AUTH_SOCK ?? "").trim();
    if (isPlaceholder(computePubkey))
        return ["SSH agent: :compute-pubkey still contains REPLACE_ME"];
    if (sock === "")
        return ["SSH agent: SSH_AUTH_SOCK is not set; start ssh-agent and run ssh-add for :compute-pubkey"];
    const r = runFn(["ssh-add", "-L"], { SSH_AUTH_SOCK: sock });
    const wanted = sshPubkeyIdentity(typeof computePubkey === "string" ? computePubkey : null);
    const agentMsg = `${r.err}\n${r.out}`;
    if (wanted == null)
        return ["SSH agent: :compute-pubkey is not a parseable SSH public key"];
    if (r.ok) {
        const loaded = new Set((r.out || "").split(/\r?\n/).map((line) => sshPubkeyIdentity(line)).filter((x) => x != null));
        if (loaded.has(wanted))
            return [];
        return [`SSH agent: :compute-pubkey is not loaded in ssh-agent at SSH_AUTH_SOCK=${sock}`];
    }
    if (agentMsg.toLowerCase().includes("no identities"))
        return [`SSH agent: :compute-pubkey is not loaded; the agent at SSH_AUTH_SOCK=${sock} has no identities`];
    const snippet = trimSnippet(r.err);
    return [`SSH agent: ssh-add -L failed for SSH_AUTH_SOCK=${sock} (exit ${r.exit})${snippet ? ` — ${snippet}` : ""}`];
}
export function credentialErrors(params, env = process.env, runFn = run) {
    const pCompute = params["provider-compute"];
    const pBackend = params["provider-backend"];
    const single = [
        pCompute === "hcloud" && realValue(params["hcloud-token"]) ? bearerCheck("Hetzner Cloud API", "https://api.hetzner.cloud/v1/server_types", params["hcloud-token"], runFn) : null,
        pCompute === "digitalocean" && realValue(params["do-token"]) ? bearerCheck("DigitalOcean API", "https://api.digitalocean.com/v2/account", params["do-token"], runFn) : null,
        pCompute === "oci" && which("oci") ? (ociConfigError() || cliCheck("OCI", ["oci", "iam", "region", "list", "--output", "json"], undefined, runFn)) : null,
        pBackend === "s3" && which("aws") ? cliCheck("AWS (S3 backend)", ["aws", "sts", "get-caller-identity"], undefined, runFn) : null,
    ].filter((x) => x != null);
    const multi = [...(pBackend === "r2" ? r2Errors(params, runFn) : []), ...sshAgentErrors(params, env, runFn)];
    return [...single, ...multi].map((detail) => ({ check: "credential", detail }));
}
function ansibleDataError(detail) {
    return { check: "ansible-data", detail };
}
export function ansibleDataErrors(opts) {
    try {
        const params = paramsOf(tofuParams(opts));
        const data = ansible.dataFn(params);
        const hosts = data.hosts ?? [];
        const users = data.users ?? [];
        const activeUsers = users.filter((u) => !u.remove);
        const configKey = data.config?.ssh_key;
        const computeKey = params["compute-pubkey"];
        const repos = data.repos ?? [];
        const packages = data.packages ?? [];
        const messages = [];
        if (hosts.length === 0)
            messages.push("Ansible hosts: at least one host is required");
        hosts.forEach((host, i) => { if (blankOrPlaceholder(host))
            messages.push(`Ansible hosts[${i}]: host must be a real value`); });
        if (activeUsers.length === 0)
            messages.push("Ansible users: at least one active user is required");
        users.forEach((u, i) => { if (blankOrPlaceholder(u.name) || blankOrPlaceholder(u.uid))
            messages.push(`Ansible users[${i}]: :name and :uid must be real values`); });
        if (!activeUsers.some((u) => u.name === "ubuntu"))
            messages.push("Ansible users: active users must include ubuntu");
        if (blankOrPlaceholder(configKey))
            messages.push("Ansible config: :ssh_key must come from :compute-pubkey");
        else if (typeof configKey !== "string" || !sshPubkeyRx.test(configKey))
            messages.push("Ansible config: :ssh_key must look like an SSH public key");
        else if (realValue(computeKey) && String(configKey).trim() !== String(computeKey).trim())
            messages.push("Ansible config: :ssh_key must match :compute-pubkey");
        repos.forEach((r, i) => { if (["org", "repo", "branch", "user"].some((k) => blankOrPlaceholder(r[k])))
            messages.push(`Ansible repos[${i}]: :org, :repo, :branch, and :user must be real values`); });
        packages.forEach((p, i) => { if (!Array.isArray(p) || p.length < 2 || blankOrPlaceholder(p[0]) || blankOrPlaceholder(p[1]))
            messages.push(`Ansible packages[${i}]: package and CLI names must be real values`); });
        return messages.map(ansibleDataError);
    }
    catch (e) {
        return [ansibleDataError(`failed to build Walter Ansible data — ${e?.message ?? e}`)];
    }
}
export function validateReport(opts, env = process.env) {
    const merged = readBcPars(toBcOpts(opts), env);
    const params = paramsOf(merged);
    const errors = [
        ...(schemaErrors(merged) ?? []),
        ...toolErrors(params),
        ...credentialErrors(params, env),
        ...ansibleDataErrors(merged),
    ];
    return { ok: errors.length === 0, errors };
}
function groupName(k) {
    return { schema: "Schema", tool: "Tools", credential: "Credentials", "ansible-data": "Ansible data" }[k] ?? String(k);
}
export function printReport(result) {
    if (result.ok) {
        console.log("All checks passed.");
        return;
    }
    console.log(`Validation failed (${result.errors.length} issue${result.errors.length === 1 ? "" : "s"}):`);
    for (const k of ["schema", "tool", "credential", "ansible-data"]) {
        const es = result.errors.filter((e) => e.check === k);
        if (es.length === 0)
            continue;
        console.log("");
        console.log(`  ${groupName(k)}:`);
        for (const e of es)
            console.log(`    - ${e.detail}`);
    }
}
export function validate(_stepFns, opts, reportFn = validateReport) {
    const result = reportFn(opts);
    printReport(result);
    const base = { ...opts, "validation/result": result };
    return result.ok ? okAlias(base) : status(base, 1, "validation failed");
}
//# sourceMappingURL=validation.js.map