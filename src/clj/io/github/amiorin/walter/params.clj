(ns io.github.amiorin.walter.params
  (:require
   [big-config.workflow :as workflow]
   [io.github.amiorin.once.params :as params-once]
   [io.github.amiorin.walter.options :as options]))

(def opts-fn (comp params-once/tofu-params workflow/read-bc-pars))

(def walter-opts (comp opts-fn #(workflow/new-prefix % :io.github.amiorin.walter.package/start-create-or-delete)))

(comment
  (workflow/new-prefix {} :io.github.amiorin.walter.package/start-create-or-delete)
  (walter-opts options/bb))
