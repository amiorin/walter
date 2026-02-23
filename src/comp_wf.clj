(ns comp-wf
  (:require
   [babashka.process :as p]
   [big-config :as bc]
   [big-config.core :as core]
   [big-config.render :as render]
   [big-config.step-fns :as step-fns]
   [big-config.utils :refer [debug]]
   [big-config.workflow :as workflow]
   [cheshire.core :as json]
   [com.rpl.specter :as s]
   [tool-wf :as tool-wf]))

(def step-fns [workflow/print-step-fn
               (step-fns/->exit-step-fn ::end)
               (step-fns/->print-error-step-fn ::end)])

(defn extract-params
  [opts]
  (let [ip (-> (p/shell {:dir (workflow/path opts ::tofu)
                         :out :string} "tofu show --json")
               :out
               (json/parse-string keyword)
               (->> (s/select-one [:values :root_module :resources s/FIRST :values :ipv4_address])))]
    {::workflow/params {:ip ip}}))

(defn walter-create
  [step-fns {:keys [::tofu-opts ::ansible-opts] :as opts}]
  (let [globals-opts (workflow/select-globals opts)
        tofu-opts (merge (workflow/parse-args "render tofu:init tofu:apply:-auto-approve")
                         globals-opts
                         tofu-opts)
        ansible-opts (merge (workflow/parse-args "render ansible-playbook:main.yml")
                            globals-opts
                            ansible-opts)
        opts* (atom opts)
        wf (core/->workflow {:first-step ::start
                             :wire-fn (fn [step step-fns]
                                        (case step
                                          ::start [core/ok ::tofu]
                                          ::tofu [(partial tool-wf/tofu step-fns) ::ansible]
                                          ::ansible [(partial tool-wf/ansible step-fns) ::end]
                                          ::end [identity]))
                             :next-fn (fn [step next-step {:keys [::bc/exit] :as opts}]
                                        (if (#{::tofu ::ansible} step)
                                          (do
                                            (swap! opts* merge (select-keys opts [::bc/exit ::bc/err]))
                                            (swap! opts* assoc step opts))
                                          (reset! opts* opts))
                                        (cond
                                          (= step ::end)
                                          [nil @opts*]

                                          (> exit 0)
                                          [::end @opts*]

                                          :else
                                          [next-step (case next-step
                                                       ::tofu tofu-opts
                                                       ::ansible (merge-with merge ansible-opts (extract-params @opts*))
                                                       @opts*)]))})]
    (wf step-fns opts)))

(defn walter-delete
  [step-fns opts]
  (let [opts (merge (workflow/parse-args "render tofu:destroy:-auto-approve")
                    opts)]
    (tool-wf/tofu step-fns opts)))

(defn walter
  [step-fns opts]
  (let [opts (merge {::workflow/create-fn walter-create
                     ::workflow/delete-fn walter-delete}
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
               ::workflow/steps [:create]
               ::render/profile profile
               ::workflow/prefix (format ".dist/%s" profile)
               ::workflow/create-opts {::tofu-opts (workflow/parse-args "render tofu:init")
                                       ::ansible-opts (workflow/parse-args "render")}
               ::workflow/delete-opts (workflow/parse-args "render")})))
  (-> tap-values))

(defn walter*
  [args & [opts]]
  (let [        profile "default"
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
