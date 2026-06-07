import { existsSync, readdirSync, readFileSync, rmSync, statSync } from "node:fs";
import { join, resolve } from "node:path";
import { describe, expect, test, vi } from "vitest";
import { EXIT, RENDER_PROFILE, WF_PARAMS } from "big-config";
import * as ansible from "../src/walter/ansible.js";
import { main } from "../src/cli.js";
import { describe as describeStep, describeReport } from "../src/walter/describe.js";
import { walter } from "../src/walter/options.js";
import { walterStar } from "../src/walter/package.js";
import * as v from "../src/walter/validation.js";
const TEST_COMPUTE_PUBKEY = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIHDKdUkY+SfRm6ttOz2EEZ2+i/zm+o1mpMOdMeGUr0t4 test@example.com";
function withCreds(opts) {
    const out = { ...opts };
    const params = { ...(out[WF_PARAMS] ?? {}) };
    Object.assign(params, {
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
    });
    out[WF_PARAMS] = params;
    out.params = params;
    return out;
}
function profile(providerCompute, providerBackend) {
    return withCreds({ [RENDER_PROFILE]: "walter", [WF_PARAMS]: { package: "walter", "provider-compute": providerCompute, "provider-backend": providerBackend } });
}
function walk(dir) {
    const out = [];
    for (const entry of readdirSync(dir)) {
        const p = join(dir, entry);
        const st = statSync(p);
        if (st.isDirectory())
            out.push(...walk(p));
        else if (st.isFile())
            out.push(p);
    }
    return out;
}
describe("validation", () => {
    test("active profiles pass schema with stub creds", () => {
        for (const p of [withCreds(walter), profile("hcloud", "r2"), profile("digitalocean", "s3"), profile("no-infra", "local")]) {
            expect(v.schemaErrors(p)).toBeNull();
        }
    });
    test("placeholder credential is reported", () => {
        const p = profile("hcloud", "local");
        p[WF_PARAMS]["hcloud-token"] = "REPLACE_ME";
        const errors = v.schemaErrors(p) ?? [];
        expect(errors.some((e) => e.detail.includes("hcloud-token") && e.detail.includes("REPLACE_ME"))).toBe(true);
    });
    test("env string scalars are accepted", () => {
        const p = profile("oci", "local");
        p[WF_PARAMS]["oci-ocpus"] = "2";
        expect(v.schemaErrors(p)).toBeNull();
        const p2 = profile("no-infra", "local");
        p2[WF_PARAMS]["compute-prevent-destroy"] = "false";
        expect(v.schemaErrors(p2)).toBeNull();
    });
    test("validate workflow step sets exit status", () => {
        const ok = v.validate([], {}, () => ({ ok: true, errors: [] }));
        expect(ok[EXIT]).toBe(0);
        const bad = v.validate([], {}, () => ({ ok: false, errors: [{ check: "schema", detail: "bad" }] }));
        expect(bad[EXIT]).toBe(1);
        expect(bad.err).toBe("validation failed");
    });
    test("provider tools picks right clis", () => {
        expect(new Set(v.providerTools({ "provider-compute": "hcloud", "provider-backend": "s3" }).map((t) => t.cmd))).toEqual(new Set(["hcloud", "aws"]));
        expect(v.providerTools({ "provider-compute": "no-infra", "provider-backend": "local" }).map((t) => t.cmd)).toEqual([]);
    });
    test("tool errors honors injected which fn", () => {
        const errors = v.toolErrors(profile("hcloud", "s3")[WF_PARAMS], (cmd) => cmd !== "tofu");
        expect(errors).toHaveLength(1);
        expect(errors[0].detail).toContain("OpenTofu");
    });
    test("ssh-agent checks cloud and skips no-infra", () => {
        const params = { "provider-compute": "hcloud", "compute-pubkey": TEST_COMPUTE_PUBKEY };
        expect(v.sshAgentErrors(params, {})[0]).toContain("SSH_AUTH_SOCK");
        expect(v.sshAgentErrors({ ...params, "provider-compute": "no-infra" }, {})).toEqual([]);
        const keyIdLine = TEST_COMPUTE_PUBKEY.split(/\s+/).slice(0, 2).join(" ");
        const runFn = vi.fn((_args, _env) => ({ ok: true, exit: 0, out: `${keyIdLine} other-comment\n`, err: "" }));
        expect(v.sshAgentErrors(params, { SSH_AUTH_SOCK: "/tmp/agent.sock" }, runFn)).toEqual([]);
    });
    test("r2 head bucket errors are classified", () => {
        expect(v.classifyHeadBucketError("An error occurred (404): Not Found")).toBe("missing-bucket");
        expect(v.classifyHeadBucketError("An error occurred (403): Forbidden")).toBe("bad-credentials");
        expect(v.classifyHeadBucketError("connection reset")).toBe("unknown");
    });
    test("ansible data checks compute pubkey", () => {
        expect(v.ansibleDataErrors(profile("no-infra", "local"))).toEqual([]);
        const p = profile("no-infra", "local");
        delete p[WF_PARAMS]["compute-pubkey"];
        const errors = v.ansibleDataErrors(p);
        expect(errors.some((e) => e.detail.includes(":ssh_key"))).toBe(true);
    });
});
describe("describe", () => {
    const baseOpts = {
        [RENDER_PROFILE]: "walter-test",
        [WF_PARAMS]: {
            "provider-compute": "no-infra",
            "provider-backend": "local",
            package: "walter",
            ip: "203.0.113.10",
            user: "ubuntu",
            sudoer: "root",
            "compute-pubkey": TEST_COMPUTE_PUBKEY,
        },
    };
    test("summarizes a reachable workstation", () => {
        const calls = [];
        const result = describeReport(baseOpts, (args, opts) => {
            calls.push([args, opts]);
            return { ok: true, exit: 0, out: "", err: "" };
        }, (opts) => opts);
        expect(result.profile).toBe("walter-test");
        expect(result.providers).toEqual({ compute: "no-infra", backend: "local" });
        expect(result.compute.running).toBe(true);
        expect(result.compute.detail).toBe("ssh ok");
        expect(result.workstation.hosts).toEqual(["203.0.113.10"]);
        expect(result.workstation.sudoer).toBe("root");
        expect(result.workstation.repoCount).toBeGreaterThan(0);
        expect(result.workstation.packageCount).toBeGreaterThan(0);
        expect(calls[0][0][0]).toBe("ssh");
    });
    test("soft-fails unreachable ssh", () => {
        const result = describeReport(baseOpts, () => ({ ok: false, exit: 255, out: "", err: "connection refused" }), (opts) => opts);
        expect(result.compute.running).toBe(false);
        expect(result.compute.detail).toContain("connection refused");
        expect(result.fatalError).toBe(false);
    });
    test("workflow step sets exit status", () => {
        const log = vi.spyOn(console, "log").mockImplementation(() => undefined);
        const result = describeStep([], {}, () => ({ profile: "test", providers: {}, compute: {}, workstation: {}, fatalError: false }));
        expect(result.exit).toBe(0);
        expect(result["describe/result"].profile).toBe("test");
        log.mockRestore();
    });
});
describe("cli", () => {
    test("exposes package workflow verbs and rejects top-level validate", () => {
        const exit = vi.spyOn(process, "exit").mockImplementation(((code) => { throw new Error(`exit:${code}`); }));
        const err = vi.spyOn(console, "error").mockImplementation(() => undefined);
        expect(() => main(["validate"], walter)).toThrow("exit:1");
        const output = err.mock.calls.flat().join("\n");
        for (const command of ["validate", "describe", "build", "create", "delete", "lock", "git-check", "git-push", "unlock-any"]) {
            expect(output).toContain(command);
        }
        expect(output).toContain("walter package validate");
        expect(output).toContain("walter package describe");
        expect(output).toContain("git-check lock render");
        exit.mockRestore();
        err.mockRestore();
    });
});
describe("rendering", () => {
    test("ansible render matches reference generated files", () => {
        const ref = resolve("../clojure/.dist/walter-7b467017/io/github/bigconfig-ai/walter/tools/ansible");
        if (!existsSync(ref))
            return;
        const data = ansible.dataFn({ ip: "192.168.0.1", "compute-pubkey": "REPLACE_ME" });
        expect(ansible.inventory(data)).toBe(readFileSync(join(ref, "inventory.json"), "utf8"));
        expect(ansible.config(data)).toBe(readFileSync(join(ref, "default.config.yml"), "utf8"));
        expect(ansible.packages(data)).toBe(readFileSync(join(ref, "roles/users/tasks/packages.yml"), "utf8"));
        expect(ansible.repos(data)).toBe(readFileSync(join(ref, "roles/users/tasks/repos.yml"), "utf8"));
        expect(ansible.sshConfig(data)).toBe(readFileSync(join(ref, "roles/users/tasks/ssh-config.yml"), "utf8"));
    });
    test("package build matches clojure reference", () => {
        const ref = resolve("../clojure/.dist/walter-7b467017");
        if (!existsSync(ref))
            return;
        const target = resolve(".dist/walter-7b467017");
        rmSync(target, { recursive: true, force: true });
        walterStar(["build"], walter);
        const refFiles = walk(ref).map((p) => p.slice(ref.length + 1)).sort();
        const targetFiles = walk(target).map((p) => p.slice(target.length + 1)).sort();
        expect(targetFiles).toEqual(refFiles);
        for (const rel of refFiles) {
            expect(readFileSync(join(target, rel))).toEqual(readFileSync(join(ref, rel)));
        }
    });
});
//# sourceMappingURL=validation.test.js.map