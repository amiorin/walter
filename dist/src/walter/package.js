/** High-level Walter build/create/delete workflows and package entry point. */
import { ENV, WF_BUILD_FN, WF_CREATE_FN, WF_DELETE_FN, WF_PARAMS, WF_VALIDATE_FN, } from "big-config";
import { createWorkflow } from "big-config/core";
import { createExitStepFn, createPrintErrorStepFn } from "big-config/step-fns";
import { createWorkflowStar, mergeParams, parseArgs, printStepFn, registerWorkflow, runSteps, } from "big-config/workflow";
import * as onceTools from "once/dist/src/once/tools.js";
import { syncAliases, toBcOpts } from "./interop.js";
import { optsFn } from "./params.js";
import * as walterTools from "./tools.js";
import { validate } from "./validation.js";
const START = "io.github.bigconfig-ai.walter.package/start";
const END = "io.github.bigconfig-ai.walter.package/end";
const PIPELINE_START = "io.github.bigconfig-ai.walter.package/start-create-or-delete";
const PIPELINE_END = "io.github.bigconfig-ai.walter.package/end-create-or-delete";
export const stepFns = [
    printStepFn,
    createExitStepFn(END),
    createPrintErrorStepFn(END),
];
const TOFU_APPLY = "render tofu:init tofu:apply:-auto-approve";
const TOFU_DESTROY = "render tofu:init tofu:destroy:-auto-approve";
const ANSIBLE_RUN = "render ansible-playbook:main.yml";
for (const [step, fn] of [
    [onceTools.TOFU, onceTools.tofu],
    [walterTools.ANSIBLE, walterTools.ansible],
    [onceTools.ANSIBLE_LOCAL, onceTools.ansibleLocal],
]) {
    registerWorkflow(step, fn);
}
export const create = createWorkflowStar({
    firstStep: PIPELINE_START,
    lastStep: PIPELINE_END,
    pipeline: [
        onceTools.TOFU, [TOFU_APPLY, optsFn],
        walterTools.ANSIBLE, [ANSIBLE_RUN, optsFn],
        onceTools.ANSIBLE_LOCAL, [ANSIBLE_RUN, optsFn],
    ],
});
export const build = createWorkflowStar({
    firstStep: PIPELINE_START,
    lastStep: PIPELINE_END,
    pipeline: [
        onceTools.TOFU, ["render", optsFn],
        walterTools.ANSIBLE, ["render", optsFn],
        onceTools.ANSIBLE_LOCAL, ["render", optsFn],
    ],
});
export const deleteWorkflow = createWorkflowStar({
    firstStep: PIPELINE_START,
    lastStep: PIPELINE_END,
    pipeline: [onceTools.TOFU, [TOFU_DESTROY, optsFn]],
});
const TOOL_OPTS_KEYS = [
    `${onceTools.TOFU}-opts`,
    `${walterTools.ANSIBLE}-opts`,
    `${onceTools.ANSIBLE_LOCAL}-opts`,
];
export function walter(sfns, opts0) {
    const opts = toBcOpts(opts0);
    const withFns = {
        [WF_CREATE_FN]: create,
        [WF_BUILD_FN]: build,
        [WF_DELETE_FN]: deleteWorkflow,
        [WF_VALIDATE_FN]: validate,
        ...opts,
    };
    const merged = mergeParams(TOOL_OPTS_KEYS, opts[WF_PARAMS] ?? {}, withFns);
    const wf = createWorkflow({
        firstStep: START,
        wireFn: (step, resolvedStepFns) => step === START
            ? [(o) => runSteps(resolvedStepFns, o), END]
            : [(o) => o, undefined],
    });
    return syncAliases(wf(sfns, merged));
}
export function walterStar(args, opts = {}) {
    return walter(stepFns, { ...parseArgs(args), [ENV]: "shell", ...toBcOpts(opts) });
}
//# sourceMappingURL=package.js.map