(ns io.github.bigconfig-ai.walter.package
  (:require
   [big-config :as bc]
   [big-config.core :as core]
   [big-config.run :as run]
   [big-config.step-fns :as step-fns]
   [big-config.utils :refer [debug]]
   [big-config.workflow :as workflow]
   [io.github.bigconfig-ai.once.tools :as tools-once]
   [io.github.bigconfig-ai.walter.describe :as describe]
   [io.github.bigconfig-ai.walter.options :as options]
   [io.github.bigconfig-ai.walter.params :as params]
   [io.github.bigconfig-ai.walter.tools :as tools-walter]
   [io.github.bigconfig-ai.walter.validation :as validation]))

(def step-fns [workflow/print-step-fn
               (step-fns/->exit-step-fn ::end)
               (step-fns/->print-error-step-fn ::end)])

(def create
  (workflow/->workflow* {:first-step ::start-create-or-delete
                         :last-step ::end-create-or-delete
                         :pipeline [::tools-once/tofu ["render tofu:init tofu:apply:-auto-approve" params/opts-fn]
                                    ::tools-walter/ansible ["render ansible-playbook:main.yml" params/opts-fn]
                                    ::tools-once/ansible-local ["render ansible-playbook:main.yml" params/opts-fn]]}))

(def build
  (workflow/->workflow* {:first-step ::start-create-or-delete
                         :last-step ::end-create-or-delete
                         :pipeline [::tools-once/tofu ["render" params/opts-fn]
                                    ::tools-walter/ansible ["render" params/opts-fn]
                                    ::tools-once/ansible-local ["render" params/opts-fn]]}))

(def delete
  (workflow/->workflow* {:first-step ::start-create-or-delete
                         :last-step ::end-create-or-delete
                         :pipeline [::tools-once/tofu ["render tofu:init tofu:destroy:-auto-approve" params/opts-fn]]}))

(def ^:private tool-workflows
  {::tools-once/tofu tools-once/tofu
   ::tools-walter/ansible tools-walter/ansible
   ::tools-once/ansible-local tools-once/ansible-local})

(when-let [register-workflow (ns-resolve 'big-config.workflow 'register-workflow)]
  (run! (fn [[step f]]
          (register-workflow step f))
        tool-workflows))

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

(defn walter
  [step-fns {:keys [::workflow/params] :as opts}]
  (let [opts (->> opts
                  (merge {::workflow/create-fn create
                          ::workflow/build-fn build
                          ::workflow/delete-fn delete
                          ::workflow/validate-fn validation/validate
                          ::workflow/describe-fn describe/describe})
                  (workflow/merge-params [::tools-once/tofu-opts
                                          ::tools-walter/ansible-opts
                                          ::tools-once/ansible-local-opts]
                                         params))
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
    (walter* "build" (merge options/walter
                            {::bc/env :repl
                             ::run/shell-opts {:err *err*
                                               :out *out*}})))
  (-> tap-values))
