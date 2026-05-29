(ns io.github.bigconfig-ai.walter.params
  (:require
   [big-config.workflow :as workflow]
   [io.github.bigconfig-ai.once.params :as params-once]
   [io.github.bigconfig-ai.walter.options :as options]))

(def opts-fn (comp params-once/tofu-params workflow/read-bc-pars))

(def walter-opts (comp opts-fn #(workflow/new-prefix % :io.github.bigconfig-ai.walter.package/start-create-or-delete)))

(comment
  (workflow/new-prefix {} :io.github.bigconfig-ai.walter.package/start-create-or-delete)
  (walter-opts options/bb))
