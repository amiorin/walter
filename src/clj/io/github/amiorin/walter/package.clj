(ns io.github.amiorin.walter.package
  (:require
   [big-config :as bc]
   [big-config.core :as core]
   [big-config.run :as run]
   [big-config.step-fns :as step-fns]
   [big-config.utils :refer [debug]]
   [big-config.workflow :as workflow]
   [io.github.amiorin.once.tools :as tools-once]
   [io.github.amiorin.walter.options :as options]
   [io.github.amiorin.walter.params :as params]
   [io.github.amiorin.walter.tools :as tools-walter]))

(def step-fns [workflow/print-step-fn
               (step-fns/->exit-step-fn ::end)
               (step-fns/->print-error-step-fn ::end)])

(def create
  (workflow/->workflow* {:first-step ::start-create-or-delete
                         :last-step ::end-create-or-delete
                         :pipeline [::tools-once/tofu ["render tofu:init tofu:apply:-auto-approve" params/opts-fn]
                                    ::tools-walter/ansible ["render ansible-playbook:main.yml" params/opts-fn]
                                    ::tools-once/ansible-local ["render ansible-playbook:main.yml" params/opts-fn]]}))

(comment
  (debug tap-values
    (create [] (merge options/walter
                      {::bc/env :repl
                       ::tools-once/tofu-opts (workflow/parse-args "render")
                       ::tools-walter/ansible-opts (workflow/parse-args "render")
                       ::tools-once/ansible-local-opts (workflow/parse-args "render")
                       ::run/shell-opts {:err *err*
                                         :out *out*}})))
  (-> tap-values))

(def delete
  (workflow/->workflow* {:first-step ::start-create-or-delete
                         :last-step ::end-create-or-delete
                         :pipeline [::tools-once/tofu ["render tofu:init tofu:destroy:-auto-approve" params/opts-fn]]}))

(defn walter
  [step-fns {:keys [::workflow/params] :as opts}]
  (let [hyperscaler "hcloud"
        opts (->> opts
                  (merge {::workflow/create-fn create
                          ::workflow/delete-fn delete})
                  (workflow/merge-params [::tools-once/tofu-opts] params))
        wf (core/->workflow {:first-step ::start
                             :wire-fn (fn [step step-fns]
                                        (case step
                                          ::start [(partial workflow/run-steps step-fns) ::end]
                                          ::end [identity]))})]
    (wf step-fns opts)))

(defn walter*
  [args & [opts]]
  (let [opts (merge (workflow/parse-args args)
                    {::bc/env :shell}
                    opts)]
    (walter step-fns opts)))

(comment
  (debug tap-values
    (walter* "create" (merge options/walter
                             {::bc/env :repl
                              ::run/shell-opts {:err *err*
                                                :out *out*}})))
  (-> tap-values))
