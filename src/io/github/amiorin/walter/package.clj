(ns io.github.amiorin.walter.package
  (:require
   [babashka.process :as p]
   [big-config :as bc]
   [big-config.core :as core]
   [big-config.render :as render]
   [big-config.run :as run]
   [big-config.step-fns :as step-fns]
   [big-config.utils :refer [debug]]
   [big-config.workflow :as workflow]
   [cheshire.core :as json]
   [com.rpl.specter :as s]
   [io.github.amiorin.walter.tools :as tools]))

(def step-fns [workflow/print-step-fn
               (step-fns/->exit-step-fn ::end)
               (step-fns/->print-error-step-fn ::end)])

(defn extract-params
  [opts]
  (let [ip (-> (p/shell {:dir (workflow/path opts ::tools/tofu)
                         :out :string} "tofu show --json")
               :out
               (json/parse-string keyword)
               (->> (s/select-one [:values :root_module :resources s/FIRST :values :ipv4_address])))]
    {::workflow/params {:ip ip}}))

(def create
  (workflow/->workflow* {:first-step ::start-create-or-delete
                         :last-step ::end-create-or-delete
                         :pipeline [::tools/tofu ["render tofu:init tofu:apply:-auto-approve"]
                                    ::tools/ansible ["render ansible-playbook:main.yml" extract-params]]}))

(def delete
  (workflow/->workflow* {:first-step ::start-create-or-delete
                         :last-step ::end-create-or-delete
                         :pipeline [::tools/tofu ["render tofu:init tofu:destroy:-auto-approve"]]}))

(defn walter
  [step-fns opts]
  (let [opts (merge {::workflow/create-fn create
                     ::workflow/delete-fn delete}
                    opts)
        wf (core/->workflow {:first-step ::start
                             :wire-fn (fn [step step-fns]
                                        (case step
                                          ::start [(partial workflow/run-steps step-fns) ::end]
                                          ::end [identity]))})]
    (wf step-fns opts)))

(comment
  (debug tap-values
    (let [profile "insta-a"]
      (walter [(fn [f step opts]
                 (tap> [step opts])
                 (f step opts))]
              {::bc/env :repl
               ::run/shell-opts {:err *err*
                                 :out *out*}
               ::workflow/steps [:create]
               ::render/profile profile
               ::workflow/prefix (format ".dist/%s" profile)
               ::workflow/create-opts {::tools/tofu-opts (workflow/parse-args "render tofu:init")
                                       ::tools/ansible-opts (workflow/parse-args "render")}
               ::workflow/delete-opts (workflow/parse-args "render")})))
  (-> tap-values))

(defn walter*
  [args & [opts]]
  (let [profile "default"
        opts (merge (workflow/parse-args args)
                    {::bc/env :shell
                     ::render/profile profile
                     ::workflow/prefix (format ".dist/%s" profile)}
                    opts)]
    (walter step-fns opts)))

(comment
  (debug tap-values
    (walter* "create" {::bc/env :repl}))
  (-> tap-values))
