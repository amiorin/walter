import * as bcWorkflow from "big-config/workflow";
import { tofuParams } from "once/dist/src/once/params.js";
import { syncAliases, toBcOpts } from "./interop.js";
const START_STEP = "io.github.bigconfig-ai.walter.package/start-create-or-delete";
export function optsFn(opts) {
    const withEnv = syncAliases(bcWorkflow.readBcPars(toBcOpts(opts)));
    return syncAliases(tofuParams(withEnv));
}
export function walterOpts(opts) {
    return optsFn(bcWorkflow.newPrefix(toBcOpts(opts), START_STEP));
}
//# sourceMappingURL=params.js.map