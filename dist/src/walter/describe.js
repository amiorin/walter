/** Walter post-provisioning describe report. */
import { spawnSync } from "node:child_process";
import { ok } from "big-config";
import { dataFn } from "./ansible.js";
import { PARAMS, PROFILE, status, syncAliases, toBcOpts } from "./interop.js";
import { walterOpts } from "./params.js";
const RUN_TIMEOUT_MS = 30_000;
const SSH_PROBE_TIMEOUT_MS = 10_000;
export const run = (args, opts = {}) => {
    try {
        const result = spawnSync(args[0] ?? "", args.slice(1), {
            input: "",
            encoding: "utf8",
            timeout: opts["timeout-ms"] ?? opts.timeout ?? RUN_TIMEOUT_MS,
            env: opts["extra-env"] ? { ...process.env, ...opts["extra-env"] } : process.env,
        });
        const exit = result.status ?? (result.error ? -1 : 0);
        return {
            ok: exit === 0,
            exit,
            out: result.stdout ?? "",
            err: result.stderr || result.error?.message || "",
        };
    }
    catch (error) {
        return { ok: false, exit: -1, out: "", err: error instanceof Error ? error.message : String(error) };
    }
};
function trimSnippet(s) {
    const text = String(s ?? "").trim();
    if (text.length === 0)
        return undefined;
    return text.length > 200 ? `${text.slice(0, 200)}…` : text;
}
function resultDetail(label, result) {
    const snippet = trimSnippet(result.err) ?? trimSnippet(result.out);
    return `${label} failed (exit ${result.exit ?? -1})${snippet ? ` — ${snippet}` : ""}`;
}
export function providerSummary(params) {
    return { compute: params["provider-compute"], backend: params["provider-backend"] };
}
function computeTarget(params) {
    let ip = params.ip;
    if (params["provider-compute"] === "no-infra" && (!ip || ip === "192.168.0.1") && params["no-infra-compute-ip"]) {
        ip = params["no-infra-compute-ip"];
    }
    return {
        ip,
        user: params.user ?? params["no-infra-compute-user"] ?? params.sudoer ?? params["no-infra-compute-sudoer"] ?? "root",
    };
}
function sshBaseArgs(compute) {
    return [
        "ssh",
        "-o", "BatchMode=yes",
        "-o", "ConnectTimeout=5",
        "-o", "StrictHostKeyChecking=accept-new",
        `${compute.user}@${compute.ip}`,
    ];
}
function sshRun(runFn, compute, remoteArgs) {
    return runFn([...sshBaseArgs(compute), ...remoteArgs], { "timeout-ms": SSH_PROBE_TIMEOUT_MS });
}
export function computeStatus(runFn, params) {
    const target = computeTarget(params);
    if (!target.ip)
        return { ...target, running: false, detail: "missing IP address" };
    const result = sshRun(runFn, target, ["true"]);
    const detail = result.ok
        ? "ssh ok"
        : `${resultDetail("ssh", result)}${target.ip === "192.168.0.1" ? "; no Tofu output found or host is down" : ""}`;
    return { ...target, running: Boolean(result.ok), detail };
}
function resolveWalterOpts(opts, walterOptsFn) {
    try {
        return { opts: walterOptsFn(opts) };
    }
    catch (error) {
        return { opts, detail: `could not resolve OpenTofu parameters: ${error instanceof Error ? error.message : String(error)}` };
    }
}
function workstationSummary(params) {
    const data = dataFn(params);
    return {
        hosts: [...(data.hosts ?? [])],
        sudoer: data.sudoer,
        users: (data.users ?? []).map((u) => u.name),
        repoCount: (data.repos ?? []).length,
        packageCount: (data.packages ?? []).length,
    };
}
export function describeReport(opts, runFn = run, walterOptsFn = walterOpts) {
    const resolved = resolveWalterOpts(toBcOpts(opts), walterOptsFn);
    const withAliases = toBcOpts(resolved.opts);
    const params = { ...(withAliases[PARAMS] ?? {}) };
    const compute = computeStatus(runFn, params);
    if (resolved.detail)
        compute.detail = `${compute.detail ?? ""}; ${resolved.detail}`;
    return {
        profile: withAliases[PROFILE],
        providers: providerSummary(params),
        compute,
        workstation: workstationSummary(params),
        fatalError: false,
    };
}
function present(x) {
    return x == null || String(x).trim() === "" ? "unknown" : String(x);
}
function joinPresent(xs) {
    const values = (xs ?? []).map((x) => String(x)).filter((x) => x.trim() !== "");
    return values.length === 0 ? "unknown" : values.join(", ");
}
function printReport(result) {
    const providers = result.providers ?? {};
    const compute = result.compute ?? {};
    const workstation = result.workstation ?? {};
    console.log(`Profile: ${present(result.profile)}`);
    console.log("");
    console.log("Providers:");
    console.log(`  Compute: ${present(providers.compute)}`);
    console.log(`  Backend: ${present(providers.backend)}`);
    console.log("");
    console.log("Compute:");
    console.log(`  IP: ${present(compute.ip)}`);
    console.log(`  SSH user: ${present(compute.user)}`);
    console.log(`  Status: ${compute.running ? "running" : "not reachable"}${compute.detail ? ` (${compute.detail})` : ""}`);
    console.log("");
    console.log("Workstation:");
    console.log(`  Hosts: ${joinPresent(workstation.hosts)}`);
    console.log(`  Sudoer: ${present(workstation.sudoer)}`);
    console.log(`  Users: ${joinPresent(workstation.users)}`);
    console.log(`  Repositories: ${workstation.repoCount ?? 0}`);
    console.log(`  Packages: ${workstation.packageCount ?? 0}`);
}
export function describe(_stepFns, opts, reportFn = describeReport) {
    const result = reportFn(opts);
    printReport(result);
    const base = { ...syncAliases(opts), "describe/result": result };
    return result.fatalError ? status(base, 1, "describe failed") : syncAliases(ok(base));
}
//# sourceMappingURL=describe.js.map